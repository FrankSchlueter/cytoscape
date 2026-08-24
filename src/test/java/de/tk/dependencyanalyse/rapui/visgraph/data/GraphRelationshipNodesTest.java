package de.tk.dependencyanalyse.rapui.visgraph.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the {@link GraphRelationship} constructor now accepts
 * live {@link GraphNode} references and exposes them via
 * {@link GraphRelationship#getSource()} / {@link GraphRelationship#getTarget()}.
 *
 * <p>The deprecated id-only constructor must still work for backward
 * compatibility — it transparently wraps the strings into
 * placeholder {@link GraphNode}s, so {@code getSourceId()} continues to
 * return the original id.</p>
 */
class GraphRelationshipNodesTest {

    @Test
    void primaryConstructorStoresLiveNodeReferences() {
        GraphNode source = new GraphNode("alpha", List.of("Class"),
                Map.of("name", "Alpha"));
        GraphNode target = new GraphNode("beta",  List.of("Class"),
                Map.of("name", "Beta"));
        GraphRelationship r = new GraphRelationship(
                "r1", "REL", source, target,
                Map.of(GraphRelationship.PROP_WEIGHT, 7.0));

        // Live references are the *same* objects.
        assertSame(source, r.getSource(),
                "source must be the live GraphNode passed to the constructor");
        assertSame(target, r.getTarget());
        // String accessors still derive from the node ids.
        assertEquals("alpha", r.getSourceId());
        assertEquals("beta",  r.getTargetId());
        // Downstream callers can read the node's data without lookups.
        assertEquals(List.of("Class"), r.getSource().getLabels());
        assertEquals(List.of("Class"), r.getTarget().getLabels());
    }

    @Test
    void primaryConstructorRequiresNonNullNodes() {
        GraphNode target = new GraphNode("beta", List.of(), Map.of());
        GraphNode source = new GraphNode("alpha", List.of(), Map.of());
        // Both source + target are required.
        assertThrows(NullPointerException.class,
                () -> new GraphRelationship("r1", "REL", null, target, null));
        assertThrows(NullPointerException.class,
                () -> new GraphRelationship("r1", "REL", source, null, null));
    }

    @Test
    void legacyStringConstructorIsDeprecatedButWorking() {
        // The deprecated (String, String, String, String, Map) ctor must
        // still be reachable so existing tests and 3rd-party callers don't
        // break. Internally it wraps the strings into placeholder
        // GraphNodes — getSourceId() and getSource().getId() agree.
        GraphRelationship r = new GraphRelationship(
                "r2", "REL", "x", "y", Map.of());
        assertEquals("x", r.getSourceId());
        assertEquals("y", r.getTargetId());
        assertNotNull(r.getSource());
        assertEquals("x", r.getSource().getId());
        assertNotNull(r.getTarget());
        assertEquals("y", r.getTarget().getId());
    }

    @Test
    void serializationUsesLiveIdsAfterRefactor() {
        GraphNode source = new GraphNode("src", List.of(),
                Map.of("name", "src"));
        GraphNode target = new GraphNode("dst", List.of(),
                Map.of("name", "dst"));
        GraphRelationship r = new GraphRelationship("r", "REL", source, target,
                Map.of(GraphRelationship.PROP_WEIGHT, 3.0));

        // vis-network serialization — fields stay "from" / "to".
        Map<String, Object> vis = r.toVisNetworkData();
        assertEquals("src", vis.get("from"));
        assertEquals("dst", vis.get("to"));

        // Cytoscape serialization — fields are "source" / "target".
        @SuppressWarnings("unchecked")
        Map<String, Object> cy = (Map<String, Object>)
                r.toCytoscapeEdge().get("data");
        assertEquals("src", cy.get("source"));
        assertEquals("dst", cy.get("target"));

        // NVL serialization.
        Map<String, Object> nvl = r.toNvlData();
        assertEquals("src", nvl.get("from"));
        assertEquals("dst", nvl.get("to"));
    }

    @Test
    void nodesFromConstructorAreReusedAcrossEdges() {
        // Building many relationships that share endpoints must yield
        // relationships whose getSource() is the SAME GraphNode instance —
        // this is the whole point of the refactor (downstream code can
        // rely on identity-based lookups, not id-based).
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c")) {
            nodes.computeIfAbsent(id, k -> new GraphNode(k, List.of(), Map.of()));
        }
        GraphRelationship r1 = new GraphRelationship("e1", "REL",
                nodes.get("a"), nodes.get("b"), Map.of());
        GraphRelationship r2 = new GraphRelationship("e2", "REL",
                nodes.get("a"), nodes.get("c"), Map.of());
        assertSame(r1.getSource(), r2.getSource(),
                "shared endpoint nodes must be the same instance");
        assertNotSame(r1.getTarget(), r2.getTarget());
    }
}
