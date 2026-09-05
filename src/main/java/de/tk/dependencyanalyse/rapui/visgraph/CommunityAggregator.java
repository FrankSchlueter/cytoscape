package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-data builder for the optional "Community Aggregation" view in the
 * graph viewer.
 *
 * <p>When a Leiden/Louvain-style clustering has been applied, the graph
 * has a {@code nodeId -> hexColor} map and the user can collapse the
 * canvas to <b>one node per community</b> with <b>one aggregated edge per
 * inter-community pair</b>. The aggregated inter-edges combine all
 * individual edges between the two communities (bidirectional A -> B and
 * B -> A are merged into a single edge whose {@code weight} is the sum of
 * all individual weights and whose {@code edgeCount} counts how many
 * individual edges were folded together).</p>
 *
 * <p>Two main entry points:</p>
 * <ul>
 *   <li>{@link #buildRootElements(GraphData, Map)} - the aggregated root
 *       view: one element per community plus one element per inter-community
 *       edge pair.</li>
 *   <li>{@link #buildCommunityDetailElements(GraphData, Map, String)} -
 *       the drill-down view for a single community: original member-nodes
 *       plus all intra-community edges. Other communities are NOT
 *       included; the user sees an isolated view of the chosen community.</li>
 * </ul>
 *
 * <p>All methods are pure functions of {@code (data, colors)} and are
 * testable without an SWT shell or a running Cytoscape instance.</p>
 *
 * <p>Output is deterministic for a fixed input graph and color map
 * (communities are numbered in iteration order over {@code colors};
 * inter-edge ids are derived from the ordered color pair).</p>
 */
public final class CommunityAggregator {

    /** Prefix for community-node ids in the aggregated view. */
    public static final String COMMUNITY_ID_PREFIX = "community_";

    /** Prefix for aggregated inter-community edge ids. */
    public static final String INTER_EDGE_ID_PREFIX = "inter_";

    /**
     * Infix separating source and target in the deterministic inter-edge
     * id (e.g. {@code inter_<srcColor>_to_<dstColor>}). The directional
     * form ensures {@code A->B} and {@code B->A} never collide.
     */
    public static final String INTER_EDGE_INFIX = "_to_";

    /** Data flag marking an aggregated community-node in the root view. */
    public static final String IS_COMMUNITY = "isCommunity";

    /** Data flag marking an aggregated inter-community edge. */
    public static final String IS_COMMUNITY_EDGE = "isCommunityEdge";

    /** Data field holding the Leiden colour of the source-community of an aggregated edge. */
    public static final String FIELD_SOURCE_COMMUNITY_COLOR = "sourceCommunityColor";

    /** Data field holding the Leiden colour of the target-community of an aggregated edge. */
    public static final String FIELD_TARGET_COMMUNITY_COLOR = "targetCommunityColor";

    /** Data field holding the human-readable label of the source-community (e.g. "Cluster 1"). */
    public static final String FIELD_SOURCE_LABEL = "sourceLabel";

    /** Data field holding the human-readable label of the target-community (e.g. "Cluster 2"). */
    public static final String FIELD_TARGET_LABEL = "targetLabel";

    /** Data field holding the Cytoscape-native tooltip text. */
    public static final String FIELD_TOOLTIP = "tooltip";

    /** Data field holding the Leiden colour of a community (node). */
    public static final String FIELD_COMMUNITY_COLOR = "communityColor";

    /** Data field holding the count of member nodes in a community. */
    public static final String FIELD_MEMBER_COUNT = "memberCount";

    /** Data field holding the number of individual edges folded into one (per direction). */
    public static final String FIELD_EDGE_COUNT = "edgeCount";

    /**
     * Data field holding the list of original {@link GraphNode#getId() ids}
     * of the nodes that belong to this community. Empty for orphan
     * communities with no assigned member nodes. The cytoscape bridge
     * joins this list with the {@code GraphNode}'s display name /
     * caption to render a "Cluster members" tooltip on the aggregated
     * community-node.
     */
    public static final String FIELD_MEMBER_IDS = "memberIds";

    /**
     * Data field holding the list of original {@link GraphRelationship#getId() ids}
     * of the individual edges that were folded into this aggregated edge. Empty
     * for direct (single-edge) aggregations. The cytoscape bridge consumes this
     * list so a row-click on the "edges-of-community" table can map back to the
     * real Java relationship for the {@code RelationshipSelectionListener}.
     */
    public static final String FIELD_MEMBER_EDGE_IDS = "memberEdgeIds";

    /** Data field holding the original (pre-aggregation) colour for legend lookup. */
    public static final String FIELD_ORIGINAL_COLOR = "originalColor";

    /**
     * Data field holding the sum of weights of all inter-community edges
     * whose <b>target</b> is this community. Drives the visual size of
     * the community-node so that the user can spot "load sinks" at a glance.
     */
    public static final String FIELD_INCOMING_WEIGHT_SUM = "incomingWeightSum";

    private CommunityAggregator() {}

    /**
     * Deterministic community-index map: colour -> 0-based index.
     *
     * <p>Iteration order is the {@code colors} keySet order (a
     * {@code LinkedHashMap} on the Java side). Callers that want a
     * different order (e.g. by community size descending) can build their
     * own {@code colors} map.</p>
     *
     * @return an unmodifiable map keyed by hex colour.
     */
    public static Map<String, Integer> communityIndexMap(Map<String, String> colors) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (colors == null) return out;
        int i = 0;
        for (String c : colors.values()) {
            if (c == null) continue;
            if (out.containsKey(c)) continue;
            out.put(c, i++);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Build the deterministic id for a community-node given its 0-based index. */
    public static String communityNodeId(int idx) {
        return COMMUNITY_ID_PREFIX + idx;
    }

    /**
     * Build the deterministic id for a <b>directional</b> inter-community edge.
     * The id is asymmetric: {@code A->B} and {@code B->A} produce different
     * ids so the aggregated view keeps both directions as separate edges
     * (rendered as bezier curves that do not overlap).
     */
    public static String interEdgeId(String sourceColor, String targetColor) {
        return INTER_EDGE_ID_PREFIX + canon(sourceColor) + INTER_EDGE_INFIX + canon(targetColor);
    }

    /**
     * Build the aggregated root view: one element per Leiden community plus
     * one element per inter-community edge pair.
     *
     * <p>Intra-community edges are NOT included in the root view (they
     * live inside the detail view). Nodes that do not appear in
     * {@code colors} (orphans) are reported under a single
     * {@code "_orphan_"} community only when the user explicitly opted in
     * via a non-null colors map; otherwise they are silently dropped so
     * the aggregated view stays focused on communities.</p>
     *
     * @param data   the graph
     * @param colors Leiden colour map from {@link LeidenColors#compute(GraphData)}
     * @return a flat Cytoscape elements array (nodes + edges). Empty when
     *         {@code data} or {@code colors} is null/empty.
     */
    public static List<Map<String, Object>> buildRootElements(
            GraphData data, Map<String, String> colors) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (data == null || colors == null || colors.isEmpty()) return out;

        // ---- community-nodes: one element per distinct Leiden colour ----
        // We sort communities by member-count descending so the largest
        // community lands in the upper-left of the preset grid and reads
        // as Cluster1 in the legend (matches LegendBuilder.fromLeidenClusters).
        Map<String, Integer> memberCount = new LinkedHashMap<>();
        for (String nodeId : colors.keySet()) {
            String c = colors.get(nodeId);
            if (c == null) continue;
            memberCount.merge(c, 1, Integer::sum);
        }
        // Sort by member-count desc, then colour asc as a stable tie-breaker.
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(memberCount.entrySet());
        sorted.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            if (byCount != 0) return byCount;
            return canon(a.getKey()).compareTo(canon(b.getKey()));
        });
        // Build a fresh idxByColor (lowercase canon keys) that reflects
        // the sorted order so community_<i> ids are stable across re-runs.
        // Keys are normalised to lowercase so the AggregateBucket colour
        // lookup (`sortedIdx.get(b.sourceColor)` where the colour is canon'd) hits.
        Map<String, Integer> sortedIdx = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            sortedIdx.put(canon(sorted.get(i).getKey()), i);
        }

        // Two-pass aggregation: pass 1 collects inter-edge buckets and
        // accumulates incoming-weight-sums; pass 2 emits community-nodes
        // (with their incomingWeightSum + memberIds populated) and
        // directional edges (with source/target labels + colour + tooltip).
        Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
        Map<String, Double> incomingWeightSum = new LinkedHashMap<>();
        for (GraphRelationship r : data.getRelationships()) {
            String cSrc = colors.get(r.getSourceId());
            String cDst = colors.get(r.getTargetId());
            if (cSrc == null || cDst == null) continue;
            if (Objects.equals(cSrc, cDst)) continue; // intra-community
            double w = readWeight(r);
            // Directional bucket: A->B and B->A stay separate.
            String key = interEdgeId(cSrc, cDst);
            AggregateBucket b = buckets.computeIfAbsent(key,
                    k -> new AggregateBucket(cSrc, cDst));
            b.weightSum += w;
            b.edgeCount += 1;
            b.memberEdgeIds.add(r.getId());
            // incomingWeightSum: only count inter-edges whose TARGET is
            // this community. The user reads a community's size as "how
            // much weight is incoming into it".
            incomingWeightSum.merge(cDst, w, Double::sum);
        }

        // Build a color → [memberIds] map so the cytoscape bridge can
        // surface a "members of this cluster" tooltip on the aggregated
        // community-node. Iteration order is the original graph-node
        // insertion order so the list is stable across re-runs.
        Map<String, List<String>> membersByColor = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : colors.entrySet()) {
            String c = e.getValue();
            if (c == null) continue;
            membersByColor.computeIfAbsent(c, k -> new ArrayList<>())
                    .add(e.getKey());
        }

        for (Map.Entry<String, Integer> entry : sorted) {
            String color = entry.getKey();
            int idx = sortedIdx.get(canon(color));
            int count = entry.getValue();
            double inSum = incomingWeightSum.getOrDefault(color, 0.0);
            List<String> memberIds = membersByColor.getOrDefault(color, List.of());
            Map<String, Object> data1 = new LinkedHashMap<>();
            data1.put("id", communityNodeId(idx));
            data1.put(IS_COMMUNITY, true);
            // Per user spec: the cluster-node label is the short form
            // "C1", "C2", ... so the visual reads as a compact cluster
            // identifier. The longer "Cluster N" wording is still used
            // in the tooltip header (see buildCommunityNodeTooltip in
            // cytoscape-viewer.js) and in the source/target labels of
            // aggregated edges. The member count still lives in
            // data.memberCount for callers that need it (legend, table
            // builders, status text).
            data1.put("label", "C" + (idx + 1));
            data1.put(FIELD_COMMUNITY_COLOR, color);
            data1.put(FIELD_MEMBER_COUNT, count);
            data1.put(FIELD_ORIGINAL_COLOR, color);
            data1.put(FIELD_INCOMING_WEIGHT_SUM, inSum);
            // Member-node ids, in original graph-iteration order. The
            // cytoscape bridge joins this with the GraphNode 'name' /
            // 'caption' to render a "Cluster members" tooltip.
            data1.put(FIELD_MEMBER_IDS, new ArrayList<>(memberIds));
            // Cytoscape compound-parent safeguard: aggregated nodes are
            // root-level (no parent), in contrast to the compound-parent
            // strategy where every member node points to its cluster_*.
            Map<String, Object> elem = new LinkedHashMap<>();
            elem.put("data", data1);
            out.add(elem);
        }

        // ---- inter-community edges: one element per ORDERED colour pair ----
        // A->B and B->A are TWO separate edges. Cytoscape's bezier
        // curve-style with a generous control-point-step-size keeps them
        // visually separated (parallel bezier "cables" instead of one
        // straight line). The aggregated weight is the SUM of all
        // individual weights in that direction.
        for (Map.Entry<String, AggregateBucket> e : buckets.entrySet()) {
            AggregateBucket b = e.getValue();
            int idxA = sortedIdx.get(b.sourceColor);
            int idxB = sortedIdx.get(b.targetColor);
            String sourceLabel = "Cluster " + (idxA + 1);
            String targetLabel = "Cluster " + (idxB + 1);
            double totalWeight = b.weightSum;
            double logWeight = Math.log(totalWeight + 1.0);
            Map<String, Object> ed = new LinkedHashMap<>();
            ed.put("id", e.getKey());
            ed.put("source", communityNodeId(idxA));
            ed.put("target", communityNodeId(idxB));
            ed.put(IS_COMMUNITY_EDGE, true);
            ed.put(FIELD_SOURCE_COMMUNITY_COLOR, b.sourceColor);
            ed.put(FIELD_TARGET_COMMUNITY_COLOR, b.targetColor);
            ed.put(FIELD_SOURCE_LABEL, sourceLabel);
            ed.put(FIELD_TARGET_LABEL, targetLabel);
            ed.put("weight", totalWeight);
            ed.put("logWeight", logWeight);
            ed.put(FIELD_EDGE_COUNT, b.edgeCount);
            // Cytoscape on-canvas label = the summed weight for this
            // direction. Server-side formatter (Java) so the cytoscape
            // string-mapper 'label': 'data(label)' in
            // communityEdgeStyle() reads it directly via fromJson
            // round-trip. The value matches the tooltip's ": <weight>"
            // suffix below — single source of truth, no JS-side
            // re-formatting needed (mirrored by
            // cytoscape-viewer.js formatAggregatedWeight for the table
            // cell so a future Java-side change shows up in both
            // places).
            ed.put("label", formatWeight(totalWeight));
            // Cytoscape-native tooltip ("From -> To: sumWeight" format).
            ed.put(FIELD_TOOLTIP,
                    sourceLabel + " \u2192 " + targetLabel + ": " + formatWeight(totalWeight));
            // Expose the original edge IDs for reference (table rows
            // currently route via the AGGREGATED edge id above; the
            // memberEdgeIds are kept on the data payload in case a
            // future "drill into the relationship" affordance wants
            // them).
            ed.put(FIELD_MEMBER_EDGE_IDS, new ArrayList<>(b.memberEdgeIds));
            Map<String, Object> elem = new LinkedHashMap<>();
            elem.put("data", ed);
            out.add(elem);
        }
        return out;
    }

    /**
     * Build the isolated detail view for a single community.
     *
     * <p>The output contains:</p>
     * <ul>
     *   <li>One element per member-node of the given community (the
     *       original node, with its full Cytoscape data payload from
     *       {@link de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode#toCytoscapeNode()}).</li>
     *   <li>One element per intra-community edge (both endpoints are in
     *       the same community).</li>
     * </ul>
     *
     * <p>Inter-community edges are NOT included — the user sees the
     * community in isolation, without distraction from the outside world.</p>
     *
     * @param data            the graph
     * @param colors          Leiden colour map
     * @param communityColor  the hex colour of the community to drill into.
     *                        Nodes whose {@code colors} entry equals this
     *                        value are the member set.
     * @return flat Cytoscape elements array. Empty when {@code communityColor}
     *         is null/empty or the graph has no members with that colour.
     */
    public static List<Map<String, Object>> buildCommunityDetailElements(
            GraphData data, Map<String, String> colors, String communityColor) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (data == null || colors == null || communityColor == null
                || communityColor.isEmpty()) {
            return out;
        }
        String want = canon(communityColor);
        // Collect member-node ids.
        List<String> memberIds = new ArrayList<>();
        for (Map.Entry<String, String> e : colors.entrySet()) {
            if (want.equals(canon(e.getValue()))) {
                memberIds.add(e.getKey());
            }
        }
        if (memberIds.isEmpty()) return out;
        java.util.Set<String> memberSet = new java.util.HashSet<>(memberIds);
        // Emit member nodes.
        for (String id : memberIds) {
            data.findNode(id).ifPresent(n -> out.add(n.toCytoscapeNode()));
        }
        // Emit intra-community edges.
        for (GraphRelationship r : data.getRelationships()) {
            if (memberSet.contains(r.getSourceId())
                    && memberSet.contains(r.getTargetId())) {
                out.add(r.toCytoscapeEdge());
            }
        }
        return out;
    }

    /* ============================================================== */
    /*  Helpers                                                        */
    /* ============================================================== */

    /** Canonical lowercase form of a hex colour so "{@code #abc}" matches "{@code #ABC}". */
    private static String canon(String hex) {
        return hex == null ? null : hex.trim().toLowerCase(Locale.ROOT);
    }

    /** Read a relationship weight as {@code double}, defaulting to 1.0 for unweighted edges. */
    private static double readWeight(GraphRelationship r) {
        Object pw = r.getProperties().get(GraphRelationship.PROP_WEIGHT);
        if (pw instanceof Number num) return Math.max(1.0, num.doubleValue());
        if (pw != null) {
            try { return Math.max(1.0, Double.parseDouble(pw.toString())); }
            catch (NumberFormatException ignored) {}
        }
        return 1.0;
    }

    /**
     * Format a weight for the edge tooltip. Integer-valued weights render
     * without a decimal point (e.g. {@code 12}, not {@code 12.0}) while
     * fractional values keep one decimal place (e.g. {@code 7.5}).
     */
    static String formatWeight(double weight) {
        if (Double.isNaN(weight) || Double.isInfinite(weight)) {
            return "0";
        }
        if (weight == Math.floor(weight) && !Double.isInfinite(weight)) {
            return Long.toString((long) weight);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", weight);
    }

    /** Mutable accumulator for one DIRECTIONAL inter-community edge
     * (source -> target). The previous (undirected) form merged A->B and
     * B->A into a single edge; the new aggregated view keeps both
     * directions separate so they can be rendered as parallel bezier
     * curves. */
    private static final class AggregateBucket {
        final String sourceColor;
        final String targetColor;
        double weightSum = 0.0;
        int edgeCount = 0;
        /** Ids of the original {@link GraphRelationship}s folded into this
         *  bucket. Surfaced to the cytoscape bridge via
         *  {@link CommunityAggregator#FIELD_MEMBER_EDGE_IDS} so a row-click
         *  on the "edges of community" table can re-fire the real Java
         *  {@code RelationshipSelectionListener}. */
        final java.util.List<String> memberEdgeIds = new java.util.ArrayList<>();
        AggregateBucket(String source, String target) {
            this.sourceColor = canon(source);
            this.targetColor = canon(target);
        }
    }

    /* ============================================================== */
    /*  Static discovery helpers (testable, no SWT)                    */
    /* ============================================================== */

    /** Distinct colours from a Leiden map, in iteration order (used in dialog status text). */
    public static List<String> distinctCommunityColors(Map<String, String> colors) {
        if (colors == null || colors.isEmpty()) return List.of();
        java.util.Set<String> ordered = new java.util.LinkedHashSet<>();
        for (String c : colors.values()) {
            if (c != null) ordered.add(canon(c));
        }
        return new ArrayList<>(ordered);
    }

    /** Count of inter-community edges (post-aggregation, per direction). */
    public static int countAggregatedEdges(GraphData data, Map<String, String> colors) {
        if (data == null || colors == null || colors.isEmpty()) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (GraphRelationship r : data.getRelationships()) {
            String cSrc = colors.get(r.getSourceId());
            String cDst = colors.get(r.getTargetId());
            if (cSrc == null || cDst == null) continue;
            if (Objects.equals(cSrc, cDst)) continue;
            String key = interEdgeId(cSrc, cDst);
            if (seen.add(key)) n++;
        }
        return n;
    }
}
