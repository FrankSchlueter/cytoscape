package de.tk.dependencyanalyse.rapui.visgraph.internal;

import com.google.gson.Gson;
import de.tk.dependencyanalyse.rapui.visgraph.api.NodeConfigRegistry;
import de.tk.dependencyanalyse.rapui.visgraph.api.SigmaGraphCache;
import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendEntry;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.service.UISession;
import org.eclipse.rap.rwt.service.UISessionEvent;
import org.eclipse.rap.rwt.service.UISessionListener;
import org.eclipse.swt.browser.Browser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the BrowserFunction handlers that the {@code sigma-viewer.js}
 * bridge registers with the embedded {@link Browser}, plus the lifecycle
 * hooks that deliver the graphology payload to the iframe.
 *
 * <p>This is the sigma.js counterpart to {@link CytoscapeJsBridge}. The
 * function names use the {@code vg_} prefix (vs {@code cgv_} for cytoscape
 * and {@code vgv_} for vis-network) so all three viewers can coexist in the
 * same RAP session. Scripts are serialised through
 * {@link BrowserScriptQueue} to honour RAP's "one script in flight" rule.</p>
 *
 * <h2>Data delivery via REST</h2>
 * <p>Unlike the Cytoscape/vis viewers that push the elements payload via
 * {@code BrowserFunction.exec}, the sigma viewer fetches the graph from
 * {@code /api/sigma/nodes} and {@code /api/sigma/edges}. This keeps the
 * iframe-side memory profile predictable for large graphs (the
 * {@link SigmaGraphCache} holds at most one entry per RAP session, and the
 * daemon evictor drops stale entries after
 * {@link SigmaGraphCache#MAX_AGE_MINUTES} minutes) and lets the response
 * stream through the standard Servlet stack with GZIP compression
 * enabled in {@code application.yml}.</p>
 *
 * <p>The initial token is generated server-side and inlined into the iframe
 * HTML by {@link de.tk.dependencyanalyse.rapui.visgraph.SigmaViewer} via
 * the {@code __VG_INITIAL_NODES_URL__} / {@code __VG_INITIAL_EDGES_URL__}
 * placeholders, so the iframe can refetch without a Java roundtrip once
 * the user changes the layout (the fetch URL is unchanged, the response
 * carries a {@code 304} on the next request when nothing changed).</p>
 */
public final class SigmaJsBridge {

    private static final Logger LOG = Logger.getLogger(SigmaJsBridge.class.getName());

    private static final String FN_VIEWER_READY = "vg_viewerReady";
    private static final String FN_NODE_SELECTED = "vg_notifyNodeSelected";
    private static final String FN_REL_SELECTED = "vg_notifyRelationshipSelected";
    private static final String FN_SEL_CLEARED = "vg_notifySelectionCleared";
    private static final String FN_REQ_NODE_CTX = "vg_requestNodeContextMenu";
    private static final String FN_REQ_REL_CTX = "vg_requestRelationshipContextMenu";
    private static final String FN_INVOKE_CTX = "vg_invokeContextMenuAction";

    private final Browser browser;
    private final Gson gson = new Gson();
    private final BrowserScriptQueue scriptQueue;
    private final BrowserFunctions functions;

    private final List<Runnable> onReadyCallbacks = new CopyOnWriteArrayList<>();
    private final List<Consumer<GraphNode>> nodeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<GraphRelationship>> relListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> clearedListeners = new CopyOnWriteArrayList<>();
    private final List<ContextHandler> contextHandlers = new CopyOnWriteArrayList<>();
    private final List<ContextActionHandler> contextActionHandlers = new CopyOnWriteArrayList<>();

    private volatile boolean viewerReady = false;
    private volatile GraphData currentData;
    private volatile NodeConfig currentNodeConfig;
    private volatile String currentToken;
    private volatile ContextMenuSnapshot pendingContextMenu;
    private final java.util.concurrent.atomic.AtomicReference<Object> lastContextTarget =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Tracks whether the per-session UISessionListener is registered. */
    private final AtomicBoolean sessionListenerRegistered = new AtomicBoolean(false);

    public SigmaJsBridge(Browser browser) {
        this.browser = browser;
        this.scriptQueue = new BrowserScriptQueue(browser);
        this.functions = new BrowserFunctions(browser);
        registerAll();
    }

    public boolean isViewerReady() { return viewerReady; }

    public void onReady(Runnable r) {
        if (viewerReady) {
            r.run();
        } else {
            onReadyCallbacks.add(r);
        }
    }

    /**
     * Queue the given script for execution. Runs immediately if the iframe
     * is ready, otherwise enqueues until {@code vg_viewerReady} fires.
     * Mirrors the pattern used by the vis-network and Cytoscape bridges.
     */
    private void execWhenReady(String script) {
        if (viewerReady) {
            exec(script);
        } else {
            onReadyCallbacks.add(() -> {
                try { exec(script); } catch (Exception e) {
                    LOG.log(Level.WARNING, "execWhenReady failed", e);
                }
            });
        }
    }

    public void addNodeListener(Consumer<GraphNode> l) { nodeListeners.add(l); }
    public void addRelationshipListener(Consumer<GraphRelationship> l) { relListeners.add(l); }
    public void addSelectionClearedListener(Runnable l) { clearedListeners.add(l); }
    public void addContextHandler(ContextHandler h) { contextHandlers.add(h); }
    public void addContextActionHandler(ContextActionHandler h) { contextActionHandlers.add(h); }

    public void setCurrentData(GraphData data) { this.currentData = data; }

    public void setCurrentNodeConfig(NodeConfig config) { this.currentNodeConfig = config; }

    public NodeConfig getCurrentNodeConfig() { return currentNodeConfig; }

    /** Last token used to push the current graph to the iframe. */
    public String getCurrentToken() { return currentToken; }

    /**
     * Compute the initial fetch URLs for a freshly-created SigmaViewer
     * iframe. Called by the viewer BEFORE {@code Browser.setText(html)} so
     * the {@code __VG_INITIAL_NODES_URL__} / {@code __VG_INITIAL_EDGES_URL__}
     * placeholders in the HTML template can be replaced with concrete
     * addresses.
     *
     * <p>Returns an empty array when no graph is available yet — the viewer
     * will leave the placeholders empty and the iframe will only fetch when
     * a later {@code applyData(...)} call fires.</p>
     */
    public String[] computeInitialUrls() {
        if (currentData == null || currentData.getNodes().isEmpty()) {
            return new String[] { "", "" };
        }
        return buildUrls(currentToken, currentData);
    }

    /**
     * Apply the graph payload to the iframe. Stores the data in the
     * per-session cache, generates a fresh token, and triggers an iframe
     * fetch via {@code window.vg_loadGraph(...)}. If the iframe is not yet
     * booted the call is queued until {@code vg_viewerReady} fires.
     */
    public void applyData(GraphData data) {
        this.currentData = data;
        if (data == null || data.getNodes().isEmpty()) {
            currentToken = null;
            execWhenReady("if (window.vg_clear) { window.vg_clear(); }");
            return;
        }
        String token = generateToken();
        this.currentToken = token;
        registerSessionCleanupOnce();
        String httpSessionId = currentHttpSessionId();
        SigmaGraphCache.put(httpSessionId, token, data);
        String[] urls = buildUrls(token, data);
        // Atomic call: clear + loadGraph so the iframe cannot race on
        // a stale initial URL. Same defensive pattern as Cytoscape's
        // applyData (see CytoscapeJsBridge.applyData).
        execWhenReady("if (window.vg_clear) { window.vg_clear(); } "
                + "if (window.vg_loadGraph) { window.vg_loadGraph("
                + gson.toJson(urls[0]) + ", " + gson.toJson(urls[1]) + "); }");
    }

    public void applyNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        NodeConfigRegistry.put(currentHttpSessionId(), config);
        execWhenReady("if (window.vg_applyNodeConfig) { window.vg_applyNodeConfig(" + gson.toJson(toJsonNodeConfig(config)) + "); }");
    }

    /**
     * Push the engine-agnostic per-node effective color map produced by
     * {@link NodeColorResolver#resolveEffectiveColors} to the iframe.
     *
     * <p>The JS handler {@code vg_applyNodeColors} (in
     * {@code sigma-viewer.js}) stores the map in
     * {@code currentEffectiveColors} and rebuilds the nodeReducer so
     * the effective colors win over both {@code currentNodeConfig}
     * (label/tag colors) and {@code currentLeidenColors}.</p>
     */
    public void applyNodeColors(Map<String, String> effective) {
        execWhenReady("if (window.vg_applyNodeColors) { window.vg_applyNodeColors(" + gson.toJson(effective == null ? Map.of() : effective) + "); }");
    }

    /**
     * Push Leiden cluster colors. Each entry maps node id → hex color.
     */
    public void setLeidenColors(Map<String, String> colors) {
        execWhenReady("if (window.vg_applyLeidenColors) { window.vg_applyLeidenColors(" + gson.toJson(colors) + "); }");
    }

    public void applyLegend(List<LegendEntry> entries, boolean enabled) {
        execWhenReady("if (window.vg_applyLegend) { window.vg_applyLegend("
                + gson.toJson(entries == null ? List.of() : entries)
                + ", " + (enabled ? "true" : "false") + "); }");
    }

    public void clearLegend() {
        execWhenReady("if (window.vg_applyLegend) { window.vg_applyLegend([], false); }");
    }

    public void clear() {
        execWhenReady("if (window.vg_clear) { window.vg_clear(); }");
    }

    public void fitToScreen() {
        execWhenReady("if (window.vg_fitToScreen) { window.vg_fitToScreen(); }");
    }

    public void setLayout(String algorithm) {
        execWhenReady("if (window.vg_setLayout) { window.vg_setLayout(" + gson.toJson(algorithm) + "); }");
    }

    public void setLayoutOptions(Map<String, Object> options) {
        if (options == null) return;
        execWhenReady("if (window.vg_setLayoutOptions) { window.vg_setLayoutOptions(" + gson.toJson(options) + "); }");
    }

    /**
     * Ask the sigma iframe to resize itself to the current container size
     * and re-fit. Called from the {@code SigmaViewer}'s {@code Resize}
     * listener so the canvas follows the composite's actual dimensions.
     */
    public void resize() {
        exec("if (window.vg_resize) { window.vg_resize(); }");
    }

    /**
     * Iframe-side cleanup hook invoked just before the Browser widget is
     * disposed (e.g. on an engine switch to vis-network or cytoscape).
     * Removes the floating tooltip element from {@code document.body} and
     * clears any pending tooltip state. Without this the orphan
     * {@code #vg-tooltip} div would survive the iframe swap and float on top
     * of the next engine's canvas.
     */
    public void disposeIframe() {
        exec("try { if (window.vg_dispose) { window.vg_dispose(); } } catch(e){}");
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y) {
        showContextMenu(entries, x, y, null);
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y, Object target) {
        lastContextTarget.set(target);
        ContextMenuSnapshot snap = new ContextMenuSnapshot(entries, target);
        this.pendingContextMenu = snap;
        exec("window.vg_showContextMenu(" + gson.toJson(snap.toJson()) + ", " + x + ", " + y + ");");
    }

    public Object getLastContextTarget() {
        return lastContextTarget.get();
    }

    public void hideContextMenu() {
        exec("window.vg_hideContextMenu && window.vg_hideContextMenu();");
    }

    public void dispose() {
        functions.dispose();
        scriptQueue.dispose();
    }

    /* ---- private ---- */

    private void exec(String script) {
        scriptQueue.exec(script);
    }

    private String[] buildUrls(String token, GraphData data) {
        return new String[] {
                "/api/sigma/nodes?token=" + token + "&v=" + data.getNodes().size(),
                "/api/sigma/edges?token=" + token + "&v=" + data.getRelationships().size()
        };
    }

    private static String generateToken() {
        return UUID.randomUUID().toString();
    }

    private static String currentSessionId() {
        UISession ui = RWT.getUISession();
        return ui == null ? "no-session" : ui.getId();
    }

    /**
     * Best-effort HTTP-session-id lookup. Both this bridge (which runs on
     * the RAP UI thread) and the {@link de.tk.dependencyanalyse.rapui.visgraph.api.SigmaGraphController}
     * (which runs on regular Spring threads) need to look up the same
     * cache entry — so both use {@link jakarta.servlet.http.HttpSession#getId()}.
     * The RAP {@link UISession#getHttpSession()} bridge exposes the same
     * session on both threads.
     */
    private static String currentHttpSessionId() {
        UISession ui = RWT.getUISession();
        if (ui == null) {
            return currentSessionId();
        }
        try {
            if (ui.getHttpSession() != null) {
                return ui.getHttpSession().getId();
            }
        } catch (Exception ex) {
            // ignore
        }
        return currentSessionId();
    }

    /**
     * Register a per-session cleanup hook exactly once per bridge. The
     * listener fires when the underlying RAP UI session is destroyed (logout,
     * tab close, server-side timeout) and evicts the corresponding entries
     * from {@link SigmaGraphCache} and {@link NodeConfigRegistry}.
     *
     * <p>The defensive daemon evictor in
     * {@link SigmaGraphCache#evictStale()} catches the rare case where the
     * listener does NOT fire (e.g. server crash, RAP failover).</p>
     */
    private void registerSessionCleanupOnce() {
        if (sessionListenerRegistered.compareAndSet(false, true)) {
            UISession ui = RWT.getUISession();
            if (ui == null) return;
            String sid = currentHttpSessionId();
            try {
                ui.addUISessionListener(new UISessionListener() {
                    @Override public void beforeDestroy(UISessionEvent event) {
                        SigmaGraphCache.evictSession(sid);
                        NodeConfigRegistry.evictSession(sid);
                    }
                });
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Could not register UISessionListener — daemon evictor will catch up", ex);
            }
        }
    }

    /**
     * Convert a {@link NodeConfig} into a JSON-friendly map shape for the
     * JS bridge. Mirrors {@link CytoscapeJsBridge#toJsonNodeConfig} so the
     * two engines consume the same payload shape.
     */
    private static Map<String, Object> toJsonNodeConfig(NodeConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (cfg == null) {
            out.put("showTitle", true);
            out.put("labelColors", Map.of());
            out.put("labelShapes", Map.of());
            out.put("tagColors", Map.of());
            out.put("globalTagColors", Map.of());
            return out;
        }
        out.put("showTitle", cfg.isShowTitle());
        out.put("labelColors", new LinkedHashMap<>(cfg.getLabelColors()));
        out.put("labelShapes", new LinkedHashMap<>(cfg.getLabelShapes()));
        Map<String, Object> tags = new LinkedHashMap<>();
        cfg.getTagColors().forEach((label, byProp) -> {
            Map<String, Object> inner = new LinkedHashMap<>();
            byProp.forEach((prop, tp) -> inner.put(prop, new LinkedHashMap<>(tp.getValueColors())));
            tags.put(label, inner);
        });
        out.put("tagColors", tags);
        Map<String, Object> globals = new LinkedHashMap<>();
        cfg.getGlobalTagColors().forEach((prop, byValue) ->
                globals.put(prop, new LinkedHashMap<>(byValue)));
        out.put("globalTagColors", globals);
        return out;
    }

    private void registerAll() {
        functions.create(FN_VIEWER_READY, args -> {
            LOG.info("SigmaJsBridge: vg_viewerReady received from iframe");
            viewerReady = true;
            for (Runnable r : onReadyCallbacks) {
                try { r.run(); } catch (Exception e) {
                    LOG.log(Level.WARNING, "onReady callback failed", e);
                }
            }
            onReadyCallbacks.clear();
            return null;
        });
        functions.create(FN_NODE_SELECTED, args -> {
            String id = BrowserFunctions.stringAt(args, 0);
            GraphData d = currentData;
            if (d == null) return null;
            d.findNode(id).ifPresent(n -> nodeListeners.forEach(l -> {
                try { l.accept(n); } catch (Exception e) {
                    LOG.log(Level.WARNING, "nodeListener failed", e);
                }
            }));
            return null;
        });
        functions.create(FN_REL_SELECTED, args -> {
            String id = BrowserFunctions.stringAt(args, 0);
            GraphData d = currentData;
            if (d == null) return null;
            d.findRelationship(id).ifPresent(r -> relListeners.forEach(l -> {
                try { l.accept(r); } catch (Exception e) {
                    LOG.log(Level.WARNING, "relListener failed", e);
                }
            }));
            return null;
        });
        functions.create(FN_SEL_CLEARED, args -> {
            clearedListeners.forEach(Runnable::run);
            return null;
        });
        functions.create(FN_REQ_NODE_CTX, args -> {
            String id = BrowserFunctions.stringAt(args, 0);
            int x = BrowserFunctions.intAt(args, 1);
            int y = BrowserFunctions.intAt(args, 2);
            GraphData d = currentData;
            if (d == null) return null;
            d.findNode(id).ifPresent(node -> {
                for (ContextHandler h : contextHandlers) {
                    try {
                        List<ContextMenuEntry> entries = h.forNode(node);
                        if (entries != null && !entries.isEmpty()) {
                            showContextMenu(entries, x, y, node);
                            return;
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "contextHandler failed", e);
                    }
                }
            });
            return null;
        });
        functions.create(FN_REQ_REL_CTX, args -> {
            String id = BrowserFunctions.stringAt(args, 0);
            int x = BrowserFunctions.intAt(args, 1);
            int y = BrowserFunctions.intAt(args, 2);
            GraphData d = currentData;
            if (d == null) return null;
            d.findRelationship(id).ifPresent(rel -> {
                for (ContextHandler h : contextHandlers) {
                    try {
                        List<ContextMenuEntry> entries = h.forRelationship(rel);
                        if (entries != null && !entries.isEmpty()) {
                            showContextMenu(entries, x, y, rel);
                            return;
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "contextHandler failed", e);
                    }
                }
            });
            return null;
        });
        functions.create(FN_INVOKE_CTX, args -> {
            String entryId = BrowserFunctions.stringAt(args, 0);
            ContextMenuSnapshot snap = pendingContextMenu;
            if (snap == null) return null;
            ContextMenuEntry entry = snap.findById(entryId);
            if (entry == null) return null;
            Object target = snap.target();
            for (ContextActionHandler h : contextActionHandlers) {
                h.invoke(entry, target);
            }
            return null;
        });
    }

    @FunctionalInterface
    public interface ContextHandler {
        List<ContextMenuEntry> forNode(GraphNode node);
        default List<ContextMenuEntry> forRelationship(GraphRelationship rel) { return List.of(); }
    }

    @FunctionalInterface
    public interface ContextActionHandler {
        void invoke(ContextMenuEntry entry, Object target);
    }
}
