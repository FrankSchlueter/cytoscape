package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the discovery logic in {@link GraphConfigurationDialog.Discovery}
 * without spinning up an SWT Shell — the discovery methods are pure
 * functions of {@link GraphData}.
 */
class GraphConfigurationDialogDiscoveryTest {

    private GraphData sampleGraphWithAllWhitelistedProps() {
        GraphNode n1 = new GraphNode("a", List.of("Class"),
                Map.of("name", "a", "product", "X", "bundle", "B1", "ownerProduct", "OP1", "department", "D1"));
        GraphNode n2 = new GraphNode("b", List.of("Class"),
                Map.of("name", "b", "product", "Y", "bundle", "B2", "ownerProduct", "OP1", "department", "D2"));
        GraphNode n3 = new GraphNode("c", List.of("BatchReader"),
                Map.of("name", "c", "product", "X", "bundle", "B1", "ownerProduct", "OP2"));
        return new GraphData(List.of(n1, n2, n3), Collections.emptyList());
    }

    @Test
    void tagCandidatesAreLimitedToWhitelist() {
        GraphData data = sampleGraphWithAllWhitelistedProps();
        List<String> candidates = GraphConfigurationDialog.Discovery.tagCandidates(data);
        assertTrue(candidates.contains("product"));
        assertTrue(candidates.contains("bundle"));
        assertTrue(candidates.contains("ownerProduct"));
        assertFalse(candidates.contains("department"),
                "non-whitelisted properties must be filtered out");
    }

    @Test
    void tagCandidatesAppearInWhitelistOrder() {
        GraphData data = sampleGraphWithAllWhitelistedProps();
        List<String> candidates = GraphConfigurationDialog.Discovery.tagCandidates(data);
        assertEquals(Arrays.asList("product", "bundle", "ownerProduct"), candidates);
    }

    @Test
    void tagCandidatesExcludePropertiesWithTooFewValues() {
        // Only 'product' has 2 distinct values; 'bundle' only 1.
        GraphNode n1 = new GraphNode("a", List.of("Class"),
                Map.of("product", "X", "bundle", "B1"));
        GraphNode n2 = new GraphNode("b", List.of("Class"),
                Map.of("product", "Y", "bundle", "B1"));
        GraphData data = new GraphData(List.of(n1, n2), Collections.emptyList());

        List<String> candidates = GraphConfigurationDialog.Discovery.tagCandidates(data);
        assertEquals(List.of("product"), candidates);
    }

    @Test
    void tagCandidatesExcludePropertiesWithTooManyValues() {
        // 22 distinct 'product' values across the graph → exceeds the
        // MAX_TAG_VALUES (20) cap. Build the graph by giving each node a
        // distinct 'product' value via property keys other than 'product'
        // would not work because each node only has one 'product' value.
        // Instead we create 22 individual nodes, each with a unique product.
        List<GraphNode> manyNodes = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            manyNodes.add(new GraphNode("n" + i, List.of("Class"),
                    Map.of("product", "p" + i)));
        }
        GraphData data = new GraphData(manyNodes, Collections.emptyList());

        List<String> candidates = GraphConfigurationDialog.Discovery.tagCandidates(data);
        assertFalse(candidates.contains("product"),
                "properties with too many values must be excluded");
    }

    @Test
    void distinctCountReturnsAccurateCount() {
        GraphData data = sampleGraphWithAllWhitelistedProps();
        assertEquals(2, GraphConfigurationDialog.Discovery.distinctCount(data, "product"));
        assertEquals(2, GraphConfigurationDialog.Discovery.distinctCount(data, "bundle"));
        assertEquals(2, GraphConfigurationDialog.Discovery.distinctCount(data, "ownerProduct"));
        assertEquals(2, GraphConfigurationDialog.Discovery.distinctCount(data, "department"));
    }

    @Test
    void distinctCountZeroWhenPropertyMissing() {
        GraphNode n = new GraphNode("a", List.of("Class"), Map.of("name", "a"));
        GraphData data = new GraphData(List.of(n), Collections.emptyList());
        assertEquals(0, GraphConfigurationDialog.Discovery.distinctCount(data, "product"));
    }

    @Test
    void nodeTypeValuesEnumeratesAllLabels() {
        List<String> types = GraphConfigurationDialog.Discovery.nodeTypeValues(
                sampleGraphWithAllWhitelistedProps());
        assertEquals(2, types.size());
        assertTrue(types.contains("Class"));
        assertTrue(types.contains("BatchReader"));
    }

    @Test
    void nodeTypeValuesSortedAlphabetically() {
        List<String> types = GraphConfigurationDialog.Discovery.nodeTypeValues(
                sampleGraphWithAllWhitelistedProps());
        assertEquals(List.of("BatchReader", "Class"), types);
    }

    @Test
    void tagCandidateMaxLimitIsExposedAndPositive() {
        int cap = GraphConfigurationDialog.Discovery.maxTagValues();
        assertTrue(cap >= 2 && cap <= 100);
        assertEquals(List.of("product", "bundle", "ownerProduct"),
                GraphConfigurationDialog.Discovery.tagWhitelist());
    }

    @Test
    void allNodesHaveExplicitNodeTypeWhenEveryNodeCarriesIt() {
        GraphNode n1 = new GraphNode("a", List.of("Class"),
                Map.of("_nodeType_", "Class"));
        GraphNode n2 = new GraphNode("b", List.of("BatchReader"),
                Map.of("_nodeType_", "BatchReader"));
        GraphData data = new GraphData(List.of(n1, n2), Collections.emptyList());
        assertTrue(GraphConfigurationDialog.Discovery.publicAllNodesHaveExplicitNodeType(data));
    }

    @Test
    void allNodesHaveExplicitNodeTypeFalseWhenAnyNodeMissesIt() {
        GraphNode n1 = new GraphNode("a", List.of("Class"),
                Map.of("_nodeType_", "Class"));
        GraphNode n2 = new GraphNode("b", List.of("BatchReader"),
                Collections.emptyMap());
        GraphData data = new GraphData(List.of(n1, n2), Collections.emptyList());
        assertFalse(GraphConfigurationDialog.Discovery.publicAllNodesHaveExplicitNodeType(data));
    }

    @Test
    void allNodesHaveExplicitNodeTypeFalseForEmptyGraph() {
        GraphData data = new GraphData(Collections.emptyList(), Collections.emptyList());
        assertFalse(GraphConfigurationDialog.Discovery.publicAllNodesHaveExplicitNodeType(data));
    }

    @Test
    void allNodesHaveExplicitNodeTypeFalseWhenPropertyValueIsBlank() {
        GraphNode n1 = new GraphNode("a", List.of("Class"),
                Map.of("_nodeType_", ""));
        GraphData data = new GraphData(List.of(n1), Collections.emptyList());
        assertFalse(GraphConfigurationDialog.Discovery.publicAllNodesHaveExplicitNodeType(data));
    }

    @Test
    void potentialLeidenClusterCountReturnsZeroForEmptyGraph() {
        // Empty graph → compute() returns an empty map → 0 communities.
        GraphData data = new GraphData(Collections.emptyList(), Collections.emptyList());
        assertEquals(0,
                GraphConfigurationDialog.Discovery.publicPotentialLeidenClusterCount(data));
    }

    @Test
    void potentialLeidenClusterCountMatchesLeidenColorsOutput() {
        // Three nodes in a chain: a-b-c. Leiden produces a single community
        // (perfectly modular). The count helper must mirror the palette
        // cardinality of the underlying compute() call.
        GraphNode a = new GraphNode("a", List.of("X"), Map.of("_nodeType_", "X"));
        GraphNode b = new GraphNode("b", List.of("X"), Map.of("_nodeType_", "X"));
        GraphNode c = new GraphNode("c", List.of("X"), Map.of("_nodeType_", "X"));
        List<de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship> edges = List.of(
                new de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship(
                        "e1", "REL", a, b, Collections.emptyMap()),
                new de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship(
                        "e2", "REL", b, c, Collections.emptyMap())
        );
        GraphData data = new GraphData(List.of(a, b, c), edges);
        int count = GraphConfigurationDialog.Discovery.publicPotentialLeidenClusterCount(data);
        // The helper mirrors the distinct-color count of LeidenColors.compute.
        // A 3-node chain typically folds into 1 community; the contract is
        // that the helper is non-negative and matches LeidenColors.compute.
        assertTrue(count >= 0);
        assertEquals((int) de.tk.dependencyanalyse.rapui.visgraph.LeidenColors.compute(data)
                        .values().stream().distinct().count(),
                count);
    }

    @Test
    void leidenClusterCapIsExposed() {
        assertTrue(GraphConfigurationDialog.Discovery.leidenClusterCap() >= 2);
    }
}

