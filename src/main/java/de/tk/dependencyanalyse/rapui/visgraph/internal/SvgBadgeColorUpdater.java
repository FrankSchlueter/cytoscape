package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.config.TagProperty;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-logic helper that computes the new background color for SVG badge
 * nodes under a new {@link NodeConfig} and produces the list of
 * {@code {id, image}} updates the Cytoscape bridge must ship to the
 * iframe so badges are recolored without a full data reload.
 *
 * <p>Extracted from {@link CytoscapeJsBridge} so it can be unit-tested
 * without instantiating a real RAP {@code Browser} widget. The bridge
 * delegates here once per {@code applyNodeConfig} call.</p>
 *
 * <p>Color resolution order (highest priority first):</p>
 * <ol>
 *   <li>{@code config.globalTagColors} — match when ANY of the node's
 *       properties (other than {@code _nodeType_} and known cytoscape
 *       fields) carries a color override for the node's value.</li>
 *   <li>{@code config.tagColors} — same logic but scoped to the node's
 *       primary label.</li>
 *   <li>{@code config.labelColors} — match by {@code nodeType}</li>
 * </ol>
 *
 * <p>Returns an empty list when no node changed color or when there is
 * no SVG-badge node in the graph.</p>
 */
public final class SvgBadgeColorUpdater {

    /**
     * One Cytoscape image-swap entry. {@code id} is the node id,
     * {@code image} is the freshly-rendered {@code data:image/svg+xml}
     * data URI the bridge ships via {@code cgv_applyNodeImages}.
     */
    public static final class ImageUpdate {
        public final String id;
        public final String image;

        public ImageUpdate(String id, String image) {
            this.id = id;
            this.image = image;
        }
    }

    private SvgBadgeColorUpdater() {}

    /**
     * Walk the graph and produce image updates for every node whose
     * SVG-badge background color changed under {@code config}. Mutates
     * each affected node in place via
     * {@link GraphNode#recolorSvgShape(String)} so subsequent
     * serialization reflects the new color.
     *
     * @return the list of {@code {id, image}} entries the bridge must
     *         push to Cytoscape. Never {@code null}.
     */
    public static List<ImageUpdate> applyRecolors(GraphData data, NodeConfig config) {
        return applyRecolors(data, config, /* onlyIfMissing= */ false);
    }

    /**
     * Walk the graph and produce image updates for every node whose
     * SVG-badge background color would resolve differently under
     * {@code config}, regardless of whether it actually changed. Useful
     * for tests that want to verify the resolution order without
     * having to set up the initial color first.
     *
     * <p>Does NOT mutate the nodes — read-only inspection.</p>
     */
    public static List<ImageUpdate> previewRecolors(GraphData data, NodeConfig config) {
        return applyRecolors(data, config, /* onlyIfMissing= */ true);
    }

    private static List<ImageUpdate> applyRecolors(GraphData data, NodeConfig config,
                                                   boolean previewOnly) {
        List<ImageUpdate> updates = new ArrayList<>();
        if (data == null || config == null) return updates;
        for (GraphNode n : data.getNodes()) {
            Map<String, String> svgImage = n.getSvgImage();
            if (svgImage == null) continue;

            String newColor = resolveEffectiveColor(n, config);
            if (newColor == null) continue;
            if (previewOnly) {
                // Emit the update based purely on the resolution logic
                // (no mutation, no diff against the current color).
                String rendered = computeRendered(n, newColor);
                updates.add(new ImageUpdate(n.getId(), rendered));
                continue;
            }
            if (!n.recolorSvgShape(newColor)) continue;
            String rendered = computeRendered(n, newColor);
            updates.add(new ImageUpdate(n.getId(), rendered));
        }
        return updates;
    }

    /**
     * Walk the graph and produce a list of updates suitable for vis-network's
     * {@code DataSet.update}. Each update is one of:
     *
     * <ul>
     *   <li><strong>SVG-badge node</strong> ({@link GraphNode#getSvgImage()} is
     *       non-null): {@code {id, image: "<base64 data URI>"}}. vis-network
     *       swaps the node's image and re-renders with the new color baked
     *       into the SVG.</li>
     *   <li><strong>Plain node</strong> (no svgImage descriptor):
     *       {@code {id, color: {background: "...", border: "..."}}}. vis-network
     *       has no equivalent to Cytoscape's stylesheet selectors, so the
     *       effective color must be pushed onto the node's own
     *       {@code options.color} property.</li>
     * </ul>
     *
     * <p>Mutates SVG-badge nodes in place via
     * {@link GraphNode#recolorSvgShape(String)}; plain nodes are
     * read-only and only the resulting update carries the new color.</p>
     *
     * @return map-shaped update list consumable by Gson + vis-network's
     *         {@code DataSet.update}. Never {@code null}.
     */
    public static List<Map<String, Object>> applyRecolorsBoth(GraphData data, NodeConfig config) {
        List<Map<String, Object>> updates = new ArrayList<>();
        if (data == null || config == null) return updates;
        for (GraphNode n : data.getNodes()) {
            String newColor = resolveEffectiveColor(n, config);
            if (newColor == null) continue;

            Map<String, String> svgImage = n.getSvgImage();
            if (svgImage != null) {
                // SVG-badge node: re-render the SVG and push the new URI.
                if (!n.recolorSvgShape(newColor)) continue;
                String rendered = computeRendered(n, newColor);
                if (rendered == null) continue;
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("id", n.getId());
                upd.put("image", rendered);
                updates.add(upd);
            } else {
                // Plain node: push vis-network's ColorSpec — `color`
                // (NOT `background`!) drives the shape fill. `highlight`
                // and `hover` default to `color` when omitted but we set
                // them explicitly so highlight/hover stay in sync with
                // the new background. vis-network's parseOptions reads
                // `color` from the incoming object and writes it into
                // the node's `options.color.color` — passing
                // `{background: ...}` silently does nothing because
                // vis-network does not understand that key.
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("id", n.getId());
                Map<String, Object> color = new LinkedHashMap<>();
                color.put("color", newColor);
                color.put("highlight", newColor);
                color.put("hover", newColor);
                upd.put("color", color);
                updates.add(upd);
            }
        }
        return updates;
    }

    /**
     * Compute the freshly-rendered Cytoscape data:image URI for a node
     * with the given color. Mirrors the rendering done by
     * {@code GraphNode.resolveCytoscapeImage()} so the bridge can ship
     * the URI Cytoscape actually consumes (base64-encoded).
     */
    private static String computeRendered(GraphNode n, String color) {
        Map<String, Object> ele = n.toCytoscapeNode();
        @SuppressWarnings("unchecked")
        Map<String, Object> eleData = (Map<String, Object>) ele.get("data");
        return (String) eleData.get("image");
    }

    /**
     * Resolve the effective background color for a node under the given
     * {@link NodeConfig}, or {@code null} when no rule applies.
     */
    static String resolveEffectiveColor(GraphNode n, NodeConfig config) {
        Map<String, Object> props = n.getProperties();

        // 1) globalTagColors: node[prop = "value"] — first matching property
        //    wins. Skips known cytoscape-internal fields that should never
        //    participate in tag matching.
        for (Map.Entry<String, Map<String, String>> propEntry
                : config.getGlobalTagColors().entrySet()) {
            String prop = propEntry.getKey();
            if (isInternalProp(prop)) continue;
            Object rawValue = props.get(prop);
            if (rawValue == null) continue;
            String value = String.valueOf(rawValue);
            String c = propEntry.getValue().get(value);
            if (c != null) return c;
        }

        // 2) tagColors: per-label map of (property -> TagProperty with
        //    valueColors). Matches node[nodeType = "X"][prop = "value"].
        String nodeType = primaryNodeType(n);
        if (nodeType != null) {
            Map<String, TagProperty> perLabel = config.getTagColors().get(nodeType);
            if (perLabel != null) {
                for (Map.Entry<String, TagProperty> e : perLabel.entrySet()) {
                    if (isInternalProp(e.getKey())) continue;
                    Object rawValue = props.get(e.getKey());
                    if (rawValue == null) continue;
                    String c = e.getValue().getValueColors().get(String.valueOf(rawValue));
                    if (c != null) return c;
                }
            }
            // 3) labelColors: node[nodeType = "X"] plain color override.
            String labelColor = config.getLabelColors().get(nodeType);
            if (labelColor != null) return labelColor;
        }
        return null;
    }

    /** Mirror of {@code GraphNode.toCytoscapeNode}'s nodeType resolution. */
    static String primaryNodeType(GraphNode n) {
        Object explicit = n.getProperties().get("_nodeType_");
        if (explicit != null && !String.valueOf(explicit).isEmpty()) {
            return String.valueOf(explicit);
        }
        List<String> labels = n.getLabels();
        if (!labels.isEmpty()) return labels.get(0);
        return null;
    }

    /**
     * Properties that must never participate in tag-based color matching.
     * They are cytoscape-internal / Java-serialization-internal fields
     * whose values are not user-meaningful for the dialog's tag pickers.
     */
    private static boolean isInternalProp(String prop) {
        return "_nodeType_".equals(prop) || "nodeTag".equals(prop) || "id".equals(prop)
                || "label".equals(prop) || "tooltip".equals(prop)
                || "properties".equals(prop);
    }

    /**
     * Convenience for callers (notably the bridge) that want a stable
     * map-shaped view of an update list — Gson consumes LinkedHashMaps
     * in declared order which makes the on-the-wire payload easier to
     * diff against {@code cgv_applyNodeImages}'s expectations.
     */
    public static List<Map<String, Object>> toJsonUpdates(List<ImageUpdate> updates) {
        List<Map<String, Object>> out = new ArrayList<>(updates.size());
        for (ImageUpdate u : updates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id);
            m.put("image", u.image);
            out.add(m);
        }
        return out;
    }
}
