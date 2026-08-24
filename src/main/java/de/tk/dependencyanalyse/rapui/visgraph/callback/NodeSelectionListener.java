package de.tk.dependencyanalyse.rapui.visgraph.callback;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;

/**
 * Listener for node selection events from the vis-network canvas.
 */
@FunctionalInterface
public interface NodeSelectionListener {
    void nodeSelected(GraphNode node);
}
