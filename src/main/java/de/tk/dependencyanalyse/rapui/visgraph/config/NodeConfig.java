package de.tk.dependencyanalyse.rapui.visgraph.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime configuration for node visualization.
 *
 * <p>Held by the {@code GraphViewer} and consulted during
 * {@code GraphData.toVisNetworkData()} serialization.</p>
 *
 * <p>Immutable; use the {@link Builder} to construct instances and the
 * {@code withX} methods to derive modified copies.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code showTitle} — when {@code false}, the vis-network {@code label}
 *       field is omitted on every node (the on-node text disappears)</li>
 *   <li>{@code labelColors} — color per node label; applied as the node
 *       background when no tag-value color overrides it</li>
 *   <li>{@code labelShapes} — Cytoscape.js shape name per node label;
 *       applied via the style selector mapping in the JS bridge</li>
 *   <li>{@code tagColors} — per (label, property) the color for each
 *       property value; takes precedence over the label color when matched</li>
 *   <li>{@code globalTagColors} — per property, the color for each value;
 *       applied unconditionally via the Cytoscape selector
 *       {@code node[property = "value"]}. Used by the
 *       {@code GraphConfigurationDialog} "Apply Tag Colors" button when the
 *       selected tag property (e.g. {@code product}) is a global tag that
 *       should color every node whose value matches — independent of the
 *       node's primary label.</li>
 *   <li>{@code labelNodeCounts} — informational: number of nodes per label</li>
 * </ul>
 */
public final class NodeConfig {

    private final boolean showTitle;
    private final Map<String, String> labelColors;
    private final Map<String, String> labelShapes;
    private final Map<String, Map<String, TagProperty>> tagColors;
    private final Map<String, Map<String, String>> globalTagColors;
    private final Map<String, Integer> labelNodeCounts;

    private NodeConfig(Builder b) {
        this.showTitle = b.showTitle;
        this.labelColors = Collections.unmodifiableMap(new LinkedHashMap<>(b.labelColors));
        this.labelShapes = Collections.unmodifiableMap(new LinkedHashMap<>(b.labelShapes));
        Map<String, Map<String, TagProperty>> tagsCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, TagProperty>> e : b.tagColors.entrySet()) {
            tagsCopy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        this.tagColors = Collections.unmodifiableMap(tagsCopy);
        Map<String, Map<String, String>> globalCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : b.globalTagColors.entrySet()) {
            globalCopy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        this.globalTagColors = Collections.unmodifiableMap(globalCopy);
        this.labelNodeCounts = Collections.unmodifiableMap(new LinkedHashMap<>(b.labelNodeCounts));
    }

    public boolean isShowTitle() { return showTitle; }

    public Map<String, String> getLabelColors() { return labelColors; }

    public Map<String, String> getLabelShapes() { return labelShapes; }

    public Map<String, Map<String, TagProperty>> getTagColors() { return tagColors; }

    /** Per-property value-color map applied unconditionally via Cytoscape. */
    public Map<String, Map<String, String>> getGlobalTagColors() { return globalTagColors; }

    public Map<String, Integer> getLabelNodeCounts() { return labelNodeCounts; }

    /** Returns the color for a node label, or {@code null} if not configured. */
    public String colorForLabel(String label) {
        return label == null ? null : labelColors.get(label);
    }

    /** Returns the Cytoscape.js shape name for a node label, or {@code null}. */
    public String shapeForLabel(String label) {
        return label == null ? null : labelShapes.get(label);
    }

    /** Returns the color configured for a (label, property, value) tuple, or {@code null}. */
    public String colorForTagValue(String label, String property, String value) {
        if (label == null || property == null || value == null) return null;
        Map<String, TagProperty> byProp = tagColors.get(label);
        if (byProp == null) return null;
        TagProperty tp = byProp.get(property);
        if (tp == null) return null;
        return tp.getValueColors().get(value);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public NodeConfig withShowTitle(boolean showTitle) {
        return toBuilder().showTitle(showTitle).build();
    }

    public NodeConfig withLabelColor(String label, String color) {
        return toBuilder().labelColor(label, color).build();
    }

    public NodeConfig withLabelShape(String label, String shape) {
        return toBuilder().labelShape(label, shape).build();
    }

    public NodeConfig withTagValueColor(String label, String property, String value, String color) {
        return toBuilder().tagValueColor(label, property, value, color).build();
    }

    public NodeConfig withGlobalTagValueColor(String property, String value, String color) {
        return toBuilder().globalTagValueColor(property, value, color).build();
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Default config with {@code showTitle=true} and empty maps; used when
     * the caller passes {@code null} to {@code GraphViewer.setNodeConfig}.
     */
    public static NodeConfig defaults() {
        return new Builder().showTitle(true).build();
    }

    @Override
    public String toString() {
        return "NodeConfig[showTitle=" + showTitle
                + ", labels=" + labelColors.keySet()
                + ", tags=" + tagColors.size()
                + "]";
    }

    public static final class Builder {
        private boolean showTitle = true;
        private final Map<String, String> labelColors = new LinkedHashMap<>();
        private final Map<String, String> labelShapes = new LinkedHashMap<>();
        private final Map<String, Map<String, TagProperty>> tagColors = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> globalTagColors = new LinkedHashMap<>();
        private final Map<String, Integer> labelNodeCounts = new LinkedHashMap<>();

        Builder() {}

        Builder(NodeConfig src) {
            this.showTitle = src.showTitle;
            this.labelColors.putAll(src.labelColors);
            this.labelShapes.putAll(src.labelShapes);
            src.tagColors.forEach((l, byProp) -> {
                Map<String, TagProperty> inner = new LinkedHashMap<>(byProp);
                this.tagColors.put(l, inner);
            });
            src.globalTagColors.forEach((prop, byValue) ->
                    this.globalTagColors.put(prop, new LinkedHashMap<>(byValue)));
            this.labelNodeCounts.putAll(src.labelNodeCounts);
        }

        public Builder showTitle(boolean v) { this.showTitle = v; return this; }

        public Builder labelColor(String label, String color) {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(color, "color");
            this.labelColors.put(label, color);
            return this;
        }

        public Builder labelColors(Map<String, String> colors) {
            if (colors != null) this.labelColors.putAll(colors);
            return this;
        }

        public Builder labelShape(String label, String cytoscapeShapeName) {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(cytoscapeShapeName, "cytoscapeShapeName");
            this.labelShapes.put(label, cytoscapeShapeName);
            return this;
        }

        public Builder labelShapes(Map<String, String> shapes) {
            if (shapes != null) this.labelShapes.putAll(shapes);
            return this;
        }

        public Builder labelNodeCount(String label, int count) {
            this.labelNodeCounts.put(Objects.requireNonNull(label, "label"), count);
            return this;
        }

        public Builder tagProperty(TagProperty tp) {
            Objects.requireNonNull(tp, "tagProperty");
            this.tagColors
                    .computeIfAbsent(tp.getLabel(), k -> new LinkedHashMap<>())
                    .put(tp.getProperty(), tp);
            return this;
        }

        public Builder tagValueColor(String label, String property, String value, String color) {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(color, "color");
            Map<String, TagProperty> byProp = this.tagColors
                    .computeIfAbsent(label, k -> new LinkedHashMap<>());
            TagProperty existing = byProp.get(property);
            TagProperty next = (existing == null)
                    ? new TagProperty(label, property, Map.of(value, color))
                    : existing.withValueColor(value, color);
            byProp.put(property, next);
            return this;
        }

        public Builder tagColors(Map<String, Map<String, TagProperty>> tags) {
            if (tags != null) {
                tags.forEach((l, byProp) -> byProp.forEach((p, tp) -> tagProperty(tp)));
            }
            return this;
        }

        /** Replace the entire global tag-color map. */
        public Builder globalTagColors(Map<String, Map<String, String>> map) {
            this.globalTagColors.clear();
            if (map != null) {
                map.forEach((prop, byValue) -> this.globalTagColors.put(prop, new LinkedHashMap<>(byValue)));
            }
            return this;
        }

        /** Set / replace a single global tag value color. */
        public Builder globalTagValueColor(String property, String value, String color) {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(color, "color");
            this.globalTagColors
                    .computeIfAbsent(property, k -> new LinkedHashMap<>())
                    .put(value, color);
            return this;
        }

        public NodeConfig build() { return new NodeConfig(this); }
    }
}