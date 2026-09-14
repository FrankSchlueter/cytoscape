package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.config.TagProperty;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for "effective background color of a node" given
 * a {@link NodeConfig} and the optional Leiden-cluster color map.
 *
 * <p>Used by every viewer bridge (Cytoscape, vis-network, sigma, NVL) so
 * the Java-to-JS color-push path is engine-agnostic. Previously each
 * engine applied its own resolution logic — Cytoscape via stylesheet
 * selectors, vis-network via {@code SvgBadgeColorUpdater}, sigma via a
 * nodeReducer combining {@code currentNodeConfig} and
 * {@code currentLeidenColors}, NVL via nothing (no-op). Each had its own
 * ordering bugs and gaps; this resolver collapses them into one
 * deterministic priority chain.</p>
 *
 * <h2>Resolution priority (highest first)</h2>
 * <ol>
 *   <li>{@code leidenColors[nodeId]} — Leiden cluster color wins when
 *       present, so "Apply Leiden Clustering" overrides everything below.</li>
 *   <li>{@code config.globalTagColors} — first matching property (any
 *       non-internal key) where the node carries a value that has a
 *       color override.</li>
 *   <li>{@code config.tagColors[nodeType]} — same logic but scoped to the
 *       node's primary label.</li>
 *   <li>{@code config.labelColors[nodeType]} — fallback per-type color.</li>
 * </ol>
 *
 * <p>If no rule matches, the node is omitted from the result map. Engines
 * then keep their previous color (typically the default or the
 * {@code visualAttrs.color} baked in during data load).</p>
 */
public final class NodeColorResolver {

    /**
     * Properties that must never participate in tag-based color matching.
     * They are cytoscape-internal / Java-serialization-internal fields
     * whose values are not user-meaningful for the dialog's tag pickers.
     * Mirrors {@link SvgBadgeColorUpdater} so the resolution logic stays
     * consistent between the image-swap path and the per-node color path.
     */
    static final java.util.Set<String> INTERNAL_PROPS = java.util.Set.of(
            "_nodeType_", "nodeTag", "id", "label", "tooltip", "properties");

    private NodeColorResolver() {}

    /**
     * Compute the effective per-node background color for the given graph
     * under {@code config} and {@code leidenColors}.
     *
     * @param data         graph whose nodes should be walked. {@code null}
     *                     or empty yields an empty map.
     * @param config       runtime node-config (tag/label colors). When
     *                     {@code null} only the Leiden map is consulted.
     * @param leidenColors id → hex map from the most recent
     *                     {@code LeidenColors.compute(...)} run. When
     *                     {@code null} or empty, the Leiden source is
     *                     skipped (other sources still apply).
     * @return linked map of {@code nodeId → hexColor} for every node that
     *         matches at least one rule. Nodes that match no rule are
     *         omitted; engines should leave their existing color in place.
     */
    public static Map<String, String> resolveEffectiveColors(GraphData data,
                                                               NodeConfig config,
                                                               Map<String, String> leidenColors) {
        Map<String, String> out = new LinkedHashMap<>();
        if (data == null) return out;
        Map<String, String> leiden = leidenColors == null ? Map.of() : leidenColors;
        for (GraphNode n : data.getNodes()) {
            String id = n.getId();
            if (id == null) continue;
            String color = resolveOne(n, config, leiden);
            if (color != null) out.put(id, color);
        }
        return out;
    }

    /**
     * Compute the effective color for a single node. Public so callers
     * that need to drive an engine's per-node update for one node (e.g.
     * a future "color picker on hover") don't have to rebuild the full
     * map.
     */
    public static String resolveOne(GraphNode n, NodeConfig config,
                                      Map<String, String> leidenColors) {
        if (n == null) return null;

        // 1) Leiden wins.
        Map<String, String> leiden = leidenColors == null ? Map.of() : leidenColors;
        String id = n.getId();
        if (id != null) {
            String lc = leiden.get(id);
            if (lc != null && !lc.isEmpty()) return lc;
        }

        // 2/3/4) NodeConfig-based rules. Skip entirely when no config.
        if (config == null) return null;

        // 2) Global tag colors: any property whose value matches a rule.
        Map<String, Object> props = n.getProperties();
        for (Map.Entry<String, Map<String, String>> e
                : config.getGlobalTagColors().entrySet()) {
            String prop = e.getKey();
            if (INTERNAL_PROPS.contains(prop)) continue;
            Object raw = props.get(prop);
            if (raw == null) continue;
            String c = e.getValue().get(String.valueOf(raw));
            if (c != null && !c.isEmpty()) return c;
        }

        // 3) Per-label tag colors scoped to the node's primary nodeType.
        String nodeType = primaryNodeType(n);
        if (nodeType != null) {
            Map<String, TagProperty> perLabel = config.getTagColors().get(nodeType);
            if (perLabel != null) {
                for (Map.Entry<String, TagProperty> e : perLabel.entrySet()) {
                    String prop = e.getKey();
                    if (INTERNAL_PROPS.contains(prop)) continue;
                    Object raw = props.get(prop);
                    if (raw == null) continue;
                    String c = e.getValue().getValueColors().get(String.valueOf(raw));
                    if (c != null && !c.isEmpty()) return c;
                }
            }

            // 4) Plain label color.
            String labelColor = config.getLabelColors().get(nodeType);
            if (labelColor != null && !labelColor.isEmpty()) return labelColor;
        }
        return null;
    }

    /**
     * Mirror of {@code GraphNode.toCytoscapeNode}'s nodeType resolution:
     * explicit {@code _nodeType_} property wins, otherwise fall back to
     * the first label, otherwise {@code null}.
     */
    static String primaryNodeType(GraphNode n) {
        Object explicit = n.getProperties().get("_nodeType_");
        if (explicit != null && !String.valueOf(explicit).isEmpty()) {
            return String.valueOf(explicit);
        }
        List<String> labels = n.getLabels();
        if (!labels.isEmpty()) return labels.get(0);
        return null;
    }
}
