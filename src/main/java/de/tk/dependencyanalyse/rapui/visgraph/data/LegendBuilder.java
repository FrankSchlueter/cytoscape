package de.tk.dependencyanalyse.rapui.visgraph.data;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure-data builder for the optional color legend.
 *
 * <p>The legend explains to the user what each color in the graph means. It
 * has three independent sources:</p>
 *
 * <ul>
 *   <li>{@link #fromTagValues(GraphData, NodeConfig)} — colors assigned by
 *       the Graph Configuration dialog's "Apply Tag Colors" button. The
 *       entry label is the tag value (or the property:value pair when more
 *       than one tag property is active).</li>
 *   <li>{@link #fromLeidenClusters(GraphData, Map)} — colors assigned by
 *       the Leiden clustering. The entry label is {@code "Cluster1"},
 *       {@code "Cluster2"}, … ordered by <b>community size descending</b>
 *       (largest community is Cluster1).</li>
 *   <li>{@link #fromNodeTypes(GraphData, NodeConfig)} — colors assigned by
 *       the node-type coloring. The entry label is the {@code _nodeType_}
 *       value.</li>
 * </ul>
 *
 * <p>{@link #combined(GraphData, NodeConfig, Map)} walks all three sources
 * in priority order <b>Tag → Cluster → NodeType</b>. When two sources use
 * the same hex color, the higher-priority source wins so the legend never
 * lists a color twice.</p>
 *
 * <p>All methods are null-safe: {@code null} arguments yield an empty list.</p>
 *
 * <p>Output is deterministic for a fixed input graph and config (same
 * iteration order over {@link GraphData#getNodes()}).</p>
 */
public final class LegendBuilder {

    /** Canonical lowercase form of a hex color for use as a dedup key. */
    private static String canon(String hex) {
        return hex == null ? null : hex.trim().toLowerCase(Locale.ROOT);
    }

    private LegendBuilder() {}

    /* ============================================================== */
    /*  Single-source builders                                         */
    /* ============================================================== */

    /**
     * Build a legend from the {@link NodeConfig#getGlobalTagColors()} map.
     *
     * <p>Each (property, value) tuple becomes one entry labeled
     * {@code "property: value"}. The node count is the number of nodes
     * whose property equals the value.</p>
     *
     * <p>If multiple tag properties are configured, they are emitted in the
     * iteration order of {@code NodeConfig.getGlobalTagColors()}.</p>
     */
    public static List<LegendEntry> fromTagValues(GraphData data, NodeConfig config) {
        if (data == null || config == null) return List.of();
        Map<String, Map<String, String>> globals = config.getGlobalTagColors();
        if (globals == null || globals.isEmpty()) return List.of();
        List<LegendEntry> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> prop : globals.entrySet()) {
            String propName = prop.getKey();
            Map<String, String> byValue = prop.getValue();
            if (byValue == null || byValue.isEmpty()) continue;
            for (Map.Entry<String, String> val : byValue.entrySet()) {
                String color = val.getValue();
                if (color == null || color.isEmpty()) continue;
                int count = countNodesWhere(data, propName, val.getKey());
                out.add(new LegendEntry(color, propName + ": " + val.getKey(), count));
            }
        }
        return out;
    }

    /**
     * Build a legend from a Leiden cluster color map.
     *
     * <p>Communities are grouped by color, then sorted descending by node
     * count. The largest community becomes {@code "Cluster1"}, the next
     * largest {@code "Cluster2"}, and so on. Ties are broken by the
     * canonical hex string so the order is stable.</p>
     */
    public static List<LegendEntry> fromLeidenClusters(GraphData data, Map<String, String> leidenColorsById) {
        if (data == null || leidenColorsById == null || leidenColorsById.isEmpty()) return List.of();
        // group nodes by color. We track both the canonical key (for
        // dedup + tie-break) and the FIRST-SEEN original hex so the
        // LegendEntry preserves the caller's capitalization.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> hexByCanon = new LinkedHashMap<>();
        for (GraphNode n : data.getNodes()) {
            String c = leidenColorsById.get(n.getId());
            if (c == null || c.isEmpty()) continue;
            String key = canon(c);
            if (!hexByCanon.containsKey(key)) {
                hexByCanon.put(key, c);
            }
            counts.merge(key, 1, Integer::sum);
        }
        if (counts.isEmpty()) return List.of();
        // sort: count desc, then canonical color (stable tie-break)
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<LegendEntry> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            String hex = hexByCanon.get(e.getKey());
            out.add(new LegendEntry(hex, "Cluster" + (i + 1), e.getValue()));
        }
        return out;
    }

    /**
     * Build a legend from the {@link NodeConfig#getLabelColors()} map.
     *
     * <p>Each {@code _nodeType_} value becomes one entry labeled
     * {@code "_nodeType_"} with the configured color and the node count
     * for that type.</p>
     *
     * <p>The {@code data} argument is used only to count nodes per
     * {@code _nodeType_} value. When the graph has no {@code _nodeType_}
     * property the method falls back to the primary label.</p>
     */
    public static List<LegendEntry> fromNodeTypes(GraphData data, NodeConfig config) {
        if (data == null || config == null) return List.of();
        Map<String, String> labels = config.getLabelColors();
        if (labels == null || labels.isEmpty()) return List.of();
        // Build the type -> count map via the same logic the dialog uses.
        Map<String, Integer> typeCounts = new TreeMap<>();
        for (GraphNode n : data.getNodes()) {
            String type = nodeTypeOf(n);
            if (type != null && !type.isEmpty()) {
                typeCounts.merge(type, 1, Integer::sum);
            }
        }
        List<LegendEntry> out = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String type = e.getKey();
            String color = e.getValue();
            if (color == null || color.isEmpty()) continue;
            out.add(new LegendEntry(color, type, typeCounts.getOrDefault(type, 0)));
        }
        return out;
    }

    /* ============================================================== */
    /*  Combined                                                       */
    /* ============================================================== */

    /**
     * Combined legend: Tag → Cluster → NodeType.
     *
     * <p>Each source contributes entries to the result, but only entries
     * with hex colors that have <b>not yet been emitted</b> by a
     * higher-priority source are kept. This avoids duplicate swatches for
     * the same color (e.g. when a Leiden cluster happens to use the same
     * hex as a NodeType).</p>
     *
     * <p>The output order is:</p>
     * <ol>
     *   <li>Tag entries in {@link NodeConfig#getGlobalTagColors()} order;</li>
     *   <li>Cluster entries in size-descending order, renumbered
     *       sequentially as {@code Cluster1}, {@code Cluster2}, … so that
     *       the labels are gap-free when a higher-priority source claims
     *       a cluster's color;</li>
     *   <li>NodeType entries in alphabetical order.</li>
     * </ol>
     */
    public static List<LegendEntry> combined(GraphData data, NodeConfig config,
                                              Map<String, String> leidenColorsById) {
        List<LegendEntry> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        // Tags
        for (LegendEntry e : fromTagValues(data, config)) {
            if (used.add(canon(e.colorHex()))) {
                out.add(e);
            }
        }
        // Clusters — renumber sequentially so dropped colors don't leave gaps.
        int clusterIdx = 0;
        for (LegendEntry e : fromLeidenClusters(data, leidenColorsById)) {
            if (used.add(canon(e.colorHex()))) {
                clusterIdx++;
                out.add(new LegendEntry(e.colorHex(), "Cluster" + clusterIdx, e.count()));
            }
        }
        // NodeTypes
        for (LegendEntry e : fromNodeTypes(data, config)) {
            if (used.add(canon(e.colorHex()))) {
                out.add(e);
            }
        }
        return out;
    }

    /* ============================================================== */
    /*  Internals                                                       */
    /* ============================================================== */

    private static int countNodesWhere(GraphData data, String property, String value) {
        if (data == null || property == null || value == null) return 0;
        int n = 0;
        for (GraphNode node : data.getNodes()) {
            Object v = node.getProperties().get(property);
            if (v != null && value.equals(String.valueOf(v))) n++;
        }
        return n;
    }

    private static String nodeTypeOf(GraphNode n) {
        Object explicit = n.getProperties().get("_nodeType_");
        if (explicit != null && !String.valueOf(explicit).isEmpty()) {
            return String.valueOf(explicit);
        }
        if (!n.getLabels().isEmpty()) {
            String lbl = n.getLabels().get(0);
            if (lbl != null && !lbl.isEmpty()) return lbl;
        }
        return null;
    }

    /** Stable, sorted representation of a list — used by tests. */
    static List<LegendEntry> sortedByLabel(List<LegendEntry> entries) {
        List<LegendEntry> copy = new ArrayList<>(entries);
        copy.sort((a, b) -> a.label().compareTo(b.label()));
        return Collections.unmodifiableList(copy);
    }

    /** Used by tests to build a small graph in-memory. */
    static GraphData graphOf(List<GraphNode> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        return new GraphData(nodes, List.of());
    }

    /** Used by tests to build a single-node graph with the given id. */
    static GraphNode node(String id, String type, Map<String, Object> props) {
        List<String> labels = type == null ? List.of() : List.of(type);
        Map<String, Object> p = new LinkedHashMap<>(props == null ? Map.of() : props);
        if (type != null) p.put("_nodeType_", type);
        return new GraphNode(id, labels, p);
    }

    /** Test-only: stable iteration order helper. */
    static Set<String> sortedKeys(Map<String, ?> m) {
        Set<String> out = new TreeSet<>();
        if (m != null) out.addAll(m.keySet());
        return out;
    }
}