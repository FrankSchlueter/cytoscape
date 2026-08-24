package de.tk.dependencyanalyse.rapui.visgraph.callback;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A single entry in a context menu. Each entry has a stable id used by the
 * bridge to dispatch the action back to the correct handler.
 */
public final class ContextMenuEntry {

    private final String id;
    private final String label;
    private final boolean separator;
    private final boolean disabled;
    private final List<ContextMenuEntry> children;
    private final NodeHandler nodeHandler;
    private final RelationshipHandler relHandler;

    private ContextMenuEntry(String id, String label, boolean separator, boolean disabled,
                             List<ContextMenuEntry> children,
                             NodeHandler nodeHandler, RelationshipHandler relHandler) {
        this.id = id;
        this.label = label;
        this.separator = separator;
        this.disabled = disabled;
        this.children = children;
        this.nodeHandler = nodeHandler;
        this.relHandler = relHandler;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public boolean isSeparator() { return separator; }
    public boolean isDisabled() { return disabled; }
    public List<ContextMenuEntry> getChildren() { return children; }

    public static ContextMenuEntry of(String label, Consumer<GraphNode> handler) {
        Objects.requireNonNull(handler, "handler");
        return new ContextMenuEntry(UUID.randomUUID().toString(), label, false, false, List.of(),
                handler::accept, null);
    }

    public static ContextMenuEntry of(String label, Consumer<GraphNode> handler, String iconUrl) {
        ContextMenuEntry base = of(label, handler);
        if (iconUrl != null) {
            return new ContextMenuEntry(base.id, base.label + " " + iconUrl, false, false,
                    List.of(), base::dispatchNode, null);
        }
        return base;
    }

    public static ContextMenuEntry ofRelationship(String label, Consumer<GraphRelationship> handler) {
        Objects.requireNonNull(handler, "handler");
        return new ContextMenuEntry(UUID.randomUUID().toString(), label, false, false, List.of(),
                null, handler::accept);
    }

    public static ContextMenuEntry separator() {
        return new ContextMenuEntry(UUID.randomUUID().toString(), null, true, false, List.of(),
                null, null);
    }

    public static ContextMenuEntry submenu(String label, List<ContextMenuEntry> children) {
        return new ContextMenuEntry(UUID.randomUUID().toString(), label, false, false,
                List.copyOf(children), null, null);
    }

    public static ContextMenuEntry disabled(String label) {
        return new ContextMenuEntry(UUID.randomUUID().toString(), label, false, true, List.of(),
                null, null);
    }

    public void dispatchNode(GraphNode node) {
        if (nodeHandler != null) nodeHandler.handle(node);
    }

    public void dispatchRelationship(GraphRelationship rel) {
        if (relHandler != null) relHandler.handle(rel);
    }

    @FunctionalInterface
    interface NodeHandler { void handle(GraphNode node); }

    @FunctionalInterface
    interface RelationshipHandler { void handle(GraphRelationship rel); }
}
