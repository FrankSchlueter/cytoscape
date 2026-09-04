package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuProvider;
import de.tk.dependencyanalyse.rapui.visgraph.callback.NodeSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.RelationshipSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.SelectionClearedListener;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.LayoutAlgorithm;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendEntry;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;
import de.tk.dependencyanalyse.rapui.visgraph.internal.CytoscapeJsBridge;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RAP-BrowserWidget that hosts a Cytoscape.js + cytoscape-fcose graph. The
 * Cytoscape counterpart to {@link GraphViewer}.
 *
 * <p>The HTML and JS are embedded via {@link Browser#setText(String)} (same
 * pattern as the vis-network viewer). The JS bridge
 * ({@code /static/cytoscape-viewer.html} + {@code cytoscape-viewer.js})
 * initializes Cytoscape only after the embedded container has a non-zero
 * size — see {@link de.tk.dependencyanalyse.rapui.visgraph.internal.BrowserScriptQueue}
 * for the corresponding Java-side deferred-init mechanism.</p>
 */
public class CytoscapeViewer extends Browser {

    private static final Logger LOG = Logger.getLogger(CytoscapeViewer.class.getName());
    private static final String DEFAULT_HTML_RESOURCE = "/static/cytoscape-viewer.html";

    private final CytoscapeJsBridge bridge;
    private GraphData currentData;

    private volatile LayoutAlgorithm currentLayout = LayoutAlgorithm.FCOSE;
    private volatile Map<String, Object> currentLayoutOptions = Map.of();

    /** Last Leiden color map pushed via {@link #setLeidenClusterColors}; cached for community-view rebuilds. */
    private volatile Map<String, String> currentLeidenColors = Map.of();
    /** True when the iframe is currently showing an aggregated community view (root or detail). */
    private volatile boolean communityViewActive = false;

    private final List<NodeSelectionListener> nodeSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<RelationshipSelectionListener> relSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<SelectionClearedListener> clearedListeners = new CopyOnWriteArrayList<>();
    private final List<CommunityDrillListener> communityDrillListeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingOps =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final String htmlOrResourcePath;

    public CytoscapeViewer(Composite parent, int style) {
        this(parent, style, DEFAULT_HTML_RESOURCE);
    }

    public CytoscapeViewer(Composite parent, int style, String htmlOrResourcePath) {
        super(parent, style);
        this.htmlOrResourcePath = htmlOrResourcePath;
        this.bridge = new CytoscapeJsBridge(this);
        wireBridgeListeners();

        String html = loadClasspathResource(htmlOrResourcePath);
        if (html == null) {
            html = htmlOrResourcePath;
        }
        setText(html);
        // Force the parent composite to recompute its FillLayout now that
        // the Browser child has been added. Without this the iframe can
        // render at 0×0 in the first frame and the cytoscape boot script
        // (which waits for a non-zero container size via ResizeObserver)
        // would never fire cgv_viewerReady, stranding all queued
        // setGraphData / setLayout / setLeidenClusterColors calls.
        if (parent != null) {
            parent.layout(true, true);
        }
        // Resize-Listener: whenever the parent composite's size changes,
        // push the new dimensions into the iframe so cytoscape can
        // re-fit. SWT's default Resize event fires whenever the
        // FillLayout recomputes — including the explicit layout() calls
        // in SwitchingViewer.switchTo() and the tab-switch / window-
        // resize events.
        //
        // Critically: ignore 0×0 sizes. During a FillLayout flush (e.g.
        // the moment the old Browser widget is disposed before the
        // fresh one is added in SwitchingViewer.switchTo()), the
        // composite briefly reports size 0×0. Forwarding that to
        // cytoscape via window.cgv_resize would call cy.resize() +
        // cy.fit() against a zero viewport, which paints nodes at the
        // origin (0,0) — making the entire graph appear blank. The
        // next Resize event after FillLayout settles carries the real
        // size, and the iframe then paints correctly.
        addListener(SWT.Resize, event -> {
            if (isDisposed() || bridge == null) return;
            int w = getSize().x;
            int h = getSize().y;
            if (w <= 0 || h <= 0) return;
            bridge.resize();
        });
    }

    private static String loadClasspathResource(String path) {
        if (path == null || path.isEmpty()) return null;
        try (InputStream in = CytoscapeViewer.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not load HTML resource: " + path, e);
            return null;
        }
    }

    /* ---- data ---- */

    public void setGraphData(GraphData data) {
        LOG.info("CytoscapeViewer.setGraphData: nodes=" + (data == null ? "null" : data.getNodes().size())
                + ", edges=" + (data == null ? "null" : data.getRelationships().size())
                + ", bridgeReady=" + bridge.isViewerReady());
        if (data == null) {
            clear();
            return;
        }
        this.currentData = data;
        runWhenReady(() -> {
            LOG.info("CytoscapeViewer: applying data (now ready)");
            bridge.setCurrentData(data);
            bridge.applyData(data);
        });
    }

    public GraphData getGraphData() { return currentData; }

    public GraphEngine getEngine() {
        return GraphEngine.CYTOSCAPE;
    }

    public void setNodeConfig(NodeConfig config) {
        NodeConfig effective = config == null ? NodeConfig.defaults() : config;
        runWhenReady(() -> bridge.applyNodeConfig(effective));
    }

    public NodeConfig getNodeConfig() {
        NodeConfig cfg = bridge.getCurrentNodeConfig();
        return cfg == null ? NodeConfig.defaults() : cfg;
    }

    public void clear() {
        runWhenReady(bridge::clear);
    }

    public void fitToScreen() {
        runWhenReady(bridge::fitToScreen);
    }

    /**
     * Apply Leiden cluster colors computed in the browser (web-worker).
     * {@code colors} maps node id → hex color; the JS bridge adds a class
     * selector so Cytoscape renders the new colors immediately.
     */
    public void setLeidenClusterColors(Map<String, String> colors) {
        if (colors == null) return;
        // Cache the colors so the community-aggregation view can be
        // re-applied after a clustering re-run without needing the
        // dialog to pass them through again.
        this.currentLeidenColors = Map.copyOf(colors);
        runWhenReady(() -> bridge.setLeidenColors(colors));
    }

    /**
     * Push the optional color legend to the iframe. {@code enabled} controls
     * the panel's visibility. {@link SwitchingViewer#setLegend} routes this
     * call to whichever engine is currently active.
     */
    public void setLegend(List<LegendEntry> entries, boolean enabled) {
        runWhenReady(() -> bridge.applyLegend(entries, enabled));
    }

    /** Remove the legend panel entirely (entries cleared + panel hidden). */
    public void clearLegend() {
        runWhenReady(bridge::clearLegend);
    }

    /* ---- community aggregation view ---- */

    /**
     * Push the optional community-aggregation view to the iframe.
     *
     * <p>{@code enabled = true} switches the canvas to the aggregated root
     * view: one Cytoscape node per Leiden community, one aggregated edge
     * per inter-community pair (bidirectional A->B and B->A are merged
     * into a single edge whose weight is the sum of the individual
     * weights). The elements array is built by
     * {@link CommunityAggregator#buildRootElements(GraphData, Map)}.</p>
     *
     * <p>{@code enabled = false} clears the aggregation view and pushes
     * the original (non-aggregated) graph back into the canvas, so the
     * user sees the full per-node view again.</p>
     *
     * <p>The Leiden colors are cached on the viewer so the dialog can
     * re-apply the aggregation after a clustering re-run without having
     * to pass them every time.</p>
     *
     * <p>Backwards-compatible overload — defaults the dynamic-size flag
     * to {@code false}.</p>
     */
    public void setCommunityView(boolean enabled, Map<String, String> colors) {
        setCommunityView(enabled, colors, false);
    }

    /**
     * Same as {@link #setCommunityView(boolean, Map)} but with an
     * explicit flag controlling whether the cytoscape-side community-node
     * size scales logarithmically with the sum of incoming edge weights.
     *
     * @param dynamicSize when {@code true}, the cytoscape bridge uses
     *                    the per-node logarithmic size; when {@code false}
     *                    (default), every community-node renders at the
     *                    same fixed size for a uniform cluster layout.
     */
    public void setCommunityView(boolean enabled, Map<String, String> colors, boolean dynamicSize) {
        if (colors != null) this.currentLeidenColors = Map.copyOf(colors);
        if (!enabled) {
            runWhenReady(() -> {
                bridge.clearCommunityView();
                // Re-apply the original data so the canvas has nodes again.
                if (currentData != null) bridge.applyData(currentData);
            });
            return;
        }
        if (currentData == null || currentLeidenColors.isEmpty()) {
            LOG.warning("CytoscapeViewer.setCommunityView(true): no data or no Leiden colors, nothing to aggregate");
            return;
        }
        List<Map<String, Object>> rootElements =
                CommunityAggregator.buildRootElements(currentData, currentLeidenColors);
        runWhenReady(() -> bridge.applyCommunityView("root", rootElements, dynamicSize));
    }

    /**
     * Drill into a single community (detail view). Switches the canvas
     * to show only that community's member nodes + intra-edges. Called
     * from the {@link CytoscapeJsBridge} when the user double-clicks a
     * community-node in the root view.
     */
    public void drillIntoCommunity(String communityColor) {
        if (communityColor == null || communityColor.isEmpty()) return;
        if (currentData == null || currentLeidenColors.isEmpty()) {
            LOG.warning("CytoscapeViewer.drillIntoCommunity: no data or no Leiden colors");
            return;
        }
        List<Map<String, Object>> detailElements = CommunityAggregator.buildCommunityDetailElements(
                currentData, currentLeidenColors, communityColor);
        if (detailElements.isEmpty()) {
            LOG.warning("CytoscapeViewer.drillIntoCommunity: no members for color=" + communityColor);
            return;
        }
        // Stamp every member-node with the community colour so the JS
        // side can label the navigation overlay.
        for (Map<String, Object> elem : detailElements) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) elem.get("data");
            if (d != null && !d.containsKey("source")) {
                d.put("_communityColor", communityColor);
            }
        }
        communityViewActive = true;
        runWhenReady(() -> bridge.applyCommunityView("detail", detailElements));
    }

    /**
     * Drill back out of the detail view to the aggregated root view.
     * Called from the bridge when the user clicks the "Back to Communities"
     * button.
     */
    public void drillOut() {
        if (currentData == null || currentLeidenColors.isEmpty()) {
            runWhenReady(bridge::clearCommunityView);
            return;
        }
        List<Map<String, Object>> rootElements =
                CommunityAggregator.buildRootElements(currentData, currentLeidenColors);
        runWhenReady(() -> bridge.applyCommunityView("root", rootElements));
    }

    /**
     * Whether the community-aggregation view is currently active. Returns
     * {@code true} when the canvas is in 'root' or 'detail' mode (either
     * one). The dialog uses this to keep the "Show kumulated Communities"
     * checkbox in sync with the actual viewer state.
     */
    public boolean isCommunityViewActive() {
        return communityViewActive;
    }

    /**
     * Set the cached community-view active flag. Called by the dialog
     * after {@link #setCommunityView} so subsequent dialog interactions
     * (e.g. showing a "View is aggregated" hint) can read the flag
     * without round-tripping to the iframe.
     */
    public void setCommunityViewActive(boolean active) {
        this.communityViewActive = active;
    }

    /* ---- selection ---- */

    public void addNodeSelectionListener(NodeSelectionListener l) {
        nodeSelectionListeners.add(l);
    }

    public void addRelationshipSelectionListener(RelationshipSelectionListener l) {
        relSelectionListeners.add(l);
    }

    public void addSelectionClearedListener(SelectionClearedListener l) {
        clearedListeners.add(l);
    }

    /**
     * Register a listener notified when the user drills into a community
     * (double-clicks an aggregated community-node) or drills back out
     * (clicks the "Back to Communities" button).
     */
    public void addCommunityDrillListener(CommunityDrillListener l) {
        communityDrillListeners.add(l);
    }

    /** Interface for community drill-down/out callbacks. Both methods have default no-op implementations so callers can override only the one they care about. */
    public interface CommunityDrillListener {
        /** Called with the community's hex colour when the user drills in. */
        default void drilledDown(String communityColor) {}
        /** Called when the user clicks the "Back to Communities" button. */
        default void drilledOut() {}
    }

    /* ---- context menu ---- */

    public void setContextMenuProvider(ContextMenuProvider provider) {
        if (provider == null) {
            bridge.addContextHandler(new CytoscapeJsBridge.ContextHandler() {
                public List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forNode(
                        de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) { return List.of(); }
            });
            return;
        }
        bridge.addContextHandler(new CytoscapeJsBridge.ContextHandler() {
            public List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forNode(
                    de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) {
                return provider.forNode(n);
            }
            public List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forRelationship(
                    de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship r) {
                return provider.forRelationship(r);
            }
        });
    }

    /* ---- layout ---- */

    public void setLayout(LayoutAlgorithm algorithm) {
        if (algorithm == null || !algorithm.isSupportedByCytoscape()) return;
        LOG.info("CytoscapeViewer.setLayout: " + algorithm.name());
        this.currentLayout = algorithm;
        runWhenReady(() -> {
            LOG.info("CytoscapeViewer: applying layout " + algorithm.name());
            bridge.setLayout(algorithm.name());
            if (!currentLayoutOptions.isEmpty()) {
                bridge.setLayoutOptions(currentLayoutOptions);
            }
        });
    }

    /**
     * Returns true if the algorithm requires the cytoscape-viewer.js to
     * pre-seed positions from a Leiden color map before running the
     * Cytoscape layout. Only {@link LayoutAlgorithm#LEIDEN_GRID} does;
     * every other algorithm operates on whatever node positions are
     * already in the graph.
     */
    public static boolean isLeidenPreseed(LayoutAlgorithm algorithm) {
        return algorithm == LayoutAlgorithm.LEIDEN_GRID;
    }

    public LayoutAlgorithm currentLayout() {
        return currentLayout;
    }

    /**
     * Cytoscape-specific layout options (e.g. fcose {@code idealEdgeLength}).
     * Persisted on the viewer so subsequent {@link #setLayout} calls re-apply
     * the same options for the new layout.
     */
    public void setLayoutOptions(Map<String, Object> options) {
        this.currentLayoutOptions = options == null ? Map.of() : Map.copyOf(options);
        runWhenReady(() -> bridge.setLayoutOptions(currentLayoutOptions));
    }

    @Override
    public void dispose() {
        pendingOps.clear();
        nodeSelectionListeners.clear();
        relSelectionListeners.clear();
        clearedListeners.clear();
        try {
            // Drop the iframe-side tooltip + listeners BEFORE the
            // BrowserFunction shim is torn down — after bridge.dispose()
            // any further exec() call would silently no-op and the
            // orphan #cgv-tooltip element would survive in the iframe's
            // document.body until the next engine switch.
            bridge.disposeIframe();
            bridge.dispose();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "bridge dispose failed", e);
        }
        super.dispose();
    }

    /* ---- internals ---- */

    private void wireBridgeListeners() {
        bridge.addNodeListener(n -> getDisplay().asyncExec(() -> {
            for (NodeSelectionListener l : nodeSelectionListeners) {
                try { l.nodeSelected(n); } catch (Exception e) {
                    LOG.log(Level.WARNING, "node listener threw", e);
                }
            }
        }));
        bridge.addRelationshipListener(r -> getDisplay().asyncExec(() -> {
            for (RelationshipSelectionListener l : relSelectionListeners) {
                try { l.relationshipSelected(r); } catch (Exception e) {
                    LOG.log(Level.WARNING, "rel listener threw", e);
                }
            }
        }));
        bridge.addSelectionClearedListener(() -> getDisplay().asyncExec(() -> {
            for (SelectionClearedListener l : clearedListeners) {
                try { l.selectionCleared(); } catch (Exception e) {
                    LOG.log(Level.WARNING, "cleared listener threw", e);
                }
            }
        }));
        bridge.addContextActionHandler((entry, target) -> getDisplay().asyncExec(() -> {
            if (target instanceof de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) {
                entry.dispatchNode(n);
            } else if (target instanceof de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship r) {
                entry.dispatchRelationship(r);
            }
        }));
        // Community drill-down: triggered by dblclick on a community-node
        // in the aggregated root view. The viewer self-handles the
        // drill (pushes the detail elements into the iframe) and then
        // re-broadcasts the colour to all registered listeners (the
        // dialog and the SwitchingViewer).
        bridge.addCommunityDrillDownListener(color -> getDisplay().asyncExec(() -> {
            try { drillIntoCommunity(color); } catch (Exception e) {
                LOG.log(Level.WARNING, "drillIntoCommunity threw", e);
            }
            for (CommunityDrillListener l : communityDrillListeners) {
                try { l.drilledDown(color); } catch (Exception e) {
                    LOG.log(Level.WARNING, "communityDrill listener threw", e);
                }
            }
        }));
        bridge.addCommunityDrillOutListener(() -> getDisplay().asyncExec(() -> {
            try { drillOut(); } catch (Exception e) {
                LOG.log(Level.WARNING, "drillOut threw", e);
            }
            for (CommunityDrillListener l : communityDrillListeners) {
                try { l.drilledOut(); } catch (Exception e) {
                    LOG.log(Level.WARNING, "communityDrill listener threw", e);
                }
            }
        }));
    }

    private void runWhenReady(Runnable op) {
        if (bridge.isViewerReady()) {
            try { op.run(); } catch (Exception e) {
                LOG.log(Level.WARNING, "operation failed", e);
            }
        } else {
            pendingOps.add(op);
            bridge.onReady(this::drainPendingOps);
        }
    }

    private void drainPendingOps() {
        Runnable r;
        while ((r = pendingOps.poll()) != null) {
            try {
                // Run synchronously on the UI thread (we are already on it via
                // the BrowserFunction callback); no nested asyncExec required.
                r.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "drain op failed", e);
            }
        }
    }
}
