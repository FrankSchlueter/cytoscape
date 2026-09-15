package de.tk.dependencyanalyse.rapui.visgraph.internal;

import com.google.gson.Gson;
import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendBuilder;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendEntry;
import org.eclipse.swt.browser.Browser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

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
 * <h2>Data delivery via Rap-JS bridge (gzip + base64)</h2>
 * <p>Like {@link CytoscapeJsBridge} and {@link VisJsBridge}, the sigma
 * viewer receives its graph payload from the Java side through the
 * {@link BrowserScriptQueue}: {@link #applyData(GraphData)} serializes the
 * graphology payload, gzip-compresses the bytes and base64-encodes the
 * result, then ships the string as the argument to
 * {@code window.vg_setDataGz(b64)}. The iframe decodes the base64, gunzips
 * with the {@code pako} library and rebuilds the graph in place — no
 * fetch, no REST endpoints, no per-session cache.</p>
 *
 * <p>Pushes are queued through {@link #execWhenReady(String)} until
 * {@code vg_viewerReady} fires, so {@link #applyData(GraphData)} can be
 * called before the iframe IIFE has run (e.g. when {@code SigmaViewer} is
 * constructed with an initial dataset).</p>
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
    private volatile ContextMenuSnapshot pendingContextMenu;
    private final java.util.concurrent.atomic.AtomicReference<Object> lastContextTarget =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Last Leiden cluster color map pushed via {@link #setLeidenColors}. */
    private volatile Map<String, String> currentLeidenColors = Map.of();
    /** Last effective color map pushed via {@link #applyNodeColors}. */
    private volatile Map<String, String> currentEffectiveColors = Map.of();
    /** True when the palette panel should be visible. */
    private volatile boolean paletteVisible = false;

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

    /**
     * Apply the graph payload to the iframe. Serialises the graphology
     * payload (nodes + edges), gzip-compresses the bytes and base64-encodes
     * the result, then ships the string to the iframe via
     * {@code window.vg_setDataGz(b64)}. If the iframe is not yet booted the
     * call is queued until {@code vg_viewerReady} fires — so this method
     * is safe to call from {@code SigmaViewer}'s constructor before the
     * iframe IIFE has run.
     *
     * <p>Matches the {@link CytoscapeJsBridge#applyData(GraphData)} and
     * {@link VisJsBridge#applyData(GraphData)} pattern: data is pushed
     * from Java to JS, no fetch roundtrip, no per-session REST cache.</p>
     */
    public void applyData(GraphData data) {
        this.currentData = data;
        if (data == null || data.getNodes().isEmpty()) {
            execWhenReady("if (window.vg_clear) { window.vg_clear(); }");
            return;
        }
        Map<String, Object> payload = data.toGraphologyElements(currentNodeConfig);
        String json = gson.toJson(payload);
        String b64;
        try {
            b64 = gzipAndBase64(json);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "SigmaJsBridge.applyData: gzip failed", e);
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) payload.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) payload.get("edges");
        LOG.info("SigmaJsBridge.applyData: nodes=" + nodes.size()
                + ", edges=" + edges.size()
                + ", raw=" + json.length() + "B, gz+b64=" + b64.length() + "B");
        // Atomic call: clear + setDataGz so the iframe cannot race on a
        // stale cached payload. Same defensive pattern as the Cytoscape /
        // vis bridges.
        execWhenReady("if (window.vg_clear) { window.vg_clear(); } "
                + "if (window.vg_setDataGz) { window.vg_setDataGz("
                + gson.toJson(b64) + "); }");
    }

    public void applyNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        execWhenReady("if (window.vg_applyNodeConfig) { window.vg_applyNodeConfig(" + gson.toJson(toJsonNodeConfig(config)) + "); }");
    }

    /**
     * Re-derive the color-palette entries from the cached color maps
     * and push the resulting panel state to the iframe.
     *
     * <p>Visibility is driven by the most recent non-empty push:</p>
     * <ul>
     *   <li>{@link #applyNodeColors} with a non-empty map → palette
     *       shown with entries from {@link LegendBuilder#combined};</li>
     *   <li>{@link #setLeidenColors} with a non-empty map → palette
     *       shown with entries from {@link LegendBuilder#fromLeidenClusters};</li>
     *   <li>both maps empty → palette hidden.</li>
     * </ul>
     *
     * <p>The panel does NOT survive a {@link #clear()} call — that path
     * resets both cached maps and pushes {@code vg_hideColorPalette}.</p>
     */
    private void refreshPalette() {
        Map<String, String> effective = currentEffectiveColors;
        Map<String, String> leiden = currentLeidenColors;
        boolean anyColors = !effective.isEmpty() || !leiden.isEmpty();
        if (!anyColors) {
            if (paletteVisible) {
                paletteVisible = false;
                execWhenReady("if (window.vg_hideColorPalette) { window.vg_hideColorPalette(); }");
            }
            return;
        }
        List<LegendEntry> entries = derivePaletteEntries(effective, leiden);
        paletteVisible = true;
        execWhenReady("if (window.vg_applyColorPalette) { window.vg_applyColorPalette("
                + gson.toJson(entries) + ", true); }");
    }

    /**
     * Pick the highest-signal LegendBuilder source for the current
     * color maps. {@link LegendBuilder#combined} already merges all
     * three sources (Tag → Cluster → NodeType) and dedups by hex, so
     * passing both maps there yields the richest labels. When the
     * effective map is empty we fall back to the pure Leiden builder
     * (still produces "Cluster N" labels) and finally to a generic
     * "Color N" derivation in the iframe for the no-config case.
     */
    private List<LegendEntry> derivePaletteEntries(Map<String, String> effective,
                                                     Map<String, String> leiden) {
        if (!effective.isEmpty()) {
            return LegendBuilder.combined(currentData, currentNodeConfig, leiden);
        }
        if (!leiden.isEmpty()) {
            return LegendBuilder.fromLeidenClusters(currentData, leiden);
        }
        return List.of();
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
     *
     * <p>Also drives the auto-managed Color Palette: a non-empty map
     * makes the palette panel appear (entries derived via
     * {@link LegendBuilder#combined}); an empty map hides the palette.
     * The palette's lifecycle is tied to the most recent non-empty
     * color push so successive {@code applyNodeColors(empty)} calls
     * clean up state.</p>
     */
    public void applyNodeColors(Map<String, String> effective) {
        Map<String, String> safe = effective == null ? Map.of() : effective;
        this.currentEffectiveColors = safe;
        execWhenReady("if (window.vg_applyNodeColors) { window.vg_applyNodeColors(" + gson.toJson(safe) + "); }");
        refreshPalette();
    }

    /**
     * Push Leiden cluster colors. Each entry maps node id → hex color.
     * Also drives the auto-managed Color Palette: a non-empty map makes
     * the palette panel appear (entries derived via
     * {@link LegendBuilder#fromLeidenClusters}); an empty map hides it.
     */
    public void setLeidenColors(Map<String, String> colors) {
        Map<String, String> safe = colors == null ? Map.of() : colors;
        this.currentLeidenColors = safe;
        execWhenReady("if (window.vg_applyLeidenColors) { window.vg_applyLeidenColors(" + gson.toJson(safe) + "); }");
        refreshPalette();
    }

    public void clear() {
        // Drop cached color state so the auto-managed palette does not
        // resurrect after a clear(). Without this the next data load
        // would briefly show the previous palette until the next
        // applyNodeColors / setLeidenColors call lands.
        this.currentLeidenColors = Map.of();
        this.currentEffectiveColors = Map.of();
        this.paletteVisible = false;
        execWhenReady("if (window.vg_clear) { window.vg_clear(); } "
                + "if (window.vg_hideColorPalette) { window.vg_hideColorPalette(); }");
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

    /**
     * Gzip-compress a JSON string and base64-encode the resulting bytes so
     * the payload can travel as a JavaScript string argument through
     * {@link BrowserScriptQueue}. The iframe decodes the base64, gunzips
     * with {@code pako.ungzip(bytes, {toText:true})} and parses the JSON.
     *
     * <p>The compression ratio on graphology payloads is typically 5–10×
     * because of repetitive labels, colors and node-id prefixes. The
     * base64 encoding adds ~33% overhead but is unavoidable — the
     * underlying {@code Browser.execute(...)} API takes a String, not a
     * byte array.</p>
     */
    static String gzipAndBase64(String json) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
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
