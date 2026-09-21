package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuProvider;
import de.tk.dependencyanalyse.rapui.visgraph.callback.NodeSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.RelationshipSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.SelectionClearedListener;
import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.HierarchicalDirection;
import de.tk.dependencyanalyse.rapui.visgraph.data.LayoutAlgorithm;
import de.tk.dependencyanalyse.rapui.visgraph.data.PhysicsSolver;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;
import de.tk.dependencyanalyse.rapui.visgraph.internal.NvlJsBridge;
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
 * RAP-BrowserWidget that hosts an {@code @neo4j-nvl/base}-rendered graph.
 *
 * <p>Public-API twin of {@link GraphViewer} — same method names, same
 * semantics — but the underlying bridge is {@link NvlJsBridge} and the
 * HTML payload is the NVL viewer ({@code /static/nvl/nvl-viewer.html}).
 * Layout, physics, and hierarchical-direction setters map to NVL's
 * option names; some vis-network-specific knobs (solver, stabilization
 * iterations) are best-effort no-ops at the bridge level.</p>
 *
 * <p>Both viewers can be constructed in the same application — they
 * register distinct {@link org.eclipse.swt.browser.BrowserFunction}s
 * (because RAP scopes those to the {@link Browser} instance) and the
 * HTML documents are separate. {@link #getEngine()} tells callers which
 * renderer is in use.</p>
 */
public class Neo4jNvlViewer extends Browser {

    private static final Logger LOG = Logger.getLogger(Neo4jNvlViewer.class.getName());
    private static final String DEFAULT_HTML_RESOURCE = "/static/nvl/nvl-viewer.html";

    private final NvlJsBridge bridge;
    private GraphData currentData;

    private volatile LayoutAlgorithm currentLayout = LayoutAlgorithm.FORCE_ATLAS_2D;
    private volatile boolean physicsEnabled = true;
    private volatile PhysicsSolver physicsSolver = PhysicsSolver.FORCE_ATLAS_2_BASED;
    private volatile HierarchicalDirection hierarchicalDirection = HierarchicalDirection.UP_DOWN;
    private volatile int hierarchicalLevelSeparation = 150;
    private volatile int hierarchicalNodeSpacing = 100;
    private volatile int stabilizationIterations = 1000;
    private volatile boolean autoFitOnStabilization = true;

    private final List<NodeSelectionListener> nodeSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<RelationshipSelectionListener> relSelectionListeners = new CopyOnWriteArrayList<>();
    private final List<SelectionClearedListener> clearedListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> pendingOps = new ConcurrentLinkedQueue<>();

    private final String htmlOrResourcePath;

    public Neo4jNvlViewer(Composite parent, int style) {
        this(parent, style, DEFAULT_HTML_RESOURCE);
    }

    public Neo4jNvlViewer(Composite parent, int style, String htmlOrResourcePath) {
        super(parent, style);
        this.htmlOrResourcePath = htmlOrResourcePath;
        this.bridge = new NvlJsBridge(this);
        wireBridgeListeners();

        // Apply HTML eagerly. The nvl-graph-viewer.js bridge uses a
        // ResizeObserver (with setInterval polling fallback) to defer
        // NVL initialization until the embedded container has a non-zero
        // size.
        String html = loadClasspathResource(htmlOrResourcePath);
        if (html == null) {
            html = htmlOrResourcePath;
        }
        setText(html);
    }

    private static String loadClasspathResource(String path) {
        if (path == null || path.isEmpty()) return null;
        try (InputStream in = Neo4jNvlViewer.class.getResourceAsStream(path)) {
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
        runWhenReady(() -> {
            bridge.setCurrentData(data);
            bridge.applyData(data);
            // Auto-push the per-node color palette that travels with the
            // graph. applyGraphPalette(...) drives refreshPalette() which
            // derives panel entries via LegendBuilder.fromGraphPalette
            // (preserving the caller's keys as labels) and pushes
            // vgv_applyColorPalette(entries, true) — so the Color Palette
            // panel appears automatically for every NVL graph that
            // declares a non-empty palette, without renaming labels to
            // "ClusterN" like the Leiden path does.
            Map<String, String> palette = data.getColorPalette();
            if (palette != null && !palette.isEmpty()) {
                bridge.applyGraphPalette(palette);
            }
        });
    }

    public GraphData getGraphData() { return currentData; }

    /**
     * Returns the rendering engine of this viewer. Always
     * {@link GraphEngine#NEO4J_NVL} for instances of this class; the
     * vis-network counterpart is {@link GraphViewer}.
     */
    public GraphEngine getEngine() {
        return GraphEngine.NEO4J_NVL;
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
     * Push a per-node Leiden cluster color map to the iframe. NVL has
     * no stylesheet engine, so each node receives a per-node
     * {@code {id, color}} update via {@code nvl.updateElementsInGraph}.
     *
     * <p>Pairs with {@link SwitchingViewer#setLeidenClusterColors}
     * which forwards to this method when the active engine is
     * {@code NEO4J_NVL}. The button
     * {@code GraphConfigurationDialog.applyLeidenClustering} was a
     * silent no-op under NVL before this method existed.</p>
     */
    public void setLeidenClusterColors(Map<String, String> colors) {
        if (colors == null) return;
        runWhenReady(() -> bridge.setLeidenColors(colors));
    }

    /**
     * Push a graph-attached color palette (from {@link GraphData#getColorPalette})
     * to the iframe without going through the Leiden cluster resolver.
     *
     * <p>Pairs with {@link SwitchingViewer#setGraphData} which forwards
     * the palette on every {@code setGraphData(...)} call and on engine
     * round-trips into NVL. The map keys are preserved verbatim as
     * legend row labels (e.g. node id, cluster key, ...) so the panel
     * matches the caller's intent.</p>
     */
    public void applyGraphPalette(Map<String, String> palette) {
        if (palette == null) return;
        runWhenReady(() -> bridge.applyGraphPalette(palette));
    }

    /**
     * Apply the engine-agnostic per-node effective color map produced
     * by {@link de.tk.dependencyanalyse.rapui.visgraph.internal.NodeColorResolver}.
     *
     * <p>Used by the unified
     * {@link SwitchingViewer#applyNodeColors} so the dialog's Tag-Colors
     * and Leiden-Colors buttons apply identically across Cytoscape,
     * vis-network, sigma and NVL.</p>
     */
    public void applyNodeColors(Map<String, String> effective) {
        if (effective == null) return;
        runWhenReady(() -> bridge.applyNodeColors(effective));
    }

    /**
     * Push per-node {@code overlayIcon} updates to the iframe. Each
     * entry must carry the native NVL {@code overlayIcon} shape
     * ({@code {url, position?, size?}}); the JS bridge forwards the
     * payload to {@code nvl.updateElementsInGraph} so the icons layer
     * on top of the existing NVL node circles.
     *
     * <p>The initial overlay payload is shipped as part of the regular
     * {@link #setGraphData(GraphData)} path (see
     * {@link GraphNode#toNvlNode()}), so this method is only needed for
     * in-place updates after the graph is on screen — e.g. swapping the
     * icon set at runtime.</p>
     */
    public void applyNodeImages(List<Map<String, Object>> updates) {
        if (updates == null) return;
        runWhenReady(() -> bridge.applyNodeImages(updates));
    }

    public void clear() {
        runWhenReady(bridge::clear);
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
            bridge.addContextHandler(new NvlJsBridge.ContextHandler() {
                public List<de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry> forNode(
                        de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode n) { return List.of(); }
            });
            return;
        }
        bridge.addContextHandler(new NvlJsBridge.ContextHandler() {
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

    /* ---- layout / physics ---- */

    public void setLayout(LayoutAlgorithm algorithm) {
        if (algorithm == null) return;
        this.currentLayout = algorithm;
        runWhenReady(() -> bridge.setLayout(algorithm.name()));
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

    public void setOption(String key, Object value) {
        runWhenReady(() -> bridge.setOption(key, value));
    }

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
            try { op.run(); } catch (Exception e) {
                LOG.log(Level.WARNING, "operation failed", e);
            }
        } else {
            pendingOps.add(() -> {
                if (getDisplay() != null) {
                    getDisplay().asyncExec(() -> {
                        try { op.run(); } catch (Exception ex) {
                            LOG.log(Level.WARNING, "queued op failed", ex);
                        }
                    });
                }
            });
            bridge.onReady(this::drainPendingOps);
        }
    }

    private void drainPendingOps() {
        Runnable r;
        while ((r = pendingOps.poll()) != null) {
            try { r.run(); } catch (Exception e) {
                LOG.log(Level.WARNING, "drain op failed", e);
            }
        }
    }
}
