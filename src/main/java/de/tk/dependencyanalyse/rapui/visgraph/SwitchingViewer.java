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
import de.tk.dependencyanalyse.rapui.visgraph.internal.NodeColorResolver;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Composite that hosts a graph viewer (vis-network or Cytoscape.js) and
 * exposes a small, engine-agnostic API to consumers.
 *
 * <p>Internally delegates to a {@link GraphViewer} (vis) or
 * {@link CytoscapeViewer}. The two viewers share the same API surface
 * ({@link #setGraphData(GraphData)}, {@link #setNodeConfig(NodeConfig)},
 * {@link #setLayout(LayoutAlgorithm)}, {@link #fitToScreen()}, selection
 * listeners) so this wrapper hides the swap entirely.</p>
 *
 * <p>Engine switching is destructive: the old viewer is disposed and a
 * fresh one of the requested engine is created with the current data and
 * node-config restored.</p>
 */
public class SwitchingViewer extends Composite {

    private static final Logger LOG = Logger.getLogger(SwitchingViewer.class.getName());

    private final Listener disposeListener = e -> disposeViewer();

    private GraphData currentData;
    private NodeConfig currentNodeConfig;
    private LayoutAlgorithm currentLayout = LayoutAlgorithm.BARNES_HUT;
    private Map<String, Object> currentLayoutOptions = Map.of();
    private ContextMenuProvider currentContextMenuProvider;
    private GraphEngine currentEngine = GraphEngine.NEO4J_NVL;

    /** Last Leiden cluster colors pushed via {@link #setLeidenClusterColors}. */
    private Map<String, String> currentLeidenColors = Map.of();
    /** True when the community-aggregation view is currently active on the active engine. Survives engine switches. */
    private boolean communityViewActive = false;
    /** Legend state. {@code null} means no legend has ever been set. */
    private List<LegendEntry> currentLegend = List.of();
    private boolean legendEnabled = false;

    private GraphViewer visViewer;
    private CytoscapeViewer cytoscapeViewer;
    private SigmaViewer sigmaViewer;
    private Neo4jNvlViewer nvlViewer;

    private final java.util.List<NodeSelectionListener> nodeListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<RelationshipSelectionListener> relListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<SelectionClearedListener> clearedListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<EngineListener> engineListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<CytoscapeViewer.CommunityDrillListener> communityDrillListeners =
            new CopyOnWriteArrayList<>();

    public SwitchingViewer(Composite parent, int style) {
        super(parent, style);
        addListener(SWT.Dispose, disposeListener);
        setLayout(new org.eclipse.swt.layout.FillLayout());
        // Create the initial viewer (vis-network by default).
        //cytoscapeViewer = new CytoscapeViewer(this, SWT.NONE);
        nvlViewer = new Neo4jNvlViewer(this, SWT.NONE);
        wireViewer(nvlViewer);
    }

    public GraphEngine getEngine() {
        return currentEngine;
    }

    /**
     * Switch to the requested engine. Disposes the existing viewer and
     * creates a fresh one with the current data, node-config, layout, and
     * context-menu provider restored.
     *
     * <p>The optional legend payload (if {@link #setLegend} has been called
     * previously) is re-applied to the new engine so the panel survives an
     * engine switch without user intervention. Sigma / Cytoscape / NVL are
     * excluded — their Color Palette is auto-managed from the per-node
     * color maps and re-derives itself when the new engine boots up.</p>
     */
    public void switchTo(GraphEngine engine) {
        if (engine == null || engine == currentEngine) return;
        currentEngine = engine;
        disposeViewer();
        // Force the composite's FillLayout to recompute BEFORE we add a
        // new Browser child — without this the fresh Browser widget is
        // created inside a container whose layout hasn't been updated yet,
        // so the underlying iframe renders at 0×0. The vis-network and
        // cytoscape viewers both wait for a non-zero container size
        // before booting (ResizeObserver / setInterval in their JS
        // bridges), which means they'd never reach vgv_viewerReady /
        // cgv_viewerReady — and the queued setGraphData/setLayout calls
        // would never run. A double layout() around the dispose/create
        // sequence is enough to make the swap deterministic.
        layout(true, true);
        if (engine == GraphEngine.CYTOSCAPE) {
            cytoscapeViewer = new CytoscapeViewer(this, SWT.NONE);
            wireViewer(cytoscapeViewer);
            if (currentData != null) cytoscapeViewer.setGraphData(currentData);
            if (currentNodeConfig != null) cytoscapeViewer.setNodeConfig(currentNodeConfig);
            cytoscapeViewer.setLayout(currentLayout);
            if (!currentLayoutOptions.isEmpty()) {
                cytoscapeViewer.setLayoutOptions(currentLayoutOptions);
            }
            if (!currentLeidenColors.isEmpty()) {
                cytoscapeViewer.setLeidenClusterColors(currentLeidenColors);
            }
        } else if (engine == GraphEngine.SIGMA) {
            // Pass currentData to the SigmaViewer constructor so the
            // initial fetch URLs can be inlined into the iframe HTML —
            // eliminates the race between the iframe boot and the
            // Java-side applyData(...). The bridge queues any follow-up
            // applyData() calls via execWhenReady so the iframe can
            // refetch on later data changes.
            sigmaViewer = new SigmaViewer(this, SWT.NONE, null, currentData);
            wireViewer(sigmaViewer);
            if (currentNodeConfig != null) sigmaViewer.setNodeConfig(currentNodeConfig);
            if (currentLayout.isSupportedBySigma()) {
                sigmaViewer.setLayout(currentLayout);
            } else {
                // Previous engine's layout isn't supported by sigma —
                // fall back to the Sigma-friendly default.
                sigmaViewer.setLayout(LayoutAlgorithm.FORCE_DIRECTED_2_SIGMA);
                this.currentLayout = LayoutAlgorithm.FORCE_DIRECTED_2_SIGMA;
            }
            if (!currentLayoutOptions.isEmpty()) {
                sigmaViewer.setLayoutOptions(currentLayoutOptions);
            }
            if (!currentLeidenColors.isEmpty()) {
                sigmaViewer.setLeidenClusterColors(currentLeidenColors);
            }
        } else if (engine == GraphEngine.NEO4J_NVL) {
            nvlViewer = new Neo4jNvlViewer(this, SWT.NONE);
            wireViewer(nvlViewer);
            if (currentData != null) nvlViewer.setGraphData(currentData);
            if (currentNodeConfig != null) nvlViewer.setNodeConfig(currentNodeConfig);
            if (currentLayout.isSupportedByNvl()) {
                nvlViewer.setLayout(currentLayout);
            } else {
                // Previous engine's layout isn't supported by NVL —
                // fall back to the NVL-friendly default.
                nvlViewer.setLayout(LayoutAlgorithm.BARNES_HUT);
                this.currentLayout = LayoutAlgorithm.BARNES_HUT;
            }
            // Re-apply the Leiden color map on the fresh NVL viewer
            // (previously commented out — caused "Apply Leiden
            // Clustering" to silently disappear on every engine
            // switch into NVL).
            if (!currentLeidenColors.isEmpty()) {
                nvlViewer.setLeidenClusterColors(currentLeidenColors);
            }
            // Push the unified per-node color map so the freshly-
            // created viewer reflects the same colors the previous
            // engine showed.
            nvlViewer.applyNodeColors(resolveEffective(currentNodeConfig, currentLeidenColors));
        } else {
            visViewer = new GraphViewer(this, SWT.NONE);
            wireViewer(visViewer);
            if (currentData != null) visViewer.setGraphData(currentData);
            if (currentNodeConfig != null) visViewer.setNodeConfig(currentNodeConfig);
            visViewer.setLayout(currentLayout);
            if (!currentLayoutOptions.isEmpty()) {
                visViewer.setLayoutOptions(currentLayoutOptions);
            }
            if (!currentLeidenColors.isEmpty()) {
                visViewer.setLeidenClusterColors(currentLeidenColors);
            }
        }
        if (currentContextMenuProvider != null) {
            setContextMenuProvider(currentContextMenuProvider);
        }
        // Re-apply the legend AFTER everything else so the panel sits on top
        // of the freshly-applied data and styles. Sigma and Cytoscape are
        // excluded from this path: their Color Palette is auto-managed by
        // the bridge from the per-node color maps (applyNodeColors /
        // setLeidenClusterColors) and does not respond to manual
        // setLegend() pushes. NVL never had a legend API to begin with.
        if (legendEnabled && visViewer != null
                && currentEngine != GraphEngine.SIGMA
                && currentEngine != GraphEngine.CYTOSCAPE) {
            visViewer.setLegend(currentLegend, true);
        }
        // Re-apply the community-aggregation view (Cytoscape only) so a
        // vis -> cytoscape round-trip doesn't surprise the user with a
        // blank canvas.
        if (communityViewActive && engine == GraphEngine.CYTOSCAPE
                && cytoscapeViewer != null && !currentLeidenColors.isEmpty()) {
            cytoscapeViewer.setCommunityView(true, currentLeidenColors);
        }
        // Second layout pass — gives the freshly-added Browser widget a
        // positive size now that the FillLayout knows its sibling count.
        // The viewer constructors themselves trigger a parent.layout()
        // as a belt-and-braces measure (see GraphViewer / CytoscapeViewer).
        layout(true, true);
        for (EngineListener l : engineListeners) {
            try { l.engineChanged(currentEngine); } catch (Exception ignored) { }
        }
    }

    public void addEngineListener(EngineListener l) {
        engineListeners.add(l);
    }

    /* ---- engine-agnostic API ---- */

    public void setGraphData(GraphData data) {
        this.currentData = data;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setGraphData(data);
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            sigmaViewer.setGraphData(data);
        } else if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            nvlViewer.setGraphData(data);
        } else if (visViewer != null) {
            visViewer.setGraphData(data);
        }
    }

    public GraphData getGraphData() { return currentData; }
    public void setNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setNodeConfig(config);
            cytoscapeViewer.applyNodeColors(resolveEffective(config, currentLeidenColors));
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            sigmaViewer.setNodeConfig(config);
            sigmaViewer.applyNodeColors(resolveEffective(config, currentLeidenColors));
        } else if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            nvlViewer.setNodeConfig(config);
            nvlViewer.applyNodeColors(resolveEffective(config, currentLeidenColors));
        } else if (visViewer != null) {
            visViewer.setNodeConfig(config);
            visViewer.applyNodeColors(resolveEffective(config, currentLeidenColors));
        }
    }

    /**
     * Compute the effective per-node color map for the current graph
     * under the supplied {@code config} + {@code leiden} and forward it
     * to the active engine, applying via that engine's
     * {@code applyNodeColors(Map)} implementation.
     *
     * <p>Each engine applies the map in its own way:</p>
     * <ul>
     *   <li>Cytoscape — extends its existing stylesheet with one
     *       {@code node[id = "X"]} selector per update (plus
     *       image-swap for SVG-badge nodes).</li>
     *   <li>vis-network — calls {@code nodes.update} with one
     *       {@code {id, color: {background, border, ...}}} entry per
     *       update.</li>
     *   <li>sigma — stores the map and rebuilds the nodeReducer so
     *       it takes precedence over {@code currentNodeConfig} /
     *       {@code currentLeidenColors}.</li>
     *   <li>NVL — calls {@code nvl.updateElementsInGraph} with one
     *       {@code {id, color}} entry per update.</li>
     * </ul>
     *
     * <p>This is the single entry point the
     * {@link GraphConfigurationDialog} uses after both
     * {@code applyLeidenClustering()} and {@code applyTagColors()} so
     * all four engines see the same colors.</p>
     */
    public void applyNodeColors(NodeConfig config, Map<String, String> leidenColors) {
        Map<String, String> effective = resolveEffective(config, leidenColors);
        if (effective.isEmpty()) return;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.applyNodeColors(effective);
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            sigmaViewer.applyNodeColors(effective);
        } else if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            nvlViewer.applyNodeColors(effective);
        } else if (visViewer != null) {
            visViewer.applyNodeColors(effective);
        }
    }

    /**
     * Helper: compute the effective per-node color map for the current
     * graph, falling back to the cached {@code currentNodeConfig} /
     * {@code currentLeidenColors} when the caller passes {@code null}.
     */
    private Map<String, String> resolveEffective(NodeConfig config, Map<String, String> leidenColors) {
        NodeConfig cfg = config != null ? config : currentNodeConfig;
        Map<String, String> leiden = leidenColors != null ? leidenColors : currentLeidenColors;
        return NodeColorResolver.resolveEffectiveColors(currentData, cfg, leiden);
    }

    public NodeConfig getNodeConfig() {
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            return cytoscapeViewer.getNodeConfig();
        }
        if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            return sigmaViewer.getNodeConfig();
        }
        if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            return nvlViewer.getNodeConfig();
        }
        if (visViewer != null) {
            return visViewer.getNodeConfig();
        }
        return currentNodeConfig == null ? NodeConfig.defaults() : currentNodeConfig;
    }

    public void setLayout(LayoutAlgorithm algorithm) {
        if (algorithm == null) return;
        // If the new layout isn't supported by the active engine, we
        // silently refuse rather than silently fall back — callers can
        // inspect currentLayout() to see what actually took effect.
        this.currentLayout = algorithm;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            if (algorithm.isSupportedByCytoscape()) cytoscapeViewer.setLayout(algorithm);
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            if (algorithm.isSupportedBySigma()) sigmaViewer.setLayout(algorithm);
        } else if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            if (algorithm.isSupportedByNvl()) nvlViewer.setLayout(algorithm);
        } else if (visViewer != null) {
            if (algorithm.isSupportedByVisNetwork()) visViewer.setLayout(algorithm);
        }
    }

    public LayoutAlgorithm currentLayout() { return currentLayout; }

    public void setLayoutOptions(Map<String, Object> options) {
        this.currentLayoutOptions = options == null ? Map.of() : Map.copyOf(options);
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setLayoutOptions(currentLayoutOptions);
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            sigmaViewer.setLayoutOptions(currentLayoutOptions);
        } else if (visViewer != null) {
            visViewer.setLayoutOptions(currentLayoutOptions);
        }
    }

    public void setLeidenClusterColors(Map<String, String> colors) {
        if (colors == null) return;
        this.currentLeidenColors = Map.copyOf(colors);
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setLeidenClusterColors(currentLeidenColors);
            cytoscapeViewer.applyNodeColors(resolveEffective(currentNodeConfig, currentLeidenColors));
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            sigmaViewer.setLeidenClusterColors(currentLeidenColors);
            sigmaViewer.applyNodeColors(resolveEffective(currentNodeConfig, currentLeidenColors));
        } else if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            nvlViewer.setLeidenClusterColors(currentLeidenColors);
            nvlViewer.applyNodeColors(resolveEffective(currentNodeConfig, currentLeidenColors));
        } else if (visViewer != null) {
            visViewer.setLeidenClusterColors(currentLeidenColors);
            visViewer.applyNodeColors(resolveEffective(currentNodeConfig, currentLeidenColors));
        }
    }

    /* ---- community aggregation view (Cytoscape only) ---- */

    /**
     * Switch the canvas between the original per-node view and the
     * aggregated community view (one Cytoscape node per Leiden community,
     * one aggregated edge per inter-community pair).
     *
     * <p>Cytoscape only — vis-network has no compound-node semantics so
     * the dialog disables this option when vis is active. {@link GraphConfigurationDialog}
     * checks {@link #getEngine()} and surfaces a hint when the user tries
     * to enable it under vis.</p>
     *
     * <p>The {@code colors} argument is optional — when {@code null}, the
     * cached Leiden colors from the last {@link #setLeidenClusterColors}
     * call are used. This lets the dialog re-apply the aggregation after
     * a clustering re-run without re-passing the map every time.</p>
     *
     * <p>Backwards-compatible overload — defaults the dynamic-size flag
     * to {@code false} (uniform fixed-size community-nodes).</p>
     */
    public void setCommunityView(boolean enabled, Map<String, String> colors) {
        setCommunityView(enabled, colors, false);
    }

    /**
     * Same as {@link #setCommunityView(boolean, Map)} but with an
     * explicit flag controlling whether the cytoscape-side community-node
     * size scales logarithmically with the sum of incoming edge weights
     * ({@code dynamicSize=true}) or stays at the fixed default size
     * ({@code dynamicSize=false}, the previous default after the user
     * requested a toggle).
     */
    public void setCommunityView(boolean enabled, Map<String, String> colors, boolean dynamicSize) {
        if (colors != null) this.currentLeidenColors = Map.copyOf(colors);
        this.communityViewActive = enabled;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            if (enabled) {
                cytoscapeViewer.setCommunityView(true, currentLeidenColors, dynamicSize);
            } else {
                cytoscapeViewer.setCommunityView(false, null, dynamicSize);
            }
            cytoscapeViewer.setCommunityViewActive(enabled);
        } else if (currentEngine == GraphEngine.SIGMA) {
            LOG.warning("SwitchingViewer.setCommunityView: sigma engine — community aggregation not yet supported (deferred to a follow-up that wires graphology's addNodeWithParent)");
        } else {
            LOG.warning("SwitchingViewer.setCommunityView: vis-network engine — community aggregation not supported");
        }
    }

    /**
     * Whether the community-aggregation view is currently active on the
     * active engine. Returns {@code false} for vis-network (no-op there).
     */
    public boolean isCommunityViewActive() {
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            return cytoscapeViewer.isCommunityViewActive();
        }
        return false;
    }

    /**
     * Register a listener that fires when the user drills into a
     * community (Cytoscape only — vis-network never produces these events).
     */
    public void addCommunityDrillListener(CytoscapeViewer.CommunityDrillListener l) {
        communityDrillListeners.add(l);
        if (cytoscapeViewer != null) cytoscapeViewer.addCommunityDrillListener(l);
    }

    /**
     * Returns the most recent Leiden color map (id → hex) pushed to the
     * active engine, or an empty map when clustering has not been applied.
     * Used by the {@link GraphConfigurationDialog} Legend section when the
     * user picks {@code Source = Leiden Clusters} or {@code Combined}.
     */
    public Map<String, String> getLeidenClusterColors() {
        return currentLeidenColors;
    }

    /**
     * Push the optional legend panel to the active engine. {@code entries}
     * is the full row list (color, label, count) in panel-render order;
     * {@code enabled} controls visibility — when {@code false} the panel
     * hides but the entries are kept so toggling back on restores it.
     *
     * <p>Sigma and Cytoscape are excluded: their Color Palette is
     * auto-managed by the bridge from the per-node color maps
     * ({@link #applyNodeColors} / {@link #setLeidenClusterColors}). NVL
     * never had a legend API. Calls for any of those engines are silently
     * ignored.</p>
     *
     * <p>The legend payload survives engine switches — after
     * {@link #switchTo(GraphEngine)} the panel is re-applied to the fresh
     * engine automatically (except Sigma / Cytoscape / NVL, see above).</p>
     */
    public void setLegend(List<LegendEntry> entries, boolean enabled) {
        this.currentLegend = entries == null ? List.of() : List.copyOf(entries);
        this.legendEnabled = enabled;
        if (visViewer != null && currentEngine != GraphEngine.SIGMA
                && currentEngine != GraphEngine.CYTOSCAPE) {
            visViewer.setLegend(currentLegend, enabled);
        }
    }

    /** Hide the legend panel and discard the cached entries. */
    public void clearLegend() {
        this.currentLegend = List.of();
        this.legendEnabled = false;
        if (visViewer != null && currentEngine != GraphEngine.SIGMA
                && currentEngine != GraphEngine.CYTOSCAPE) {
            visViewer.clearLegend();
        }
    }

    /** Returns the currently configured legend entries (immutable copy). */
    public List<LegendEntry> getLegend() {
        return currentLegend;
    }

    /** Whether the legend panel is currently configured to be visible. */
    public boolean isLegendEnabled() {
        return legendEnabled;
    }

    public void fitToScreen() {
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.fitToScreen();
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            sigmaViewer.fitToScreen();
        } else if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            nvlViewer.fitToScreen();
        } else if (visViewer != null) {
            visViewer.fitToScreen();
        }
    }

    public void clear() {
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.clear();
        } else if (currentEngine == GraphEngine.SIGMA && sigmaViewer != null) {
            sigmaViewer.clear();
        } else if (currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null) {
            nvlViewer.clear();
        } else if (visViewer != null) {
            visViewer.clear();
        }
    }

    /* ---- viewport / sizing ---- */

    /**
     * @return the current composite size as {@code [width, height]} in
     *         pixels. For both engines this is the Browser widget size,
     *         which equals the iframe canvas size. Returns
     *         {@code [0, 0]} when the composite is not yet laid out
     *         (e.g. during construction); callers MUST treat that as
     *         "fall back to default sizing".
     */
    public int[] getViewportSize() {
        if (isDisposed()) return new int[]{0, 0};
        org.eclipse.swt.graphics.Point p = getSize();
        return new int[]{p.x, p.y};
    }

    /* ---- physics & auto-fit (vis-only, no-op for Cytoscape) ---- */

    /**
     * Forward to the active vis-network viewer if present. Cytoscape has
     * no physics concept, so the call is silently ignored when the
     * active engine is Cytoscape.
     */
    public void setPhysics(boolean enabled) {
        if (visViewer != null) visViewer.setPhysics(enabled);
    }

    /**
     * @return the active vis-network viewer's physics-enabled flag, or
     *         {@code true} when Cytoscape is active (no physics = always
     *         "enabled" from the user's perspective).
     */
    public boolean isPhysicsEnabled() {
        if (visViewer != null) return visViewer.isPhysicsEnabled();
        return true;
    }

    /**
     * Forward to the active vis-network viewer if present. Cytoscape's
     * fcose layout always runs with {@code fit:true} and ignores this
     * flag.
     */
    public void setAutoFitOnStabilization(boolean enabled) {
        if (visViewer != null) visViewer.setAutoFitOnStabilization(enabled);
    }

    /** @see #setAutoFitOnStabilization(boolean) */
    public boolean isAutoFitOnStabilization() {
        if (visViewer != null) return visViewer.isAutoFitOnStabilization();
        return true;
    }

    /* ---- selection listeners ---- */

    public void addNodeSelectionListener(NodeSelectionListener l) {
        nodeListeners.add(l);
        if (visViewer != null) visViewer.addNodeSelectionListener(l);
        if (cytoscapeViewer != null) cytoscapeViewer.addNodeSelectionListener(l);
        if (sigmaViewer != null) sigmaViewer.addNodeSelectionListener(l);
        if (nvlViewer != null) nvlViewer.addNodeSelectionListener(l);
    }

    public void addRelationshipSelectionListener(RelationshipSelectionListener l) {
        relListeners.add(l);
        if (visViewer != null) visViewer.addRelationshipSelectionListener(l);
        if (cytoscapeViewer != null) cytoscapeViewer.addRelationshipSelectionListener(l);
        if (sigmaViewer != null) sigmaViewer.addRelationshipSelectionListener(l);
        if (nvlViewer != null) nvlViewer.addRelationshipSelectionListener(l);
    }

    public void addSelectionClearedListener(SelectionClearedListener l) {
        clearedListeners.add(l);
        if (visViewer != null) visViewer.addSelectionClearedListener(l);
        if (cytoscapeViewer != null) cytoscapeViewer.addSelectionClearedListener(l);
        if (sigmaViewer != null) sigmaViewer.addSelectionClearedListener(l);
        if (nvlViewer != null) nvlViewer.addSelectionClearedListener(l);
    }

    public void setContextMenuProvider(ContextMenuProvider provider) {
        this.currentContextMenuProvider = provider;
        if (visViewer != null) visViewer.setContextMenuProvider(provider);
        if (cytoscapeViewer != null) cytoscapeViewer.setContextMenuProvider(provider);
        if (sigmaViewer != null) sigmaViewer.setContextMenuProvider(provider);
        if (nvlViewer != null) nvlViewer.setContextMenuProvider(provider);
    }

    /* ---- internals ---- */

    private void wireViewer(GraphViewer v) {
        for (NodeSelectionListener l : nodeListeners) v.addNodeSelectionListener(l);
        for (RelationshipSelectionListener l : relListeners) v.addRelationshipSelectionListener(l);
        for (SelectionClearedListener l : clearedListeners) v.addSelectionClearedListener(l);
    }

    private void wireViewer(CytoscapeViewer v) {
        for (NodeSelectionListener l : nodeListeners) v.addNodeSelectionListener(l);
        for (RelationshipSelectionListener l : relListeners) v.addRelationshipSelectionListener(l);
        for (SelectionClearedListener l : clearedListeners) v.addSelectionClearedListener(l);
        for (CytoscapeViewer.CommunityDrillListener l : communityDrillListeners) {
            v.addCommunityDrillListener(l);
        }
    }

    private void wireViewer(SigmaViewer v) {
        for (NodeSelectionListener l : nodeListeners) v.addNodeSelectionListener(l);
        for (RelationshipSelectionListener l : relListeners) v.addRelationshipSelectionListener(l);
        for (SelectionClearedListener l : clearedListeners) v.addSelectionClearedListener(l);
    }

    private void wireViewer(Neo4jNvlViewer v) {
        for (NodeSelectionListener l : nodeListeners) v.addNodeSelectionListener(l);
        for (RelationshipSelectionListener l : relListeners) v.addRelationshipSelectionListener(l);
        for (SelectionClearedListener l : clearedListeners) v.addSelectionClearedListener(l);
    }

    private void disposeViewer() {
        if (visViewer != null && !visViewer.isDisposed()) {
            visViewer.dispose();
        }
        visViewer = null;
        if (cytoscapeViewer != null && !cytoscapeViewer.isDisposed()) {
            cytoscapeViewer.dispose();
        }
        cytoscapeViewer = null;
        if (sigmaViewer != null && !sigmaViewer.isDisposed()) {
            sigmaViewer.dispose();
        }
        sigmaViewer = null;
        if (nvlViewer != null && !nvlViewer.isDisposed()) {
            nvlViewer.dispose();
        }
        nvlViewer = null;
    }

    @Override
    public void dispose() {
        disposeViewer();
        nodeListeners.clear();
        relListeners.clear();
        clearedListeners.clear();
        engineListeners.clear();
        super.dispose();
    }

    /** Listener notified when {@link #switchTo(GraphEngine)} actually changes the engine. */
    @FunctionalInterface
    public interface EngineListener {
        void engineChanged(GraphEngine engine);
    }
}
