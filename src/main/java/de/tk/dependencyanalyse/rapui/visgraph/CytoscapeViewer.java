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

    private final List<NodeSelectionListener> nodeSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<RelationshipSelectionListener> relSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<SelectionClearedListener> clearedListeners = new CopyOnWriteArrayList<>();
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
