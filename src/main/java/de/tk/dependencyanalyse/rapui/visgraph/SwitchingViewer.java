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
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final Listener disposeListener = e -> disposeViewer();

    private GraphData currentData;
    private NodeConfig currentNodeConfig;
    private LayoutAlgorithm currentLayout = LayoutAlgorithm.FORCE_ATLAS_2D;
    private Map<String, Object> currentLayoutOptions = Map.of();
    private ContextMenuProvider currentContextMenuProvider;
    private GraphEngine currentEngine = GraphEngine.VIS_NETWORK;

    /** Last Leiden cluster colors pushed via {@link #setLeidenClusterColors}. */
    private Map<String, String> currentLeidenColors = Map.of();
    /** Legend state. {@code null} means no legend has ever been set. */
    private List<LegendEntry> currentLegend = List.of();
    private boolean legendEnabled = false;

    private GraphViewer visViewer;
    private CytoscapeViewer cytoscapeViewer;

    private final java.util.List<NodeSelectionListener> nodeListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<RelationshipSelectionListener> relListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<SelectionClearedListener> clearedListeners = new CopyOnWriteArrayList<>();
    private final java.util.List<EngineListener> engineListeners = new CopyOnWriteArrayList<>();

    public SwitchingViewer(Composite parent, int style) {
        super(parent, style);
        addListener(SWT.Dispose, disposeListener);
        setLayout(new org.eclipse.swt.layout.FillLayout());
        // Create the initial viewer (vis-network by default).
        visViewer = new GraphViewer(this, SWT.NONE);
        wireViewer(visViewer);
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
     * engine switch without user intervention.</p>
     */
    public void switchTo(GraphEngine engine) {
        if (engine == null || engine == currentEngine) return;
        currentEngine = engine;
        disposeViewer();
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
        } else {
            visViewer = new GraphViewer(this, SWT.NONE);
            wireViewer(visViewer);
            if (currentData != null) visViewer.setGraphData(currentData);
            if (currentNodeConfig != null) visViewer.setNodeConfig(currentNodeConfig);
            visViewer.setLayout(currentLayout);
            if (!currentLeidenColors.isEmpty()) {
                visViewer.setLeidenClusterColors(currentLeidenColors);
            }
        }
        if (currentContextMenuProvider != null) {
            setContextMenuProvider(currentContextMenuProvider);
        }
        // Re-apply the legend AFTER everything else so the panel sits on top
        // of the freshly-applied data and styles.
        if (legendEnabled) {
            if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
                cytoscapeViewer.setLegend(currentLegend, true);
            } else if (visViewer != null) {
                visViewer.setLegend(currentLegend, true);
            }
        }
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
        } else if (visViewer != null) {
            visViewer.setGraphData(data);
        }
    }

    public GraphData getGraphData() { return currentData; }
    public void setNodeConfig(NodeConfig config) {
        this.currentNodeConfig = config;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setNodeConfig(config);
        } else if (visViewer != null) {
            visViewer.setNodeConfig(config);
        }
    }

    public NodeConfig getNodeConfig() {
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            return cytoscapeViewer.getNodeConfig();
        }
        if (visViewer != null) {
            return visViewer.getNodeConfig();
        }
        return currentNodeConfig == null ? NodeConfig.defaults() : currentNodeConfig;
    }

    public void setLayout(LayoutAlgorithm algorithm) {
        if (algorithm == null) return;
        this.currentLayout = algorithm;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setLayout(algorithm);
        } else if (visViewer != null) {
            visViewer.setLayout(algorithm);
        }
    }

    public LayoutAlgorithm currentLayout() { return currentLayout; }

    public void setLayoutOptions(Map<String, Object> options) {
        this.currentLayoutOptions = options == null ? Map.of() : Map.copyOf(options);
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setLayoutOptions(currentLayoutOptions);
        }
    }

    public void setLeidenClusterColors(Map<String, String> colors) {
        if (colors == null) return;
        this.currentLeidenColors = Map.copyOf(colors);
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setLeidenClusterColors(currentLeidenColors);
        } else if (visViewer != null) {
            visViewer.setLeidenClusterColors(currentLeidenColors);
        }
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
     * <p>The legend payload survives engine switches — after
     * {@link #switchTo(GraphEngine)} the panel is re-applied to the fresh
     * engine automatically.</p>
     */
    public void setLegend(List<LegendEntry> entries, boolean enabled) {
        this.currentLegend = entries == null ? List.of() : List.copyOf(entries);
        this.legendEnabled = enabled;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.setLegend(currentLegend, enabled);
        } else if (visViewer != null) {
            visViewer.setLegend(currentLegend, enabled);
        }
    }

    /** Hide the legend panel and discard the cached entries. */
    public void clearLegend() {
        this.currentLegend = List.of();
        this.legendEnabled = false;
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.clearLegend();
        } else if (visViewer != null) {
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
        } else if (visViewer != null) {
            visViewer.fitToScreen();
        }
    }

    public void clear() {
        if (currentEngine == GraphEngine.CYTOSCAPE && cytoscapeViewer != null) {
            cytoscapeViewer.clear();
        } else if (visViewer != null) {
            visViewer.clear();
        }
    }

    /* ---- selection listeners ---- */

    public void addNodeSelectionListener(NodeSelectionListener l) {
        nodeListeners.add(l);
        if (visViewer != null) visViewer.addNodeSelectionListener(l);
        if (cytoscapeViewer != null) cytoscapeViewer.addNodeSelectionListener(l);
    }

    public void addRelationshipSelectionListener(RelationshipSelectionListener l) {
        relListeners.add(l);
        if (visViewer != null) visViewer.addRelationshipSelectionListener(l);
        if (cytoscapeViewer != null) cytoscapeViewer.addRelationshipSelectionListener(l);
    }

    public void addSelectionClearedListener(SelectionClearedListener l) {
        clearedListeners.add(l);
        if (visViewer != null) visViewer.addSelectionClearedListener(l);
        if (cytoscapeViewer != null) cytoscapeViewer.addSelectionClearedListener(l);
    }

    public void setContextMenuProvider(ContextMenuProvider provider) {
        this.currentContextMenuProvider = provider;
        if (visViewer != null) visViewer.setContextMenuProvider(provider);
        if (cytoscapeViewer != null) cytoscapeViewer.setContextMenuProvider(provider);
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
