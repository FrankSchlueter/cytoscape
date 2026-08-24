package de.tk.dependencyanalyse.rapui.visgraph.callback;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;

import java.util.List;

/**
 * Provider for context-menu entries, called by the viewer when a user
 * right-clicks a node or a relationship.
 *
 * Implementations return a list of {@link ContextMenuEntry}. The default
 * implementation returns an empty list, i.e. no menu items.
 */
public interface ContextMenuProvider {

    default List<ContextMenuEntry> forNode(GraphNode node) {
        return List.of();
    }

    default List<ContextMenuEntry> forRelationship(GraphRelationship relationship) {
        return List.of();
    }
}
