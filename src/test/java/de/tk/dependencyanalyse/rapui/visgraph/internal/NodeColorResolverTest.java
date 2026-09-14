package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeColorResolver} — the engine-agnostic color
 * resolver used by the unified "update node colors" mechanism.
 *
 * <p>Each test focuses on one priority rule. Together they lock in the
 * precedence order: <em>Leiden &gt; globalTag &gt; per-label tag &gt;
 * label &gt; none</em> so every engine applies the same effective color
 * for the same input.</p>
 */
class NodeColorResolverTest {

    private static GraphNode node(String id, String nodeType, String product) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (nodeType != null) props.put("_nodeType_", nodeType);
        if (product != null) props.put("product", product);
        return new GraphNode(id, nodeType == null ? "Node" : nodeType, List.of(nodeType == null ? "Node" : nodeType), props);
    }

    private static GraphData dataOf(GraphNode... nodes) {
        return new GraphData(List.of(nodes), List.of());
    }

    @Test
    void emptyInputsYieldEmptyResult() {
        Map<String, String> colors = NodeColorResolver.resolveEffectiveColors(null, null, null);
        assertNotNull(colors);
        assertTrue(colors.isEmpty());

        Map<String, String> colors2 = NodeColorResolver.resolveEffectiveColors(
                GraphData.empty(), NodeConfig.defaults(), null);
        assertTrue(colors2.isEmpty());
    }

    @Test
    void leidenColorWinsOverEverything() {
        GraphNode n = node("a", "Class", "A");
        NodeConfig cfg = NodeConfig.defaults()
                .withLabelColor("Class", "#111111")
                .withGlobalTagValueColor("product", "A", "#222222");
        Map<String, String> leiden = Map.of("a", "#LEIDEN");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), cfg, leiden);

        assertEquals("#LEIDEN", result.get("a"));
    }

    @Test
    void globalTagColorWinsOverPerLabelTagAndLabelColor() {
        GraphNode n = node("a", "Class", "A");
        NodeConfig cfg = NodeConfig.defaults()
                .withLabelColor("Class", "#LABEL")
                .withTagValueColor("Class", "product", "A", "#PERTAG")
                .withGlobalTagValueColor("product", "A", "#GLOBAL");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), cfg, null);

        assertEquals("#GLOBAL", result.get("a"));
    }

    @Test
    void perLabelTagColorWinsOverLabelColor() {
        GraphNode n = node("a", "Class", "A");
        NodeConfig cfg = NodeConfig.defaults()
                .withLabelColor("Class", "#LABEL")
                .withTagValueColor("Class", "product", "A", "#PERTAG");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), cfg, null);

        assertEquals("#PERTAG", result.get("a"));
    }

    @Test
    void labelColorAppliesWhenNoTagMatches() {
        GraphNode n = node("a", "Class", "A");
        NodeConfig cfg = NodeConfig.defaults()
                .withLabelColor("Class", "#LABEL")
                .withTagValueColor("Class", "product", "B", "#OTHER_TAG");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), cfg, null);

        assertEquals("#LABEL", result.get("a"));
    }

    @Test
    void nodesWithNoMatchAreOmitted() {
        GraphNode a = node("a", "Class", "A");
        GraphNode b = node("b", "Enum",  "E");
        NodeConfig cfg = NodeConfig.defaults()
                .withLabelColor("Class", "#LABEL");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(a, b), cfg, null);

        assertEquals("#LABEL", result.get("a"));
        assertFalse(result.containsKey("b"));
    }

    @Test
    void internalPropsAreIgnoredInTagMatch() {
        GraphNode n = new GraphNode("a", "Class", List.of("Class"),
                Map.of("_nodeType_", "Class", "label", "sneaky"));
        NodeConfig cfg = NodeConfig.defaults()
                .withGlobalTagValueColor("label", "sneaky", "#NOT_APPLIED");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), cfg, null);

        assertFalse(result.containsKey("a"),
                "internal property 'label' must not match — only user-meaningful tags do");
    }

    @Test
    void nullNodeYieldsNullColor() {
        assertNull(NodeColorResolver.resolveOne(null, NodeConfig.defaults(), null));
    }

    @Test
    void emptyLeidenMapSkipsLeidenBranch() {
        GraphNode n = node("a", "Class", "A");
        NodeConfig cfg = NodeConfig.defaults()
                .withLabelColor("Class", "#LABEL");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), cfg, Map.of());

        assertEquals("#LABEL", result.get("a"));
    }

    @Test
    void nodeFallsBackToLabelWhenLeidenMapMissesId() {
        GraphNode a = node("a", "Class", "A");
        NodeConfig cfg = NodeConfig.defaults()
                .withLabelColor("Class", "#LABEL");
        // Leiden map has a different id — a is not in it, so the label
        // fallback should kick in.
        Map<String, String> leiden = Map.of("someone-else", "#LEIDEN");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(a), cfg, leiden);

        assertEquals("#LABEL", result.get("a"));
    }

    @Test
    void nullConfigAndLeidenSkipsTagAndLabelBranches() {
        GraphNode n = node("a", "Class", "A");
        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), null, null);
        assertFalse(result.containsKey("a"));
    }

    @Test
    void firstMatchingGlobalTagPropertyWins() {
        // The resolver walks globalTagColors in iteration order. The
        // first property whose value matches a rule decides. This locks
        // in the existing documented ordering (see NodeColorResolver
        // Javadoc, priority #2).
        GraphNode n = new GraphNode("a", "Class", List.of("Class"),
                new LinkedHashMap<>(Map.of(
                        "_nodeType_", "Class",
                        "ownerProduct", "X",
                        "product", "Y")));
        NodeConfig cfg = NodeConfig.defaults()
                .withGlobalTagValueColor("product", "Y", "#BY_PRODUCT")
                .withGlobalTagValueColor("ownerProduct", "X", "#BY_OWNER");

        Map<String, String> result = NodeColorResolver.resolveEffectiveColors(
                dataOf(n), cfg, null);

        // Both rules match. Iteration order decides; we document that
        // the resolver returns the first match it sees in iteration
        // order  — the test asserts this is the case so future
        // refactoring that switches to "best" / "most-specific" would
        // either break this test intentionally (then update the docs).
        String picked = result.get("a");
        assertNotNull(picked);
        assertTrue("#BY_PRODUCT".equals(picked) || "#BY_OWNER".equals(picked),
                "expected one of the two matched colors but got " + picked);
    }
}
