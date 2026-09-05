package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommunityAggregator}. Drives the aggregation
 * helpers directly with hand-built graphs (no SWT, no Cytoscape).
 */
class CommunityAggregatorTest {

    /* ---------- helpers ---------- */

    private static GraphNode node(String id) {
        return new GraphNode(id, List.of("Class"), Map.of("name", id));
    }

    private static GraphRelationship rel(String id, GraphNode src, GraphNode dst,
                                          Map<String, Object> props) {
        return new GraphRelationship(id, "WEIGHT", src, dst, props);
    }

    /** Build a graph with 2 communities of 2 nodes each + inter-edges. */
    private static GraphData twoCommunityGraph() {
        GraphNode a = node("a");
        GraphNode b = node("b");
        GraphNode c = node("c");
        GraphNode d = node("d");
        return new GraphData(List.of(a, b, c, d), List.of(
                rel("e1", a, b, Map.of("weight", 10.0)),                 // intra A
                rel("e2", c, d, Map.of("weight", 20.0)),                 // intra B
                rel("e3", a, c, Map.of("weight", 5.0)),                  // inter A->B
                rel("e4", d, b, Map.of("weight", 7.0)),                  // inter B->A
                rel("e5", a, d, Map.of("weight", 3.0))                   // inter A->B
        ));
    }

    private static Map<String, String> twoCommunityColors() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("a", "#4A90E2");
        m.put("b", "#4A90E2");
        m.put("c", "#E74C3C");
        m.put("d", "#E74C3C");
        return m;
    }

    /* ---------- communityIndexMap / communityNodeId / interEdgeId ---------- */

    @Test
    void communityIndexMapIsDeterministic() {
        Map<String, Integer> idx = CommunityAggregator.communityIndexMap(twoCommunityColors());
        assertEquals(2, idx.size());
        // Iteration order matches the colors map's iteration order.
        assertEquals(0, idx.values().iterator().next().intValue());
    }

    @Test
    void communityNodeIdIsStable() {
        assertEquals("community_0", CommunityAggregator.communityNodeId(0));
        assertEquals("community_42", CommunityAggregator.communityNodeId(42));
    }

    @Test
    void interEdgeIdIsDirectional() {
        // A->B and B->A must produce DIFFERENT ids so they can be
        // rendered as separate curved edges (the user explicitly asked
        // for direction-preserving aggregation).
        String idAB = CommunityAggregator.interEdgeId("#4A90E2", "#E74C3C");
        String idBA = CommunityAggregator.interEdgeId("#E74C3C", "#4A90E2");
        assertNotEquals(idAB, idBA, "interEdgeId must be directional — A->B differs from B->A");
        assertTrue(idAB.contains("_to_"), "inter-edge id must use the directional infix '_to_'");
    }

    @Test
    void interEdgeIdIsCaseInsensitive() {
        String id1 = CommunityAggregator.interEdgeId("#abc", "#DEF");
        String id2 = CommunityAggregator.interEdgeId("#ABC", "#def");
        assertEquals(id1, id2, "interEdgeId must be case-insensitive so case-mismatched palettes still dedupe");
    }

    /* ---------- buildRootElements ---------- */

    @Test
    void buildRootElementsReturnsOneNodePerCommunity() {
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        long communityNodes = out.stream()
                .filter(e -> Boolean.TRUE.equals(
                        ((Map<?, ?>) e.get("data")).get(CommunityAggregator.IS_COMMUNITY)))
                .count();
        assertEquals(2, communityNodes, "two distinct colours => two community-nodes");
    }

    @Test
    void buildRootElementsKeepsDirectionalEdgesSeparate() {
        // A->B (e3) + B->A (e4) + A->B (e5) must NOT collapse into a
        // single undirected edge. They become two directional edges:
        //   community_0 -> community_1 (e3 + e5 = weight 8.0)
        //   community_1 -> community_0 (e4 = weight 7.0)
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        long interEdges = out.stream()
                .filter(e -> Boolean.TRUE.equals(
                        ((Map<?, ?>) e.get("data")).get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .count();
        assertEquals(2, interEdges,
                "two distinct DIRECTIONS must produce two aggregated inter-edges (curved bezier cables)");
    }

    @Test
    void buildRootElementsSumsWeightsPerDirection() {
        // A->B direction: e3 (5.0) + e5 (3.0) = 8.0
        // B->A direction: e4 (7.0) only
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        List<Map<?, ?>> edges = out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .collect(java.util.stream.Collectors.toList());
        Map<?, ?> abEdge = findDirectionalEdge(edges, "#4a90e2", "#e74c3c");
        Map<?, ?> baEdge = findDirectionalEdge(edges, "#e74c3c", "#4a90e2");
        assertEquals(8.0, ((Number) abEdge.get("weight")).doubleValue(), 1e-9,
                "weight must be the SUM of all individual weights IN THAT DIRECTION (5.0 + 3.0)");
        assertEquals(2, ((Number) abEdge.get(CommunityAggregator.FIELD_EDGE_COUNT)).intValue(),
                "edgeCount must count the individual edges folded into THIS direction (2)");
        assertEquals(7.0, ((Number) baEdge.get("weight")).doubleValue(), 1e-9,
                "weight must be the sum of the B->A direction (7.0)");
        assertEquals(1, ((Number) baEdge.get(CommunityAggregator.FIELD_EDGE_COUNT)).intValue());
        // logWeight = ln(weight + 1)
        assertEquals(Math.log(9.0), ((Number) abEdge.get("logWeight")).doubleValue(), 1e-9);
        assertEquals(Math.log(8.0), ((Number) baEdge.get("logWeight")).doubleValue(), 1e-9);
    }

    @Test
    void aggregatedEdgeCarriesMemberEdgeIds() {
        // The A->B direction folded e3 (a->c) and e5 (a->d). The
        // memberEdgeIds list must contain both original ids so the JS
        // bridge can render a one-row-per-original-edge table and a
        // row-click can re-fire cgv_notifyRelationshipSelected against
        // the real Java relationship.
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        List<Map<?, ?>> edges = out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .collect(java.util.stream.Collectors.toList());
        Map<?, ?> abEdge = findDirectionalEdge(edges, "#4a90e2", "#e74c3c");
        Map<?, ?> baEdge = findDirectionalEdge(edges, "#e74c3c", "#4a90e2");
        java.util.List<?> abMembers = (java.util.List<?>) abEdge.get(
                CommunityAggregator.FIELD_MEMBER_EDGE_IDS);
        java.util.List<?> baMembers = (java.util.List<?>) baEdge.get(
                CommunityAggregator.FIELD_MEMBER_EDGE_IDS);
        assertNotNull(abMembers, "A->B direction must expose memberEdgeIds");
        assertNotNull(baMembers, "B->A direction must expose memberEdgeIds");
        assertEquals(java.util.Arrays.asList("e3", "e5"), abMembers,
                "A->B direction must list e3 and e5 in iteration order");
        assertEquals(java.util.List.of("e4"), baMembers,
                "B->A direction must contain only e4");
    }

    @Test
    void rootElementsEdgeLabelIsAggregatedWeightFormattedLikeTooltip() {
        // Per user spec: the on-canvas cytoscape label for a community
        // edge must show the SUM of original weights for that direction,
        // formatted EXACTLY like the tooltip's ": <weight>" suffix. Java
        // is the single source of truth — cytoscape-viewer.js mirrors
        // the format via formatAggregatedWeight so the community-edges
        // table column stays in sync. Integer-valued weights render
        // without a decimal point, fractional values keep one decimal
        // place (see CommunityAggregator.formatWeight).
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        List<Map<?, ?>> edges = out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .collect(java.util.stream.Collectors.toList());
        Map<?, ?> abEdge = findDirectionalEdge(edges, "#4a90e2", "#e74c3c");
        Map<?, ?> baEdge = findDirectionalEdge(edges, "#e74c3c", "#4a90e2");
        // A->B direction sums to 8.0 (integer-valued) -> label "8".
        assertEquals("8", abEdge.get("label"),
                "edge label must equal formatWeight(8.0) = \"8\"");
        // B->A direction sums to 7.0 -> label "7".
        assertEquals("7", baEdge.get("label"),
                "edge label must equal formatWeight(7.0) = \"7\"");
        // The on-canvas label and the tooltip suffix must agree: pull
        // the tooltip's ": <weight>" tail and compare.
        String abTooltip = (String) abEdge.get(CommunityAggregator.FIELD_TOOLTIP);
        String baTooltip = (String) baEdge.get(CommunityAggregator.FIELD_TOOLTIP);
        assertTrue(abTooltip.endsWith(": 8"),
                "tooltip suffix must equal formatWeight(8.0) = \"8\", got: " + abTooltip);
        assertTrue(baTooltip.endsWith(": 7"),
                "tooltip suffix must equal formatWeight(7.0) = \"7\", got: " + baTooltip);
    }

    @Test
    void rootElementsEdgeLabelUsesFractionalFormatForFractionalWeight() {
        // Fractional weight must keep one decimal place (e.g. "7.5"),
        // matching formatWeight() so the cytoscape label and the
        // tooltip's ": <weight>" suffix stay byte-identical.
        GraphNode a = node("a");
        GraphNode c = node("c");
        GraphData g = new GraphData(List.of(a, c), List.of(
                rel("e1", a, c, Map.of("weight", 7.5))
        ));
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#4A90E2");
        colors.put("c", "#E74C3C");
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(g, colors);
        // buildRootElements emits community-nodes BEFORE edges, so
        // we must filter to find the single inter-community edge
        // (a->c via #4A90E2 -> #E74C3C).
        Map<?, ?> edge = out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected exactly one inter-community edge, got: " + out));
        assertEquals("7.5", edge.get("label"),
                "fractional weight 7.5 must format as \"7.5\" (no integer rounding)");
        assertTrue(((String) edge.get(CommunityAggregator.FIELD_TOOLTIP)).endsWith(": 7.5"),
                "tooltip must end with the same \": 7.5\" suffix as the label");
    }

    @Test
    void communityNodeCarriesMemberIdsInColorOrder() {
        // The aggregated community-node must list its member-node ids
        // so the cytoscape bridge can render a "Cluster members" tooltip
        // without a second round-trip to the Java side.
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        Map<?, ?> blueNode = findCommunity(out, "#4A90E2");
        Map<?, ?> redNode = findCommunity(out, "#E74C3C");
        java.util.List<?> blueMembers = (java.util.List<?>) blueNode.get(
                CommunityAggregator.FIELD_MEMBER_IDS);
        java.util.List<?> redMembers = (java.util.List<?>) redNode.get(
                CommunityAggregator.FIELD_MEMBER_IDS);
        assertNotNull(blueMembers, "community node must expose memberIds");
        assertNotNull(redMembers, "community node must expose memberIds");
        assertEquals(java.util.Arrays.asList("a", "b"), blueMembers,
                "blue community (a, b) must list members in color-map insertion order");
        assertEquals(java.util.Arrays.asList("c", "d"), redMembers,
                "red community (c, d) must list members in color-map insertion order");
    }

    @Test
    void buildRootElementsOmitsIntraEdges() {
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        long edges = out.stream()
                .filter(e -> {
                    Map<?, ?> d = (Map<?, ?>) e.get("data");
                    return d.containsKey("source") && d.containsKey("target");
                })
                .count();
        assertEquals(2, edges,
                "root view must contain only the TWO directional inter-edges, no intra-community edges");
    }

    @Test
    void buildRootElementsLabelsCommunitiesBySizeDescending() {
        // 3 nodes in colour A, 1 node in colour B -> Cluster1 = A (3 members),
        // Cluster2 = B (1 member).
        GraphNode a = node("a");
        GraphNode b = node("b");
        GraphNode c = node("c");
        GraphNode d = node("d");
        GraphData g = new GraphData(List.of(a, b, c, d), List.of(
                rel("e1", a, b, Map.of("weight", 1.0)),
                rel("e2", b, c, Map.of("weight", 1.0))
        ));
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#4A90E2");
        colors.put("b", "#4A90E2");
        colors.put("c", "#4A90E2");
        colors.put("d", "#E74C3C");
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(g, colors);
        Map<?, ?> bigCommunity = findCommunity(out, "#4A90E2");
        Map<?, ?> smallCommunity = findCommunity(out, "#E74C3C");
        String bigLabel = (String) bigCommunity.get("label");
        String smallLabel = (String) smallCommunity.get("label");
        // Big community (3 nodes) should be labelled "C1" — index 0
        // after sorting by member count descending. Per the latest user
        // spec, the on-canvas label uses the short "C<N>" form; the
        // long "Cluster <N>" form stays in the tooltip header only.
        assertTrue(bigLabel.contains("C1"),
                "larger community should be labelled C1, got: " + bigLabel);
        assertTrue(smallLabel.contains("C2"),
                "smaller community should be labelled C2, got: " + smallLabel);
        assertEquals(3, ((Number) bigCommunity.get(
                CommunityAggregator.FIELD_MEMBER_COUNT)).intValue());
        assertEquals(1, ((Number) smallCommunity.get(
                CommunityAggregator.FIELD_MEMBER_COUNT)).intValue());
    }

    @Test
    void communityNodeLabelIsShortFormCN() {
        // Per user spec: the cluster-node label is the short form "C1",
        // "C2", ... so the visual reads as a compact cluster identifier.
        // The longer "Cluster N" wording is still used in the tooltip
        // header (see buildCommunityNodeTooltip in cytoscape-viewer.js)
        // and in the source/target labels of aggregated edges. The
        // member count stays available via the FIELD_MEMBER_COUNT field
        // for callers that need it (legend, table builders, status text).
        GraphNode a = node("a");
        GraphNode b = node("b");
        GraphNode c = node("c");
        GraphData g = new GraphData(List.of(a, b, c), Collections.emptyList());
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#aaa");
        colors.put("b", "#aaa");
        colors.put("c", "#aaa");
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(g, colors);
        Map<?, ?> community = findCommunity(out, "#aaa");
        assertEquals("C1", community.get("label"),
                "label must be the short form 'C1' — no member count suffix, no 'Cluster' prefix");
    }

    @Test
    void buildRootElementsReturnsEmptyForEmptyColors() {
        assertTrue(CommunityAggregator.buildRootElements(
                twoCommunityGraph(), Map.of()).isEmpty());
        assertTrue(CommunityAggregator.buildRootElements(
                twoCommunityGraph(), null).isEmpty());
        assertTrue(CommunityAggregator.buildRootElements(
                null, twoCommunityColors()).isEmpty());
    }

    /* ---------- buildCommunityDetailElements ---------- */

    @Test
    void buildCommunityDetailElementsReturnsOnlyThatCommunitysMembers() {
        List<Map<String, Object>> out = CommunityAggregator.buildCommunityDetailElements(
                twoCommunityGraph(), twoCommunityColors(), "#4A90E2");
        // Members of #4A90E2 are a, b.
        long nodes = out.stream()
                .filter(e -> {
                    Map<?, ?> d = (Map<?, ?>) e.get("data");
                    return d.containsKey("id") && !d.containsKey("source");
                })
                .count();
        assertEquals(2, nodes, "blue community has 2 members");
    }

    @Test
    void buildCommunityDetailElementsReturnsOnlyIntraEdges() {
        List<Map<String, Object>> out = CommunityAggregator.buildCommunityDetailElements(
                twoCommunityGraph(), twoCommunityColors(), "#4A90E2");
        long edges = out.stream()
                .filter(e -> {
                    Map<?, ?> d = (Map<?, ?>) e.get("data");
                    return d.containsKey("source") && d.containsKey("target");
                })
                .count();
        // Intra-edges for the blue community: e1 (a->b).
        assertEquals(1, edges, "detail view must only contain intra-community edges");
    }

    @Test
    void buildCommunityDetailElementsIsCaseInsensitive() {
        List<Map<String, Object>> outLower = CommunityAggregator.buildCommunityDetailElements(
                twoCommunityGraph(), twoCommunityColors(), "#4a90e2");
        List<Map<String, Object>> outUpper = CommunityAggregator.buildCommunityDetailElements(
                twoCommunityGraph(), twoCommunityColors(), "#4A90E2");
        assertEquals(outLower.size(), outUpper.size(),
                "detail view must be case-insensitive in the community colour");
    }

    @Test
    void buildCommunityDetailElementsForUnknownColourIsEmpty() {
        assertTrue(CommunityAggregator.buildCommunityDetailElements(
                twoCommunityGraph(), twoCommunityColors(), "#DEADBE").isEmpty());
        assertTrue(CommunityAggregator.buildCommunityDetailElements(
                twoCommunityGraph(), twoCommunityColors(), null).isEmpty());
        assertTrue(CommunityAggregator.buildCommunityDetailElements(
                null, twoCommunityColors(), "#4A90E2").isEmpty());
    }

    /* ---------- countAggregatedEdges / distinctCommunityColors ---------- */

    @Test
    void countAggregatedEdgesCountsDirectionsSeparately() {
        // 3 inter edges across 2 unique (direction-pairs) => 2 aggregated edges.
        // Previous (undirected) count was 1.
        assertEquals(2, CommunityAggregator.countAggregatedEdges(
                twoCommunityGraph(), twoCommunityColors()));
    }

    @Test
    void distinctCommunityColorsReturnsOnePerColour() {
        List<String> colors = CommunityAggregator.distinctCommunityColors(twoCommunityColors());
        assertEquals(2, colors.size());
    }

    @Test
    void distinctCommunityColorsEmptyOnEmptyMap() {
        assertTrue(CommunityAggregator.distinctCommunityColors(Map.of()).isEmpty());
        assertTrue(CommunityAggregator.distinctCommunityColors(null).isEmpty());
    }

    /* ---------- helpers for assertions ---------- */

    private static Map<?, ?> findCommunity(List<Map<String, Object>> out, String color) {
        return out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY)))
                .filter(d -> color.equalsIgnoreCase(
                        (String) d.get(CommunityAggregator.FIELD_COMMUNITY_COLOR)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no community-node for " + color));
    }

    private static Map<?, ?> findDirectionalEdge(List<Map<?, ?>> edges,
                                                  String sourceColor, String targetColor) {
        return edges.stream()
                .filter(d -> sourceColor.equalsIgnoreCase(
                        (String) d.get(CommunityAggregator.FIELD_SOURCE_COMMUNITY_COLOR)))
                .filter(d -> targetColor.equalsIgnoreCase(
                        (String) d.get(CommunityAggregator.FIELD_TARGET_COMMUNITY_COLOR)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no directional edge " + sourceColor + " -> " + targetColor));
    }

    /* ---------- source/target colour & tooltip ---------- */

    @Test
    void interEdgeCarriesDirectionalColorFields() {
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        List<Map<?, ?>> edges = out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .collect(java.util.stream.Collectors.toList());
        Map<?, ?> abEdge = findDirectionalEdge(edges, "#4a90e2", "#e74c3c");
        Map<?, ?> baEdge = findDirectionalEdge(edges, "#e74c3c", "#4a90e2");
        assertEquals("#4a90e2", abEdge.get(CommunityAggregator.FIELD_SOURCE_COMMUNITY_COLOR));
        assertEquals("#e74c3c", abEdge.get(CommunityAggregator.FIELD_TARGET_COMMUNITY_COLOR));
        assertEquals("#e74c3c", baEdge.get(CommunityAggregator.FIELD_SOURCE_COMMUNITY_COLOR));
        assertEquals("#4a90e2", baEdge.get(CommunityAggregator.FIELD_TARGET_COMMUNITY_COLOR));
    }

    @Test
    void interEdgeTooltipUsesClusterLabelsAndFormattedWeight() {
        // Communities sorted by member count descending → both have 2
        // members, so the tie-breaker is colour-asc: "#4A90E2" → idx 0
        // (Cluster 1), "#E74C3C" → idx 1 (Cluster 2).
        // TwoCommunity edges:
        //   A->C (5.0) + A->D (3.0) → Cluster 1 -> Cluster 2 (weight 8.0)
        //   D->B (7.0)                → Cluster 2 -> Cluster 1 (weight 7.0)
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        List<Map<?, ?>> edges = out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .collect(java.util.stream.Collectors.toList());
        Map<?, ?> abEdge = findDirectionalEdge(edges, "#4a90e2", "#e74c3c");
        Map<?, ?> baEdge = findDirectionalEdge(edges, "#e74c3c", "#4a90e2");
        assertEquals("Cluster 1 \u2192 Cluster 2: 8",
                abEdge.get(CommunityAggregator.FIELD_TOOLTIP));
        assertEquals("Cluster 2 \u2192 Cluster 1: 7",
                baEdge.get(CommunityAggregator.FIELD_TOOLTIP));
        assertEquals("Cluster 1",
                abEdge.get(CommunityAggregator.FIELD_SOURCE_LABEL));
        assertEquals("Cluster 2",
                abEdge.get(CommunityAggregator.FIELD_TARGET_LABEL));
    }

    @Test
    void interEdgeTooltipFormatsFractionalWeights() {
        // Build a graph with a fractional-weight inter-edge to exercise
        // the "%.1f" formatting branch.
        GraphNode a = node("a");
        GraphNode b = node("b");
        GraphNode c = node("c");
        GraphData g = new GraphData(List.of(a, b, c), List.of(
                rel("e1", a, c, Map.of("weight", 1.5))   // a (#aaa) -> c (#bbb) — inter
        ));
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#aaa");
        colors.put("b", "#aaa");
        colors.put("c", "#bbb");
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(g, colors);
        Map<?, ?> edge = out.stream()
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> Boolean.TRUE.equals(d.get(CommunityAggregator.IS_COMMUNITY_EDGE)))
                .findFirst().orElseThrow();
        String tooltip = (String) edge.get(CommunityAggregator.FIELD_TOOLTIP);
        assertEquals("Cluster 1 \u2192 Cluster 2: 1.5", tooltip,
                "fractional weights must keep one decimal place in the tooltip");
    }

    /* ---------- incomingWeightSum on community nodes ---------- */

    @Test
    void communityNodeCarriesIncomingWeightSum() {
        // twoCommunityGraph:
        //   e3 A->C (5.0)  → cluster of #4A90E2 -> cluster of #E74C3C (incoming to red)
        //   e4 D->B (7.0)  → red -> blue (incoming to blue)
        //   e5 A->D (3.0)  → blue -> red (incoming to red)
        // So:
        //   #4A90E2 (blue, Cluster 1)  incomingWeightSum = 7.0 (only e4)
        //   #E74C3C (red,  Cluster 2) incomingWeightSum = 5.0 + 3.0 = 8.0
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(
                twoCommunityGraph(), twoCommunityColors());
        Map<?, ?> blueNode = findCommunity(out, "#4A90E2");
        Map<?, ?> redNode = findCommunity(out, "#E74C3C");
        assertEquals(7.0,
                ((Number) blueNode.get(CommunityAggregator.FIELD_INCOMING_WEIGHT_SUM)).doubleValue(),
                1e-9, "blue community is target of e4 (D->B, weight 7) only");
        assertEquals(8.0,
                ((Number) redNode.get(CommunityAggregator.FIELD_INCOMING_WEIGHT_SUM)).doubleValue(),
                1e-9, "red community is target of e3 (A->C, 5) + e5 (A->D, 3) = 8");
    }

    @Test
    void communityNodeWithNoIncomingEdgesHasZeroIncomingWeightSum() {
        // A 1-community graph has no inter-edges at all, so
        // incomingWeightSum must be 0 (not absent — JS function mappers
        // rely on a numeric default).
        GraphNode a = node("a");
        GraphNode b = node("b");
        GraphData g = new GraphData(List.of(a, b), List.of(
                rel("e1", a, b, Map.of("weight", 10.0))   // intra
        ));
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#aaa");
        colors.put("b", "#aaa");
        List<Map<String, Object>> out = CommunityAggregator.buildRootElements(g, colors);
        Map<?, ?> node = findCommunity(out, "#aaa");
        assertEquals(0.0,
                ((Number) node.get(CommunityAggregator.FIELD_INCOMING_WEIGHT_SUM)).doubleValue(),
                1e-9, "no inter-community edges => incomingWeightSum must be 0.0");
    }
}
