package de.tk.dependencyanalyse.rapui.visgraph.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Cytoscape serialization of {@link GraphRelationship} exposes
 * the pre-computed {@code logWeight} Cytoscape attribute that the
 * Cluster-Layout-Strategie relies on (Cluster-Layout.md §1, step 1:
 * Edge-Logarithmierung via {@code ln(weight+1)}).
 *
 * <p>The earlier name {@code log10Weight} was a misnomer — the formula
 * {@code Math.log(weight+1)} returns the <em>natural</em> logarithm, not
 * log base 10. Renamed to {@code logWeight} so the cytoscape-viewer.js
 * function strings ({@code idealEdgeLength}, {@code edgeElasticity},
 * cluster edge width) all key on the same attribute.</p>
 */
class GraphRelationshipCytoscapeLogWeightTest {

    private static GraphRelationship edgeWithWeight(double w) {
        GraphNode s = new GraphNode("a", List.of("Node"), Map.of("name", "a"));
        GraphNode t = new GraphNode("b", List.of("Node"), Map.of("name", "b"));
        return new GraphRelationship("e", "REL", s, t,
                Map.of(GraphRelationship.PROP_WEIGHT, w));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(GraphRelationship r) {
        return (Map<String, Object>) r.toCytoscapeEdge().get("data");
    }

    @Test
    void cytoscapeEdgeSurfacesLogWeightAsNaturalLogarithm() {
        // weight=99 → ln(100) ≈ 4.6052. Sanity-check that the attribute
        // really is ln(weight+1), not log10(weight+1) or something else.
        GraphRelationship r = edgeWithWeight(99);
        Map<String, Object> data = dataOf(r);
        Object raw = data.get("logWeight");
        assertNotNull(raw, "Cytoscape edge data must carry a logWeight attribute "
                + "consumed by the cluster layout's fcose function strings");
        assertInstanceOf(Double.class, raw,
                "logWeight must be a number so Cytoscape's data() helper can hand it to fcose");
        assertEquals(Math.log(99 + 1), ((Double) raw).doubleValue(), 1e-9,
                "logWeight must be ln(weight+1), not log10 or any other base");
    }

    @Test
    void cytoscapeEdgeNoLongerSurfacesLegacyLog10WeightKey() {
        // Renaming "log10Weight" → "logWeight" was a bug fix — the field
        // value is ln(...), not log10(...). Old name must not come back.
        GraphRelationship r = edgeWithWeight(7);
        Map<String, Object> data = dataOf(r);
        assertFalse(data.containsKey("log10Weight"),
                "the legacy 'log10Weight' key must be gone — the value was ln, not log10");
    }

    @Test
    void cytoscapeEdgeOmitsLogWeightWhenNoWeightProperty() {
        // Edges without a weight attribute carry no logWeight either —
        // the cytoscape bridge's partitionEdgesForLayout() falls back to
        // a default logWeight=1 in the function strings for unweighted edges,
        // matching ClusterLayoutOptions.edgeElasticity's `typeof lw === 'number'`
        // guard.
        GraphNode s = new GraphNode("a", List.of(), Map.of());
        GraphNode t = new GraphNode("b", List.of(), Map.of());
        GraphRelationship r = new GraphRelationship("e", "REL", s, t, Map.of());
        Map<String, Object> data = dataOf(r);
        assertFalse(data.containsKey("logWeight"),
                "edges without a weight property must NOT carry a logWeight entry");
    }

    @Test
    void cytoscapeEdgeLogWeightMatchesJavaFormulaForSampleWeights() {
        // Cross-check the formula against a few representative values from
        // the bundled /sample/export.csv distribution.
        assertLogWeight(1, Math.log(2));      // w=1 → ln(2) ≈ 0.693
        assertLogWeight(10, Math.log(11));    // w=10 → ln(11) ≈ 2.398
        assertLogWeight(100, Math.log(101));  // w=100 → ln(101) ≈ 4.615
        assertLogWeight(1000, Math.log(1001));// w=1000 → ln(1001) ≈ 6.909
    }

    private static void assertLogWeight(double weight, double expected) {
        GraphRelationship r = edgeWithWeight(weight);
        Object raw = dataOf(r).get("logWeight");
        assertNotNull(raw, "weight=" + weight + " must carry logWeight");
        assertEquals(expected, ((Double) raw).doubleValue(), 1e-9,
                "weight=" + weight + " → ln(" + (weight + 1) + ")");
    }
}