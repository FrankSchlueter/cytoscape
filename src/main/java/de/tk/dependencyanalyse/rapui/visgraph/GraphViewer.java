package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuProvider;
import de.tk.dependencyanalyse.rapui.visgraph.callback.NodeSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.RelationshipSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.SelectionClearedListener;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.HierarchicalDirection;
import de.tk.dependencyanalyse.rapui.visgraph.data.LayoutAlgorithm;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendEntry;
import de.tk.dependencyanalyse.rapui.visgraph.data.PhysicsSolver;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;

import java.util.List;
import java.util.Map;
import de.tk.dependencyanalyse.rapui.visgraph.internal.VisJsBridge;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RAP-BrowserWidget that hosts a vis-network graph. Loads the vis-network
 * HTML+JS via {@code Browser.setText} (inline) and exposes Java APIs for
 * data, selection, context menu, and layout configuration.
 *
 * <p><b>Why setText and not setUrl:</b> embedding the HTML directly via
 * {@code setText} avoids iframe-related issues with RAP 4.x. The vis-network
 * runs in the main page's JS context, and {@code window.vgv_*} functions
 * are reachable via {@link #execute(String)} directly.</p>
 *
 * <p><b>Deferred HTML load:</b> {@code setText} is invoked the first time the
 * widget is resized (i.e. after layout has computed a non-zero size). Calling
 * setText too early gives the embedded HTML a 0x0 container, which vis-network
 * silently renders to nothing.</p>
 */
public class GraphViewer extends Browser {

    private static final Logger LOG = Logger.getLogger(GraphViewer.class.getName());
    private static final String DEFAULT_HTML_RESOURCE = "/static/vis-graph/viewer.html";

    private final VisJsBridge bridge;
    private GraphData currentData;

    private volatile LayoutAlgorithm currentLayout = LayoutAlgorithm.FORCE_ATLAS_2D;
    private volatile boolean physicsEnabled = true;
    private volatile PhysicsSolver physicsSolver = PhysicsSolver.FORCE_ATLAS_2_BASED;
    private volatile HierarchicalDirection hierarchicalDirection = HierarchicalDirection.UP_DOWN;
    private volatile int hierarchicalLevelSeparation = 150;
    private volatile int hierarchicalNodeSpacing = 100;
    private volatile int stabilizationIterations = 1000;
    private volatile boolean autoFitOnStabilization = true;
    /**
     * Most recent vis-network layout-option map pushed via
     * {@link #setLayoutOptions}. Re-applied on every subsequent
     * {@link #setLayout} call so users keep their tuned FA2 parameters
     * when they switch algorithms and back. {@code Map.of()} ⇒ no
     * options have been pushed yet.
     */
    private volatile Map<String, Object> currentLayoutOptions = Map.of();

    private final java.util.List<NodeSelectionListener> nodeSelectionListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<RelationshipSelectionListener> relSelectionListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<SelectionClearedListener> clearedListeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingOps = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final String htmlOrResourcePath;

    public GraphViewer(Composite parent, int style) {
        this(parent, style, DEFAULT_HTML_RESOURCE);
    }

    public GraphViewer(Composite parent, int style, String htmlOrResourcePath) {
        super(parent, checkStyle(style));
        this.htmlOrResourcePath = htmlOrResourcePath;
        this.bridge = new VisJsBridge(this);
        wireBridgeListeners();

        // Apply HTML eagerly. The vis-graph-viewer.js bridge uses a
        // ResizeObserver (with setInterval polling fallback) to defer
        // vis-network initialization until the embedded container has
        // a non-zero size.
        String html = loadClasspathResource(htmlOrResourcePath);
        if (html == null) {
            html = htmlOrResourcePath;
        }
        setText(html);
        // Force the parent composite to recompute its FillLayout now that
        // the Browser child has been added. Without this the iframe can
        // render at 0×0 in the first frame and the vis-network boot
        // script (which waits for a non-zero container size via
        // ResizeObserver) would never fire vgv_viewerReady, stranding
        // all queued setGraphData / setLayout calls.
        if (parent != null) {
            parent.layout(true, true);
        }
        // Resize-Listener: whenever the parent composite's size changes,
        // push the new dimensions into the iframe so vis-network can
        // re-render. SWT's default Resize event fires whenever the
        // FillLayout recomputes — including the explicit layout() calls
        // in SwitchingViewer.switchTo() and the tab-switch / window-
        // resize events.
        //
        // Critically: ignore 0×0 sizes. During a FillLayout flush (e.g.
        // the moment the old Browser widget is disposed before the
        // fresh one is added in SwitchingViewer.switchTo()), the
        // composite briefly reports size 0×0. Forwarding that to
        // vis-network via window.vgv_resize would call network.setSize(
        // 0, 0), which renders an invisible canvas — and the user
        // sees "switched to Vis, no graph appears". The next Resize
        // event after FillLayout settles carries the real size, and
        // the iframe then paints correctly.
        addListener(SWT.Resize, event -> {
            if (isDisposed() || bridge == null) return;
            int w = getSize().x;
            int h = getSize().y;
            if (w <= 0 || h <= 0) return;
            bridge.resize();
        });
    }

    private static int checkStyle(int style) {
        return style;
    }

    private static String loadClasspathResource(String path) {
        if (path == null || path.isEmpty()) return null;
        try (InputStream in = GraphViewer.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not load HTML resource: " + path, e);
            return null;
        }
    }

    /* ---- data ---- */

    public void setGraphData(GraphData data) {
        if (data == null) {
            clear();
            return;
        }
        this.currentData = data;
        java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                .info("GraphViewer.setGraphData called, bridge ready=" + bridge.isViewerReady());
        runWhenReady(() -> {
            java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                    .info("GraphViewer: applying data (now ready)");
            bridge.setCurrentData(data);
            bridge.applyData(data);
        });
    }

    public GraphData getGraphData() { return currentData; }

    /**
     * Returns the rendering engine of this viewer. Always
     * {@link GraphEngine#VIS_NETWORK} for instances of this class; the
     * NVL counterpart is {@link de.tk.dependencyanalyse.rapui.visgraph.Neo4jNvlViewer}.
     */
    public GraphEngine getEngine() {
        return GraphEngine.VIS_NETWORK;
    }

    /**
     * Applies the given {@link NodeConfig}: caches it on the bridge and,
     * if {@link #getGraphData()} is already set, re-applies the data so
     * the change is visible immediately.
     *
     * <p>Pass {@code null} to restore {@link NodeConfig#defaults()}.</p>
     */
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

    /**
     * Push a per-node Leiden-cluster color map to the iframe. Pairs with
     * {@link GraphConfigurationDialog}'s "Apply Leiden Clustering" button
     * — {@link SwitchingViewer} routes the call to whichever engine is
     * currently active. The JS handler iterates the map and emits a
     * {@code nodes.update} per node so vis-network's per-node color
     * (rather than a stylesheet selector) carries the recolor.
     */
    public void setLeidenClusterColors(Map<String, String> colors) {
        if (colors == null) return;
        runWhenReady(() -> bridge.setLeidenClusterColors(colors));
    }

    /**
     * Push the optional color legend to the iframe. {@code enabled} controls
     * the panel's visibility — when {@code false} the panel hides but the
     * entries are kept so toggling the dialog's checkbox restores it.
     * {@link SwitchingViewer#setLegend} routes this call to whichever
     * engine is currently active.
     */
    public void setLegend(List<LegendEntry> entries, boolean enabled) {
        runWhenReady(() -> bridge.applyLegend(entries, enabled));
    }

    /** Remove the legend panel entirely (entries cleared + panel hidden). */
    public void clearLegend() {
        runWhenReady(bridge::clearLegend);
    }

    public void fitToScreen() {
        runWhenReady(bridge::fitToScreen);
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

    /* ---- context menu ---- */

    public void setContextMenuProvider(ContextMenuProvider provider) {
        if (provider == null) {
            bridge.addContextHandler(new VisJsBridge.ContextHandler() {
                public java.util.List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forNode(
                        de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) { return java.util.List.of(); }
            });
            return;
        }
        bridge.addContextHandler(new VisJsBridge.ContextHandler() {
            public java.util.List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forNode(
                    de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) {
                return provider.forNode(n);
            }
            public java.util.List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forRelationship(
                    de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship r) {
                return provider.forRelationship(r);
            }
        });
    }

    /* ---- layout / physics ---- */

    public void setLayout(LayoutAlgorithm algorithm) {
        if (algorithm == null) return;
        this.currentLayout = algorithm;
        runWhenReady(() -> {
            bridge.setLayout(algorithm.name());
            if (!currentLayoutOptions.isEmpty()) {
                bridge.setLayoutOptions(currentLayoutOptions);
            }
        });
    }

    public LayoutAlgorithm currentLayout() {
        return currentLayout;
    }

    public void setPhysics(boolean enabled) {
        this.physicsEnabled = enabled;
        runWhenReady(() -> bridge.setPhysics(enabled));
    }

    public boolean isPhysicsEnabled() { return physicsEnabled; }

    public void setPhysicsSolver(PhysicsSolver solver) {
        if (solver == null) return;
        this.physicsSolver = solver;
        runWhenReady(() -> bridge.setPhysicsSolver(solver.name()));
    }

    public PhysicsSolver currentPhysicsSolver() { return physicsSolver; }

    public void setHierarchicalDirection(HierarchicalDirection dir) {
        if (dir == null) return;
        this.hierarchicalDirection = dir;
        runWhenReady(() -> bridge.setHierarchicalDirection(dir.name()));
    }

    public HierarchicalDirection currentHierarchicalDirection() { return hierarchicalDirection; }

    public void setHierarchicalLevelSeparation(int pixels) {
        this.hierarchicalLevelSeparation = pixels;
        runWhenReady(() -> bridge.setHierarchicalSpacing(pixels, hierarchicalNodeSpacing));
    }

    public void setHierarchicalNodeSpacing(int pixels) {
        this.hierarchicalNodeSpacing = pixels;
        runWhenReady(() -> bridge.setHierarchicalSpacing(hierarchicalLevelSeparation, pixels));
    }

    public void setStabilizationIterations(int iterations) {
        this.stabilizationIterations = iterations;
        runWhenReady(() -> bridge.setStabilizationIterations(iterations));
    }

    public void setAutoFitOnStabilization(boolean enabled) {
        this.autoFitOnStabilization = enabled;
        runWhenReady(() -> bridge.setAutoFitOnStabilization(enabled));
    }

    public boolean isAutoFitOnStabilization() { return autoFitOnStabilization; }

    public void setOption(String key, Object value) {
        runWhenReady(() -> bridge.setOption(key, value));
    }

    /**
     * vis-network counterpart of
     * {@link CytoscapeViewer#setLayoutOptions(java.util.Map)}. Persists the
     * option map on the viewer so a subsequent {@link #setLayout} call
     * re-applies the same tuning. Pairs with
     * {@link de.tk.dependencyanalyse.rapui.visgraph.ForceAtlasOptions} for
     * the vis-network Cluster-Layout-Strategie.
     */
    public void setLayoutOptions(Map<String, Object> options) {
        this.currentLayoutOptions = options == null ? Map.of() : Map.copyOf(options);
        runWhenReady(() -> bridge.setLayoutOptions(currentLayoutOptions));
    }

    /** @return the most recent layout-option map (immutable copy). */
    public Map<String, Object> currentLayoutOptions() {
        return currentLayoutOptions;
    }

    /**
     * Returns the current value of a vis-network option, or {@code null} if no
     * roundtrip introspection is in place. Callers SHALL treat {@code null} as
     * "unknown / not introspectable".
     */
    public Object getOption(String key) {
        return null;
    }

    /* ---- lifecycle ---- */

    @Override
    public void dispose() {
        pendingOps.clear();
        nodeSelectionListeners.clear();
        relSelectionListeners.clear();
        clearedListeners.clear();
        try {
            // Drop the iframe-side legend + tooltip artefacts BEFORE the
            // BrowserFunction shim is torn down — after bridge.dispose()
            // any further exec() call would silently no-op and the
            // orphan DOM elements would survive in the iframe's
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
        bridge.addContextActionHandler((entry, target) -> {
            // The bridge now passes the actual right-clicked target (GraphNode or
            // GraphRelationship). Dispatch synchronously on the UI thread.
            getDisplay().asyncExec(() -> {
                if (target instanceof de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) {
                    entry.dispatchNode(n);
                } else if (target instanceof de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship r) {
                    entry.dispatchRelationship(r);
                }
            });
        });
    }

    private void runWhenReady(Runnable op) {
        if (bridge.isViewerReady()) {
            java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                    .info("GraphViewer.runWhenReady: bridge ready, running op immediately");
            try { op.run(); } catch (Exception e) {
                LOG.log(Level.WARNING, "operation failed", e);
            }
        } else {
            java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                    .info("GraphViewer.runWhenReady: bridge not ready, queueing op");
            pendingOps.add(() -> {
                if (getDisplay() != null) {
                    java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                            .info("GraphViewer: pending op running, scheduling asyncExec");
                    getDisplay().asyncExec(() -> {
                        java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                                .info("GraphViewer: pending op running on UI thread");
                        try { op.run(); } catch (Exception ex) {
                            LOG.log(Level.WARNING, "queued op failed", ex);
                        }
                    });
                } else {
                    java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                            .warning("GraphViewer: pending op dropped, display is null");
                }
            });
            bridge.onReady(this::drainPendingOps);
        }
    }

    private void drainPendingOps() {
        java.util.logging.Logger.getLogger(GraphViewer.class.getName())
                .info("GraphViewer.drainPendingOps called, queue size=" + pendingOps.size());
        Runnable r;
        while ((r = pendingOps.poll()) != null) {
            try { r.run(); } catch (Exception e) {
                LOG.log(Level.WARNING, "drain op failed", e);
            }
        }
    }
}
