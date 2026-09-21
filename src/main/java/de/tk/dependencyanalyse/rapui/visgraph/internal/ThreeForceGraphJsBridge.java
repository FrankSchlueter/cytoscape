package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendBuilder;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendEntry;
import com.google.gson.Gson;
import org.eclipse.swt.browser.Browser;

import java.util.ArrayList;
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
 * 3D Force-Directed Graph bridge ({@code three-force-viewer.js}).
 *
 * <p>Mirror image of {@link NvlJsBridge} — same surface area, but speaks
 * Three.js + 3d-force-graph on the wire instead of NVL. The two bridges
 * share {@link BrowserScriptQueue} and {@link BrowserFunctions} so script
 * serialization and BrowserFunction lifecycle code is not duplicated.</p>
 *
 * <p>The {@code tfgv_*} prefix on every BrowserFunction name avoids
 * collisions with NVL/vis/Cytoscape/sigma if a user accidentally wires two
 * viewers to the same Browser (RAP scopes BrowserFunctions per-instance
 * anyway, but a distinct prefix makes the source readable).</p>
 */
public final class ThreeForceGraphJsBridge {

    private static final Logger LOG = Logger.getLogger(ThreeForceGraphJsBridge.class.getName());

    private static final String FN_VIEWER_READY = "tfgv_viewerReady";
    private static final String FN_NODE_SELECTED = "tfgv_notifyNodeSelected";
    private static final String FN_LINK_SELECTED = "tfgv_notifyLinkSelected";
    private static final String FN_SEL_CLEARED = "tfgv_notifySelectionCleared";
    private static final String FN_REQ_NODE_CTX = "tfgv_requestNodeContextMenu";
    private static final String FN_REQ_LINK_CTX = "tfgv_requestLinkContextMenu";
    private static final String FN_INVOKE_CTX = "tfgv_invokeContextMenuAction";

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

    /** Last Leiden cluster color map pushed via {@link #setLeidenColors}. */
    private volatile Map<String, String> currentLeidenColors = Map.of();
    /** Last effective color map pushed via {@link #applyNodeColors}. */
    private volatile Map<String, String> currentEffectiveColors = Map.of();
    /** Last graph-attached palette pushed via {@link #applyGraphPalette}. */
    private volatile Map<String, String> currentGraphPalette = Map.of();
    /** True when the palette panel should be visible. */
    private volatile boolean paletteVisible = false;

    public ThreeForceGraphJsBridge(Browser browser) {
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
        LOG.info("ThreeForceGraphJsBridge.applyData: nodes=" + data.getNodes().size() + ", edges=" + data.getRelationships().size());
        this.currentData = data;
        Map<String, Object> payload = data.toThreeForceGraphData();
        exec("window.__tfg_nodes = " + gson.toJson(payload.get("nodes")) + ";");
        exec("window.__tfg_links = " + gson.toJson(payload.get("links")) + ";");
        exec("window.tfgv_setData();");
        refreshPalette();
    }

    public void applyNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        exec("window.tfgv_applyNodeConfig(" + gson.toJson(toJsonNodeConfig(config)) + ");");
        if (currentData != null) {
            applyData(currentData);
        }
    }

    /**
     * Re-derive the color-palette entries from the cached color maps and
     * push the resulting panel state to the iframe. Same semantics as the
     * NVL counterpart: hidden when all three maps are empty; otherwise the
     * entries come from the highest-signal LegendBuilder source
     * ({@code combined} → {@code fromGraphPalette} → {@code fromLeidenClusters}).
     */
    private void refreshPalette() {
        Map<String, String> effective = currentEffectiveColors;
        Map<String, String> graph = currentGraphPalette;
        Map<String, String> leiden = currentLeidenColors;
        boolean anyColors = !effective.isEmpty() || !graph.isEmpty() || !leiden.isEmpty();
        if (!anyColors) {
            if (paletteVisible) {
                paletteVisible = false;
                exec("window.tfgv_hideColorPalette();");
            }
            return;
        }
        List<LegendEntry> entries = derivePaletteEntries(effective, graph, leiden);
        paletteVisible = true;
        // Reproject LegendEntry {colorHex, label, count} to the JS-side
        // schema {hex, label, count} expected by three-force-viewer.js.
        // Without this reprojection the panel reads `e.hex` which is
        // undefined for every entry and falls back to '#cccccc' — so all
        // swatches render as grey regardless of the actual cluster
        // colors. (Java Record field names are part of the public API
        // and must stay stable; engine-specific wire schemas are
        // projected at the bridge boundary.)
        List<Map<String, Object>> jsEntries = new ArrayList<>(entries.size());
        for (LegendEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>(3);
            m.put("hex", e.colorHex());
            m.put("label", e.label());
            m.put("count", e.count());
            jsEntries.add(m);
        }
        exec("window.tfgv_applyColorPalette(" + gson.toJson(jsEntries) + ", true);");
    }

    private List<LegendEntry> derivePaletteEntries(Map<String, String> effective,
                                                     Map<String, String> graph,
                                                     Map<String, String> leiden) {
        if (!effective.isEmpty()) {
            return LegendBuilder.combined(currentData, currentNodeConfig, leiden);
        }
        if (!graph.isEmpty()) {
            return LegendBuilder.fromGraphPalette(currentData, graph);
        }
        if (!leiden.isEmpty()) {
            return LegendBuilder.fromLeidenClusters(currentData, leiden);
        }
        return List.of();
    }

    public void setLeidenColors(Map<String, String> colors) {
        Map<String, String> effective = colors == null ? Map.of() : colors;
        this.currentLeidenColors = effective;
        exec("window.tfgv_applyLeidenColors(" + gson.toJson(effective) + ");");
        refreshPalette();
    }

    public void applyNodeColors(Map<String, String> effective) {
        Map<String, String> safe = effective == null ? Map.of() : effective;
        this.currentEffectiveColors = safe;
        exec("window.tfgv_applyNodeColors(" + gson.toJson(safe) + ");");
        refreshPalette();
    }

    public void applyGraphPalette(Map<String, String> palette) {
        Map<String, String> safe = palette == null ? Map.of() : palette;
        this.currentGraphPalette = safe;
        refreshPalette();
    }

    public void clear() {
        this.currentLeidenColors = Map.of();
        this.currentEffectiveColors = Map.of();
        this.currentGraphPalette = Map.of();
        this.paletteVisible = false;
        exec("window.tfgv_clear();");
        exec("window.tfgv_hideColorPalette();");
    }

    public void applyNodeImages(List<Map<String, Object>> updates) {
        if (updates == null || updates.isEmpty()) return;
        // 3d-force-graph has no overlayIcon concept; we still ship the
        // payload so JS can no-op gracefully (matches tfgv_applyNodeImages
        // handler in three-force-viewer.js).
        exec("window.tfgv_applyNodeImages(" + gson.toJson(updates) + ");");
    }

    public void fitToScreen() {
        exec("window.tfgv_fitToScreen();");
    }

    public void setLayout(String algorithm) {
        exec("window.tfgv_setLayout('" + algorithm + "');");
    }

    public void setPhysics(boolean enabled) {
        exec("window.tfgv_setPhysics(" + enabled + ");");
    }

    public void setPhysicsSolver(String solver) {
        // 3d-force-graph has a single force solver (d3-force-3d); logged
        // for visibility only.
        LOG.fine("setPhysicsSolver: " + solver + " (3D maps to best-effort solver)");
        exec("window.tfgv_setPhysicsSolver('" + solver + "');");
    }

    public void setHierarchicalDirection(String dir) {
        // No 3D equivalent.
        LOG.fine("setHierarchicalDirection: " + dir + " (3D has no hierarchical layout)");
        exec("window.tfgv_setHierarchicalDirection('" + dir + "');");
    }

    public void setHierarchicalSpacing(int levelSep, int nodeSpacing) {
        exec("window.tfgv_setHierarchicalSpacing(" + levelSep + ", " + nodeSpacing + ");");
    }

    public void setStabilizationIterations(int iterations) {
        exec("window.tfgv_setStabilizationIterations(" + iterations + ");");
    }

    public void setAutoFitOnStabilization(boolean enabled) {
        exec("window.tfgv_setAutoFitOnStabilization(" + enabled + ");");
    }

    public void setOption(String key, Object value) {
        exec("window.tfgv_setOption('" + key + "', " + gson.toJson(value) + ");");
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y) {
        showContextMenu(entries, x, y, null);
    }

    public void showContextMenu(List<ContextMenuEntry> entries, int x, int y, Object target) {
        lastContextTarget.set(target);
        ContextMenuSnapshot snap = new ContextMenuSnapshot(entries, target);
        this.pendingContextMenu = snap;
        exec("window.tfgv_showContextMenu(" + gson.toJson(snap.toJson()) + ", " + x + ", " + y + ");");
    }

    public Object getLastContextTarget() {
        return lastContextTarget.get();
    }

    public void hideContextMenu() {
        exec("window.tfgv_hideContextMenu();");
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
            LOG.info("ThreeForceGraphJsBridge: tfgv_viewerReady received from iframe");
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
        functions.create(FN_LINK_SELECTED, args -> {
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
        functions.create(FN_REQ_LINK_CTX, args -> {
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
