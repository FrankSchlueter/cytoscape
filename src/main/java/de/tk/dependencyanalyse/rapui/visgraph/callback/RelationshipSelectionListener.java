package de.tk.dependencyanalyse.rapui.visgraph.callback;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;

/**
 * Listener for relationship selection events.
 */
@FunctionalInterface
public interface RelationshipSelectionListener {
    void relationshipSelected(GraphRelationship relationship);
}
