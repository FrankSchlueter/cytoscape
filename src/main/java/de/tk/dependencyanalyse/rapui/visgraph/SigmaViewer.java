package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuProvider;
import de.tk.dependencyanalyse.rapui.visgraph.callback.NodeSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.RelationshipSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.SelectionClearedListener;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.LayoutAlgorithm;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;
import de.tk.dependencyanalyse.rapui.visgraph.internal.SigmaJsBridge;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RAP-BrowserWidget that hosts a sigma.js + graphology graph. Loads the
 * sigma-viewer HTML+JS via {@code Browser.setText} (inline) and exposes
 * Java APIs for data, selection, context menu, and layout configuration.
 *
 * <p>Data delivery differs from the vis-network and Cytoscape counterparts:
 * the graphology payload is fetched by the iframe from
 * {@code /api/sigma/nodes} and {@code /api/sigma/edges} rather than pushed
 * via {@code BrowserFunction}. This keeps the iframe-side memory profile
 * predictable for large graphs and lets the response stream through the
 * standard Servlet stack with GZIP compression.</p>
 *
 * <p>The initial fetch URL is inlined into the iframe HTML before
 * {@code Browser.setText} so the iframe can start fetching as soon as it
 * boots — eliminating the race between the iframe's IIFE and the
 * Java-side {@code applyData(...)} call. If no graph is available at
 * construction time the inlined URLs are empty strings and the iframe
 * waits for the next {@code applyData(...)} call.</p>
 */
public class SigmaViewer extends Browser {

    private static final Logger LOG = Logger.getLogger(SigmaViewer.class.getName());
    private static final String DEFAULT_HTML_RESOURCE = "/static/sigma-viewer.html";
    private static final String PLACEHOLDER_NODES_URL = "__VG_INITIAL_NODES_URL__";
    private static final String PLACEHOLDER_EDGES_URL = "__VG_INITIAL_EDGES_URL__";

    private final SigmaJsBridge bridge;
    private GraphData currentData;

    private volatile LayoutAlgorithm currentLayout = LayoutAlgorithm.FORCE_DIRECTED_2_SIGMA;
    private volatile Map<String, Object> currentLayoutOptions = Map.of();

    /** Last Leiden color map pushed via {@link #setLeidenClusterColors}. */
    private volatile Map<String, String> currentLeidenColors = Map.of();

    private final List<NodeSelectionListener> nodeSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<RelationshipSelectionListener> relSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<SelectionClearedListener> clearedListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> pendingOps = new ConcurrentLinkedQueue<>();

    private final String htmlOrResourcePath;

    public SigmaViewer(Composite parent, int style) {
        this(parent, style, DEFAULT_HTML_RESOURCE, null);
    }

    public SigmaViewer(Composite parent, int style, String htmlOrResourcePath) {
        this(parent, style, htmlOrResourcePath, null);
    }

    /**
     * Construct a SigmaViewer. {@code initialData}, when non-null, is pushed
     * to the bridge BEFORE the iframe HTML is rendered so the
     * {@code __VG_INITIAL_NODES_URL__} / {@code __VG_INITIAL_EDGES_URL__}
     * placeholders can be inlined with concrete fetch addresses. If null,
     * the placeholders stay empty and the iframe waits for a later
     * {@link #setGraphData} call.
     */
    public SigmaViewer(Composite parent, int style, String htmlOrResourcePath, GraphData initialData) {
        super(parent, style);
        this.htmlOrResourcePath = htmlOrResourcePath == null ? DEFAULT_HTML_RESOURCE : htmlOrResourcePath;
        this.bridge = new SigmaJsBridge(this);
        wireBridgeListeners();

        // Push the initial data to the bridge first so the fetch URLs are
        // available for HTML inlining.
        if (initialData != null && !initialData.getNodes().isEmpty()) {
            bridge.applyData(initialData);
        }

        String html = loadClasspathResource(this.htmlOrResourcePath);
        if (html == null) {
            html = this.htmlOrResourcePath;
        }
        // Inline the initial fetch URLs into the HTML template.
        String[] urls = bridge.computeInitialUrls();
        html = html.replace(PLACEHOLDER_NODES_URL,
                urls != null && urls.length > 0 && urls[0] != null ? urls[0] : "");
        html = html.replace(PLACEHOLDER_EDGES_URL,
                urls != null && urls.length > 1 && urls[1] != null ? urls[1] : "");

        setText(html);
        // Force the parent composite to recompute its FillLayout now that
        // the Browser child has been added. Without this the iframe can
        // render at 0x0 in the first frame and the sigma boot script
        // (which waits for a non-zero container size via ResizeObserver)
        // would never fire vg_viewerReady, stranding all queued
        // setGraphData / setLayout calls.
        if (parent != null) {
            parent.layout(true, true);
        }
        // Resize-Listener: push the new dimensions into the iframe so
        // sigma can re-fit. SWT's default Resize event fires whenever the
        // FillLayout recomputes — including the explicit layout() calls
        // in SwitchingViewer.switchTo() and tab-switch / window-resize
        // events. Ignore 0x0 sizes (see CytoscapeViewer for the same
        // rationale).
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
        try (InputStream in = SigmaViewer.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not load HTML resource: " + path, e);
            return null;
        }
    }

    /* ---- data ---- */

    public void setGraphData(GraphData data) {
        LOG.info("SigmaViewer.setGraphData: nodes=" + (data == null ? "null" : data.getNodes().size())
                + ", edges=" + (data == null ? "null" : data.getRelationships().size())
                + ", bridgeReady=" + bridge.isViewerReady());
        if (data == null) {
            clear();
            return;
        }
        this.currentData = data;
        runWhenReady(() -> {
            bridge.setCurrentData(data);
            bridge.applyData(data);
        });
    }

    public GraphData getGraphData() { return currentData; }

    public GraphEngine getEngine() {
        return GraphEngine.SIGMA;
    }

    public void setNodeConfig(NodeConfig config) {
        NodeConfig effective = config == null ? NodeConfig.defaults() : config;
        runWhenReady(() -> bridge.applyNodeConfig(effective));
    }

    public NodeConfig getNodeConfig() {
        NodeConfig cfg = bridge.getCurrentNodeConfig();
        return cfg == null ? NodeConfig.defaults() : cfg;
    }

    /**
     * Apply the engine-agnostic per-node effective color map produced
     * by {@link de.tk.dependencyanalyse.rapui.visgraph.internal.NodeColorResolver}.
     * Pairs with {@link SwitchingViewer#applyNodeColors} so the dialog's
     * Tag-Colors and Leiden-Colors buttons apply uniformly across all
     * engines.
     */
    public void applyNodeColors(Map<String, String> effective) {
        if (effective == null) return;
        runWhenReady(() -> bridge.applyNodeColors(effective));
    }

    public void clear() {
        runWhenReady(bridge::clear);
    }

    public void fitToScreen() {
        runWhenReady(bridge::fitToScreen);
    }

    /**
     * Push a per-node Leiden-cluster color map. Sigma renders the per-node
     * color via the {@code nodeReducer} (see {@code sigma-viewer.js}).
     * The Color Palette panel is auto-managed by the bridge: a non-empty
     * map makes it appear, an empty map hides it.
     */
    public void setLeidenClusterColors(Map<String, String> colors) {
        if (colors == null) return;
        this.currentLeidenColors = Map.copyOf(colors);
        runWhenReady(() -> bridge.setLeidenColors(colors));
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
            bridge.addContextHandler(new SigmaJsBridge.ContextHandler() {
                public List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forNode(
                        de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) { return List.of(); }
            });
            return;
        }
        bridge.addContextHandler(new SigmaJsBridge.ContextHandler() {
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
        if (algorithm == null || !algorithm.isSupportedBySigma()) return;
        LOG.info("SigmaViewer.setLayout: " + algorithm.name());
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
            // orphan #vg-tooltip element would survive in the iframe's
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
                r.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "drain op failed", e);
            }
        }
    }
}
