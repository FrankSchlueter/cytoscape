package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import com.google.gson.Gson;
import org.eclipse.swt.browser.Browser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages all BrowserFunction handlers and queued script execution for the
 * NVL-bridge (`nvl-graph-viewer.js`).
 *
 * <p>Mirror image of {@link VisJsBridge} — same surface area, but
 * speaks NVL on the wire instead of vis-network. The two bridges share
 * {@link BrowserScriptQueue} and {@link BrowserFunctions} so script
 * serialization and BrowserFunction lifecycle code is not duplicated.</p>
 */
public final class NvlJsBridge {

    private static final Logger LOG = Logger.getLogger(NvlJsBridge.class.getName());

    private static final String FN_VIEWER_READY = "vgv_viewerReady";
    private static final String FN_NODE_SELECTED = "vgv_notifyNodeSelected";
    private static final String FN_REL_SELECTED = "vgv_notifyRelationshipSelected";
    private static final String FN_SEL_CLEARED = "vgv_notifySelectionCleared";
    private static final String FN_REQ_NODE_CTX = "vgv_requestNodeContextMenu";
    private static final String FN_REQ_REL_CTX = "vgv_requestRelationshipContextMenu";
    private static final String FN_INVOKE_CTX = "vgv_invokeContextMenuAction";

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
    private final AtomicReference<Object> lastContextTarget = new AtomicReference<>();

    public NvlJsBridge(Browser browser) {
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

    public void addNodeListener(Consumer<GraphNode> l) { nodeListeners.add(l); }
    public void addRelationshipListener(Consumer<GraphRelationship> l) { relListeners.add(l); }
    public void addSelectionClearedListener(Runnable l) { clearedListeners.add(l); }
    public void addContextHandler(ContextHandler h) { contextHandlers.add(h); }
    public void addContextActionHandler(ContextActionHandler h) { contextActionHandlers.add(h); }

    public void setCurrentData(GraphData data) { this.currentData = data; }
    public void setCurrentNodeConfig(NodeConfig config) { this.currentNodeConfig = config; }
    public NodeConfig getCurrentNodeConfig() { return currentNodeConfig; }

    public void applyData(GraphData data) {
        this.currentData = data;
        Map<String, Object> payload = data.toNvlData();
        exec("window.__nvl_nodes = " + gson.toJson(payload.get("nodes")) + ";");
        exec("window.__nvl_relationships = " + gson.toJson(payload.get("relationships")) + ";");
        exec("window.vgv_setData();");
    }

    public void applyNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        exec("window.vgv_applyNodeConfig(" + gson.toJson(toJsonNodeConfig(config)) + ");");
        if (currentData != null) {
            applyData(currentData);
        }
    }

    /**
     * Push a per-node Leiden cluster color map to the iframe. Pairs with
     * {@code GraphConfigurationDialog}'s "Apply Leiden Clustering" button
     * and {@link SwitchingViewer#setLeidenClusterColors} (which now
     * forwards to NVL when the active engine is {@code NEO4J_NVL}).
     *
     * <p>The JS handler {@code vgv_applyLeidenColors} (in
     * {@code nvl-graph-viewer.js}) iterates the map and emits an
     * {@code nvl.updateElementsInGraph} call with one
     * {@code {id, color}} entry per matched node — symmetric to the
     * {@code vgv_applyLeidenColors} handler in
     * {@code vis-graph-viewer.js}.</p>
     */
    public void setLeidenColors(Map<String, String> colors) {
        exec("window.vgv_applyLeidenColors(" + gson.toJson(colors == null ? Map.of() : colors) + ");");
    }

    /**
     * Push the engine-agnostic "effective per-node color map" produced
     * by {@link NodeColorResolver#resolveEffectiveColors} to the NVL
     * iframe. Used by the unified {@link SwitchingViewer#applyNodeColors}
     * method so the dialog's Tag-Colors and Leiden-Colors buttons apply
     * to NVL too (previously a no-op).
     *
     * <p>The JS handler {@code vgv_applyNodeColors} walks the map and
     * calls {@code nvl.updateElementsInGraph} with one
     * {@code {id, color}} entry per node — identical wire shape to
     * {@link #setLeidenColors} but driven by the resolver instead of
     * the Leiden map alone.</p>
     */
    public void applyNodeColors(Map<String, String> effective) {
        exec("window.vgv_applyNodeColors(" + gson.toJson(effective == null ? Map.of() : effective) + ");");
    }

    public void clear() {
        exec("window.vgv_clear();");
    }

    public void fitToScreen() {
        exec("window.vgv_fitToScreen();");
    }

    public void setLayout(String algorithm) {
        exec("window.vgv_setLayout('" + algorithm + "');");
    }

    public void setPhysics(boolean enabled) {
        exec("window.vgv_setPhysics(" + enabled + ");");
    }

    public void setPhysicsSolver(String solver) {
        // NVL has no exact solver concept; the bridge maps to the closest
        // layout. Logged for visibility — callers should not rely on this
        // being a strict 1:1.
        LOG.fine("setPhysicsSolver: " + solver + " (NVL maps to best-effort layout)");
        exec("window.vgv_setPhysicsSolver('" + solver + "');");
    }

    public void setHierarchicalDirection(String dir) {
        exec("window.vgv_setHierarchicalDirection('" + dir + "');");
    }

    public void setHierarchicalSpacing(int levelSep, int nodeSpacing) {
        // NVL does not expose spacing parameters in its public API.
        exec("window.vgv_setHierarchicalSpacing(" + levelSep + ", " + nodeSpacing + ");");
    }

    public void setStabilizationIterations(int iterations) {
        // No-op equivalent in NVL; the bridge ignores this.
        exec("window.vgv_setStabilizationIterations(" + iterations + ");");
    }

    public void setAutoFitOnStabilization(boolean enabled) {
        // No-op equivalent in NVL.
        exec("window.vgv_setAutoFitOnStabilization(" + enabled + ");");
    }

    public void setOption(String key, Object value) {
        exec("window.vgv_setOption('" + key + "', " + gson.toJson(value) + ");");
    }

    public void pushNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        exec("window.vgv_applyNodeConfig(" + gson.toJson(toJsonNodeConfig(config)) + ");");
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y) {
        showContextMenu(entries, x, y, null);
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y, Object target) {
        lastContextTarget.set(target);
        ContextMenuSnapshot snap = new ContextMenuSnapshot(entries, target);
        this.pendingContextMenu = snap;
        exec("window.vgv_showContextMenu(" + gson.toJson(snap.toJson()) + ", " + x + ", " + y + ");");
    }

    public Object getLastContextTarget() {
        return lastContextTarget.get();
    }

    public void hideContextMenu() {
        exec("window.vgv_hideContextMenu();");
    }

    public void dispose() {
        functions.dispose();
        scriptQueue.dispose();
    }

    /* ---- private ---- */

    private void exec(String script) {
        scriptQueue.exec(script);
    }

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
            LOG.info("NvlJsBridge: vgv_viewerReady received from iframe");
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
