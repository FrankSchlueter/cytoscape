package de.tk.dependencyanalyse.rapui.visgraph.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link GraphData#toGraphologyElements(NodeConfig)} produces
 * the expected graphology payload: nodes as {@code [{key, attributes}]},
 * edges as {@code [{key, source, target, attributes}]}, plus an embedded
 * tooltip and the canonical weight/logWeight edge attributes consumed by
 * the sigma engine's ForceDirected2 layout.
 */
class GraphDataGraphologyElementsTest {

    @Test
    void nodesCarryKeyAndAttributes() {
        GraphNode node = new GraphNode("n1", List.of("Node"),
                java.util.Map.of("name", "Node1", "nodeTag", "entity"));
        GraphData data = new GraphData(List.of(node), List.of());
        java.util.Map<String, Object> payload = data.toGraphologyElements(null);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> nodes = (List<java.util.Map<String, Object>>) payload.get("nodes");
        assertEquals(1, nodes.size());
        java.util.Map<String, Object> n = nodes.get(0);
        assertEquals("n1", n.get("key"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attrs = (java.util.Map<String, Object>) n.get("attributes");
        assertNotNull(attrs);
        assertEquals("Node1", attrs.get("label"));
        assertEquals("Node", attrs.get("nodeType"));
        assertEquals("entity", attrs.get("nodeTag"));
        assertTrue(attrs.get("tooltip").toString().contains("Node1"));
    }

    @Test
    void edgesCarryWeightLogWeightAndLabel() {
        GraphNode src = new GraphNode("A", List.of("Node"), java.util.Map.of("name", "A"));
        GraphNode tgt = new GraphNode("B", List.of("Node"), java.util.Map.of("name", "B"));
        GraphRelationship edge = new GraphRelationship("e1", "REL", src, tgt,
                java.util.Map.of("weight", 100.0));
        GraphData data = new GraphData(List.of(src, tgt), List.of(edge));
        java.util.Map<String, Object> payload = data.toGraphologyElements(null);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> edges = (List<java.util.Map<String, Object>>) payload.get("edges");
        assertEquals(1, edges.size());
        java.util.Map<String, Object> e = edges.get(0);
        assertEquals("e1", e.get("key"));
        assertEquals("A", e.get("source"));
        assertEquals("B", e.get("target"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attrs = (java.util.Map<String, Object>) e.get("attributes");
        assertNotNull(attrs);
        assertEquals(100.0, attrs.get("weight"));
        // logWeight = log(weight + 1) per GraphRelationship.toCytoscapeEdge() / .toGraphologyEdge()
        assertEquals(Math.log(101.0), attrs.get("logWeight"));
        // Size = sqrt-based scaling
        double lw = Math.log(101.0);
        double expected = 0.6 + 0.9 * Math.sqrt(Math.min(Math.max(lw, 0), 4));
        assertEquals(expected, attrs.get("size"));
        // Tooltip + header
        assertNotNull(attrs.get("tooltip"));
        assertEquals("A -> B", attrs.get("tooltipHeader"));
    }

    @Test
    void missingWeightOmitsWeightFields() {
        GraphNode src = new GraphNode("A", List.of("Node"), java.util.Map.of("name", "A"));
        GraphNode tgt = new GraphNode("B", List.of("Node"), java.util.Map.of("name", "B"));
        GraphRelationship edge = new GraphRelationship("e1", "REL", src, tgt,
                java.util.Map.of("name", "no-weight"));
        GraphData data = new GraphData(List.of(src, tgt), List.of(edge));
        java.util.Map<String, Object> payload = data.toGraphologyElements(null);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> edges = (List<java.util.Map<String, Object>>) payload.get("edges");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attrs = (java.util.Map<String, Object>) edges.get(0).get("attributes");
        assertNotNull(attrs.get("id"));
        // No weight attributes when weight is missing.
        assertEquals(null, attrs.get("weight"));
        assertEquals(null, attrs.get("logWeight"));
        assertEquals(null, attrs.get("size"));
    }
}
