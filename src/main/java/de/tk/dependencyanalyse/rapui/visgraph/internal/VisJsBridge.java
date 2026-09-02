package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendEntry;
import com.google.gson.Gson;
import org.eclipse.swt.browser.Browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages all BrowserFunction handlers that the vis-graph-viewer registers
 * with the embedded {@link Browser}.
 *
 * Each registered function is held by {@link BrowserFunctions} so it can be
 * deregistered in {@link #dispose()}. All scripts are submitted through
 * {@link BrowserScriptQueue} so RAP's "only one script in flight" rule
 * cannot be violated.
 */
public final class VisJsBridge {

    private static final Logger LOG = Logger.getLogger(VisJsBridge.class.getName());

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
    private final java.util.concurrent.atomic.AtomicReference<Object> lastContextTarget =
            new java.util.concurrent.atomic.AtomicReference<>();

    public VisJsBridge(Browser browser) {
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

    /**
     * Push a per-node Leiden-cluster color map to the iframe. vis-network
     * has no stylesheet engine, so each node must receive its own
     * {@code color} update via {@code nodes.update}. The payload format
     * mirrors the Cytoscape side ({@code {id → hex}}) — the JS handler
     * applies it uniformly to every node present in {@code network.body.nodes}.
     */
    public void setLeidenClusterColors(Map<String, String> colors) {
        exec("window.vgv_applyLeidenColors(" + gson.toJson(colors) + ");");
    }

    /**
     * Push a legend payload to the iframe. {@code entries} is rendered as
     * the optional click-to-highlight panel positioned top-right in the
     * vis-network canvas. {@code enabled} controls panel visibility.
     */
    public void applyLegend(List<LegendEntry> entries, boolean enabled) {
        exec("window.vgv_applyLegend("
                + gson.toJson(entries == null ? List.of() : entries)
                + ", " + (enabled ? "true" : "false") + ");");
    }

    /** Remove the legend panel from the iframe. */
    public void clearLegend() {
        exec("window.vgv_applyLegend([], false);");
    }

    public void applyData(GraphData data) {
        this.currentData = data;
        Map<String, Object> payload = data.toVisNetworkData(currentNodeConfig);
        exec("window.__vgv_nodes = " + gson.toJson(payload.get("nodes")) + ";");
        exec("window.__vgv_edges = " + gson.toJson(payload.get("edges")) + ";");
        exec("window.vgv_setData();");
    }

    public void applyNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        if (currentData != null) {
            // Re-render SVG badges with the new colors and push the
            // updated image URIs / color.background updates to the iframe.
            // vis-network has no stylesheet engine like Cytoscape, so the
            // effective color must be applied per-node — see
            // SvgBadgeColorUpdater.applyRecolorsBoth.
            List<Map<String, Object>> recolors =
                    SvgBadgeColorUpdater.applyRecolorsBoth(currentData, config);
            if (!recolors.isEmpty()) {
                LOG.info("VisJsBridge.applyNodeConfig: "
                        + recolors.size() + " nodes re-rendered with new colors");
                exec("window.vgv_applyNodeImages("
                        + gson.toJson(recolors) + ");");
            }
        }
    }

    public void clear() {
        exec("window.vgv_clear();");
    }

    public void fitToScreen() {
        exec("window.vgv_fitToScreen();");
    }

    /**
     * Ask the vis-network iframe to resize itself to the current
     * container size and re-draw. Called from {@link GraphViewer}'s
     * Resize-Listener so the canvas follows the composite's actual
     * size (vis-network does not auto-detect zero-size parents).
     */
    public void resize() {
        exec("if (window.vgv_resize) { window.vgv_resize(); }");
    }

    public void setLayout(String algorithm) {
        exec("window.vgv_setLayout('" + algorithm + "');");
    }

    public void setPhysics(boolean enabled) {
        exec("window.vgv_setPhysics(" + enabled + ");");
    }

    public void setPhysicsSolver(String solver) {
        exec("window.vgv_setPhysicsSolver('" + solver + "');");
    }

    public void setHierarchicalDirection(String dir) {
        exec("window.vgv_setHierarchicalDirection('" + dir + "');");
    }

    public void setHierarchicalSpacing(int levelSep, int nodeSpacing) {
        exec("window.vgv_setHierarchicalSpacing(" + levelSep + ", " + nodeSpacing + ");");
    }

    public void setStabilizationIterations(int iterations) {
        exec("window.vgv_setStabilizationIterations(" + iterations + ");");
    }

    public void setAutoFitOnStabilization(boolean enabled) {
        exec("window.vgv_setAutoFitOnStabilization(" + enabled + ");");
    }

    public void setOption(String key, Object value) {
        exec("window.vgv_setOption('" + key + "', " + gson.toJson(value) + ");");
    }

    /**
     * Push a NodeConfig to the iframe WITHOUT triggering a re-apply. Used by
     * unit tests and the GraphViewer when the current data is not yet set.
     *
     * <p>vis-network's viewer has no stylesheet engine — the config is
     * only used by the Cytoscape bridge. The method is kept for
     * backwards compatibility with callers that explicitly want to store
     * the config without applying it.</p>
     */
    public void pushNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
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

    /**
     * Clean up vis-network-side artefacts (legend highlight, tooltip
     * containers) before the Browser is disposed. Symmetric counterpart
     * to {@link CytoscapeJsBridge#disposeIframe()} — without this hook
     * the orphan {@code #vgv-legend} / {@code #vgv-context-menu} divs
     * would float on top of an empty iframe after an engine switch.
     */
    public void disposeIframe() {
        exec("try { if (window.vgv_dispose) { window.vgv_dispose(); } } catch(e){}");
    }

    /* ---- private ---- */

    private void exec(String script) {
        scriptQueue.exec(script);
    }

    /**
     * Convert a {@link NodeConfig} into a JSON-friendly map shape for the
     * JS bridge. {@code null} yields an empty config object.
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
        // vis-network uses setNodeConfig / redraw via Cytoscape's NodeConfig
        // generator; we forward globalTagColors for parity even though
        // vis-network style updates are handled by Cytoscape-style config
        // from the JS viewer's two bridges.
        Map<String, Object> globals = new LinkedHashMap<>();
        cfg.getGlobalTagColors().forEach((prop, byValue) ->
                globals.put(prop, new LinkedHashMap<>(byValue)));
        out.put("globalTagColors", globals);
        return out;
    }

    private void registerAll() {
        functions.create(FN_VIEWER_READY, args -> {
            java.util.logging.Logger.getLogger(VisJsBridge.class.getName())
                    .info("VisJsBridge: vgv_viewerReady received from iframe");
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
