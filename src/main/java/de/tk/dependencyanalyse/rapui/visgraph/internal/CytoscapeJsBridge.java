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
 * Manages the BrowserFunction handlers that the cytoscape-viewer.js bridge
 * registers with the embedded {@link Browser}.
 *
 * <p>This is the Cytoscape counterpart to {@link VisJsBridge}; the function
 * names use the {@code cgv_} prefix (vs {@code vgv_} for vis) so the two
 * viewers can coexist in the same RAP session if needed. The script queue
 * is shared with the vis-bridge pattern: scripts are serialized through
 * {@link BrowserScriptQueue} to honor RAP's "one script in flight" rule.</p>
 */
public final class CytoscapeJsBridge {

    private static final Logger LOG = Logger.getLogger(CytoscapeJsBridge.class.getName());

    private static final String FN_VIEWER_READY = "cgv_viewerReady";
    private static final String FN_NODE_SELECTED = "cgv_notifyNodeSelected";
    private static final String FN_REL_SELECTED = "cgv_notifyRelationshipSelected";
    private static final String FN_SEL_CLEARED = "cgv_notifySelectionCleared";
    private static final String FN_REQ_NODE_CTX = "cgv_requestNodeContextMenu";
    private static final String FN_REQ_REL_CTX = "cgv_requestRelationshipContextMenu";
    private static final String FN_INVOKE_CTX = "cgv_invokeContextMenuAction";

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

    public CytoscapeJsBridge(Browser browser) {
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
     * Push the elements array to the iframe and call {@code window.cgv_setData()}.
     *
     * <p>Both statements are shipped in ONE atomic {@code evaluate()} call so
     * the JS side never sees {@code cgv_setData()} fire before
     * {@code window.__cgv_elements} has been assigned. Splitting them into two
     * separate {@link BrowserScriptQueue#exec exec()} calls would still
     * serialise them, but the JS bridge's {@code cgv_setData} early-returns
     * when {@code __cgv_elements} is not yet set — that early-return is the
     * safety net for partial / split deliveries, and folding both statements
     * into one script eliminates the window entirely.</p>
     */
    public void applyData(GraphData data) {
        this.currentData = data;
        List<Map<String, Object>> elements = data.toCytoscapeElements(currentNodeConfig);
        LOG.info("CytoscapeJsBridge.applyData: " + elements.size() + " elements");
        exec("window.__cgv_elements = " + gson.toJson(elements) + "; window.cgv_setData();");
    }

    /**
     * Send the NodeConfig to the iframe so the JS side can rebuild the
     * Cytoscape style-selector array. If data is already loaded, the JS
     * bridge will re-apply the style without re-creating elements.
     *
     * <p>When {@code config} carries {@code labelColors} or
     * {@code globalTagColors}, this method ALSO walks the current graph,
     * re-renders every {@code svgImage}-marked node whose effective
     * background color changed, and ships the updated {@code data.image}
     * URIs to the iframe via {@link SvgBadgeColorUpdater}. Without that
     * step, SVG-badge nodes keep their original color baked into the URI
     * even though the Cytoscape stylesheet background-color was
     * overwritten — the {@code Apply Tag Colors} / {@code Apply NodeType
     * Colors} buttons would not visibly recolor the badges.</p>
     */
    public void applyNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        exec("window.cgv_applyNodeConfig(" + gson.toJson(toJsonNodeConfig(config)) + ");");
        List<SvgBadgeColorUpdater.ImageUpdate> recolors =
                SvgBadgeColorUpdater.applyRecolors(currentData, config);
        if (!recolors.isEmpty()) {
            LOG.info("CytoscapeJsBridge.applyNodeConfig: "
                    + recolors.size() + " SVG badges re-rendered with new colors");
            exec("window.cgv_applyNodeImages("
                    + gson.toJson(SvgBadgeColorUpdater.toJsonUpdates(recolors)) + ");");
        }
    }

    /**
     * Apply a layout algorithm. The JS bridge translates the algorithm name
     * to a Cytoscape layout configuration.
     */
    public void setLayout(String algorithm) {
        exec("window.cgv_setLayout('" + algorithm + "');");
    }

    /**
     * Pass an arbitrary Cytoscape-layout-options object. Used for fcose
     * options like {@code idealEdgeLength} computed from log(weight).
     */
    public void setLayoutOptions(Map<String, Object> options) {
        exec("window.cgv_setLayoutOptions(" + gson.toJson(options) + ");");
    }

    /**
     * Apply Leiden cluster colors. {@code colors} maps node-id → hex color.
     * The JS side uses a class selector (e.g. {@code node.leiden_3}) and
     * updates the style accordingly.
     */
    public void setLeidenColors(Map<String, String> colors) {
        exec("window.cgv_applyLeidenColors(" + gson.toJson(colors) + ");");
    }

    /**
     * Push a legend payload to the iframe. {@code entries} is rendered as
     * the optional click-to-highlight panel positioned top-right in the
     * Cytoscape canvas. {@code enabled} controls panel visibility — when
     * {@code false} the panel hides but the entries are kept so toggling
     * the checkbox in the dialog restores the panel instantly.
     */
    public void applyLegend(List<LegendEntry> entries, boolean enabled) {
        exec("window.cgv_applyLegend("
                + gson.toJson(entries == null ? List.of() : entries)
                + ", " + (enabled ? "true" : "false") + ");");
    }

    /**
     * Ask the cytoscape iframe to resize itself to the current container
     * size and re-fit. Called from the {@link GraphViewerControlBar}'s
     * Resize-Listener so the canvas follows the composite's actual size
     * (vis-network and cytoscape do not auto-detect zero-size parents).
     */
    public void resize() {
        exec("if (window.cgv_resize) { window.cgv_resize(); }");
    }

    /**
     * Clean up Cytoscape-side artefacts (floating tooltip element, named
     * listeners) before the Browser is disposed. Without this the
     * tooltip DOM persists in the iframe's {@code document.body} even
     * after a switchTo(VIS_NETWORK) — vis-network then shows the
     * orphan tooltip on top of an empty canvas, which looks like the
     * graph is gone but only the tooltip survived.
     */
    public void disposeIframe() {
        exec("try { if (window.cgv_dispose) { window.cgv_dispose(); } } catch(e){}");
    }

    /** Remove the legend panel from the iframe. */
    public void clearLegend() {
        exec("window.cgv_applyLegend([], false);");
    }

    public void clear() {
        exec("window.cgv_clear();");
    }

    public void fitToScreen() {
        exec("window.cgv_fitToScreen();");
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y) {
        showContextMenu(entries, x, y, null);
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y, Object target) {
        lastContextTarget.set(target);
        ContextMenuSnapshot snap = new ContextMenuSnapshot(entries, target);
        this.pendingContextMenu = snap;
        exec("window.cgv_showContextMenu(" + gson.toJson(snap.toJson()) + ", " + x + ", " + y + ");");
    }

    public Object getLastContextTarget() {
        return lastContextTarget.get();
    }

    public void hideContextMenu() {
        exec("window.cgv_hideContextMenu();");
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
     * Convert a {@link NodeConfig} into a JSON-friendly map shape for the
     * JS bridge. The bridge turns {@code labelColors}, {@code tagColors},
     * and {@code showTitle} into Cytoscape style selectors.
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

        // globalTagColors: {propertyName: {value: color}, …}. The JS bridge
        // emits one Cytoscape style selector per (property, value) tuple,
        // matching `node[property = "value"]`. This is what the
        // GraphConfigurationDialog "Apply Tag Colors" button pushes so that
        // a global tag like 'product' colors every node whose product matches,
        // regardless of its primary node type.
        Map<String, Object> globals = new LinkedHashMap<>();
        cfg.getGlobalTagColors().forEach((prop, byValue) ->
                globals.put(prop, new LinkedHashMap<>(byValue)));
        out.put("globalTagColors", globals);
        return out;
    }

    private void registerAll() {
        functions.create(FN_VIEWER_READY, args -> {
            LOG.info("CytoscapeJsBridge: cgv_viewerReady received from iframe");
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
