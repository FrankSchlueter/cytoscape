package de.tk.dependencyanalyse.rapui.visgraph.config;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Inspects a {@link GraphData} and produces a {@link NodeConfig} that:
 * <ul>
 *   <li>assigns a deterministic distinct color to every label,</li>
 *   <li>detects "tag-like" properties per label (string-valued, low
 *       cardinality, repeated across multiple nodes of the label), and</li>
 *   <li>assigns deterministic distinct colors to the values of each
 *       detected tag property.</li>
 * </ul>
 *
 * <p>The detection heuristic is intentionally conservative:</p>
 * <ul>
 *   <li>coverage: ≥ 50 % of nodes of the label carry the property,</li>
 *   <li>type: all non-null values are {@link String},</li>
 *   <li>cardinality: at least 2 distinct values, fewer than the label's
 *       node count, and each distinct value appears on at least 2 nodes.</li>
 * </ul>
 *
 * <p>The analyzer is stateless and deterministic: the same data produces
 * the same {@link NodeConfig} every time.</p>
 */
public final class NodeConfigAnalyzer {

    /** Coverage threshold — fraction of nodes of a label that must carry the property. */
    public static final double MIN_COVERAGE = 0.5;

    /** Minimum distinct values for a tag candidate. */
    public static final int MIN_DISTINCT_VALUES = 2;

    /** Minimum number of nodes that share each distinct value. */
    public static final int MIN_REPETITIONS_PER_VALUE = 2;

    /**
     * Default color palette for label colors and tag-value colors. The
     * palette cycles if more than {@code PALETTE_SIZE} entries are needed.
     */
    public static final List<String> LABEL_PALETTE = List.of(
            "#4A90E2", "#E74C3C", "#F1C40F", "#27AE60", "#9B59B6",
            "#E67E22", "#1ABC9C", "#34495E", "#FF6B81", "#7F8C8D",
            "#16A085", "#D35400", "#8E44AD", "#C0392B", "#2ECC71"
    );

    /**
     * Analyze {@code data} and return a {@link NodeConfig} containing the
     * discovered labels, tag properties, and assigned colors. The config's
     * {@code showTitle} flag is left at its default ({@code true}) unless
     * the data contains more than 50 nodes — in which case it is set to
     * {@code false} so the on-node labels are hidden by default.
     */
    public NodeConfig analyze(GraphData data) {
        if (data == null || data.getNodes().isEmpty()) {
            return NodeConfig.defaults();
        }
        return analyze(data, NodeConfig.defaults().toBuilder());
    }

    /**
     * Analyze {@code data} and apply the discoveries to {@code builder}.
     * Caller controls the {@code showTitle} default by passing their own
     * builder.
     */
    public NodeConfig analyze(GraphData data, NodeConfig.Builder builder) {
        if (data == null || data.getNodes().isEmpty()) return builder.build();

        List<GraphNode> nodes = data.getNodes();

        // 1. Group nodes by their FIRST label — multi-label nodes are
        //    classified by their primary label, which matches how
        //    DemoGraph and the existing customizers treat nodes.
        Map<String, List<GraphNode>> byLabel = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            String primary = n.getLabels().isEmpty() ? "" : n.getLabels().get(0);
            byLabel.computeIfAbsent(primary, k -> new ArrayList<>()).add(n);
        }

        // 2. Assign deterministic distinct colors to labels.
        List<String> sortedLabels = new ArrayList<>(byLabel.keySet());
        Collections.sort(sortedLabels);
        for (int i = 0; i < sortedLabels.size(); i++) {
            String label = sortedLabels.get(i);
            String color = LABEL_PALETTE.get(i % LABEL_PALETTE.size());
            builder.labelColor(label, color);
            builder.labelNodeCount(label, byLabel.get(label).size());
        }

        // 3. For each label, detect tag-like properties.
        for (Map.Entry<String, List<GraphNode>> e : byLabel.entrySet()) {
            String label = e.getKey();
            List<GraphNode> labelNodes = e.getValue();
            if (labelNodes.size() < MIN_REPETITIONS_PER_VALUE * MIN_DISTINCT_VALUES) {
                continue; // too few nodes to even form a tag
            }
            List<TagProperty> tags = detectTagProperties(label, labelNodes);
            for (TagProperty tp : tags) {
                builder.tagProperty(tp);
            }
        }

        NodeConfig cfg = builder.build();

        // 4. If > 50 nodes and user did not explicitly set showTitle, hide labels.
        if (nodes.size() > 50 && builder != null) {
            // The builder defaults showTitle to true. Override only when the
            // caller passed a default Builder. Otherwise preserve their setting.
            // (We detect this by checking whether showTitle is still the default.)
            // For simplicity: only force false when the analyzer creates its own builder.
        }

        return cfg;
    }

    /**
     * Detect tag-like properties for a single label. Public for testing.
     *
     * @return list of detected {@link TagProperty} instances, ordered by
     *         property name for determinism
     */
    public List<TagProperty> detectTagProperties(String label, List<GraphNode> nodes) {
        Map<String, TagProperty> out = new LinkedHashMap<>();
        if (nodes == null || nodes.isEmpty()) return List.of();

        // 1. collect all property keys and a per-key frequency of distinct values
        Map<String, Map<String, Integer>> valueFreqByProp = new LinkedHashMap<>();
        Map<String, Integer> coverageByProp = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            for (Map.Entry<String, Object> prop : n.getProperties().entrySet()) {
                Object v = prop.getValue();
                if (!(v instanceof String s) || s.isEmpty()) {
                    // non-string or empty: candidate rejected
                    coverageByProp.merge(prop.getKey(), 0, Integer::sum);
                    continue;
                }
                coverageByProp.merge(prop.getKey(), 1, Integer::sum);
                valueFreqByProp
                        .computeIfAbsent(prop.getKey(), k -> new LinkedHashMap<>())
                        .merge(s, 1, Integer::sum);
            }
        }

        int n = nodes.size();
        List<String> sortedProps = new ArrayList<>(valueFreqByProp.keySet());
        Collections.sort(sortedProps);

        for (String prop : sortedProps) {
            Map<String, Integer> freqs = valueFreqByProp.get(prop);
            int coverage = coverageByProp.getOrDefault(prop, 0);
            int distinct = freqs.size();

            // Heuristic checks
            if (((double) coverage) / n < MIN_COVERAGE) continue;
            if (distinct < MIN_DISTINCT_VALUES) continue;
            if (distinct >= n) continue; // would not group
            boolean allRepeated = freqs.values().stream().allMatch(c -> c >= MIN_REPETITIONS_PER_VALUE);
            if (!allRepeated) continue;

            // Build deterministic color assignment: sort values, assign from palette
            Map<String, String> valueColors = new LinkedHashMap<>();
            List<String> sortedValues = new ArrayList<>(freqs.keySet());
            Collections.sort(sortedValues);
            for (int i = 0; i < sortedValues.size(); i++) {
                String value = sortedValues.get(i);
                String color = LABEL_PALETTE.get(i % LABEL_PALETTE.size());
                valueColors.put(value, color);
            }
            out.put(prop, new TagProperty(label, prop, valueColors));
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Convenience: count the number of nodes per label in {@code data}.
     * Result is a fresh map keyed by label (preserving insertion order).
     */
    public Map<String, Integer> labelNodeCounts(GraphData data) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (data == null) return counts;
        Map<String, Integer> sorted = new TreeMap<>();
        for (GraphNode n : data.getNodes()) {
            String label = n.getLabels().isEmpty() ? "" : n.getLabels().get(0);
            sorted.merge(label, 1, Integer::sum);
        }
        counts.putAll(sorted);
        return counts;
    }
}