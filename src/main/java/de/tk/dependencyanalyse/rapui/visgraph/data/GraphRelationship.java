package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Generic graph relationship. Source and target are direct references to
 * the connected {@link GraphNode}s, so a relationship is always
 * consistent with its endpoint graph without further lookup.
 *
 * <p>For convenience and to keep the existing serialization code
 * working, the relationship exposes both {@link #getSource()} / {@link #getTarget()}
 * (typed {@link GraphNode}) and {@link #getSourceId()} / {@link #getTargetId()}
 * (string id), with the string accessors simply delegating to the node's id.</p>
 *
 * <p>Relationships can carry an optional {@code weight} attribute, stored
 * both as a typed primitive field and inside the {@code properties} map.
 * The {@code weight} is a {@code double} so weighted layouts (e.g. fcose
 * with {@code idealEdgeLength(weight)}) can use it directly. Properties
 * remain the source of truth for serialization; the {@code weight} field
 * is a typed convenience accessor that reads/writes the {@code "weight"}
 * property.</p>
 */
public final class GraphRelationship {

    /** Property key for the optional relationship weight. */
    public static final String PROP_WEIGHT = "weight";

    private final String id;
    private final String type;
    private final GraphNode sourceNode;
    private final GraphNode targetNode;
    private final Map<String, Object> properties;
    private final Map<String, Object> visualAttrs = new LinkedHashMap<>();
    private String customTooltip;
    private boolean tooltipOverride = false;

    /**
     * Primary constructor — source and target are full node references.
     * String ids are derived from the nodes so callers downstream do not
     * need to keep their own mapping in sync.
     */
    public GraphRelationship(String id, String type,
                              GraphNode sourceNode, GraphNode targetNode,
                              Map<String, Object> properties) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.sourceNode = Objects.requireNonNull(sourceNode, "sourceNode");
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode");
        this.properties = properties == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(properties);
    }

    /**
     * Legacy constructor kept for backward-compatibility with code that
     * built relationships from raw ids (CSV parser, etc.). Internally it
     * now uses lightweight {@code id}-only {@link GraphNode} adapters so
     * the resulting relationship still exposes a populated
     * {@link #getSource()} / {@link #getTarget()}.
     *
     * @deprecated Use {@link #GraphRelationship(String, String, GraphNode, GraphNode, Map)}
     *             — constructing with bare strings loses the connection
     *             back to the originating nodes.
     */
    @Deprecated
    public GraphRelationship(String id, String type,
                              String sourceId, String targetId,
                              Map<String, Object> properties) {
        this(id, type,
                new GraphNode(sourceId, java.util.List.of(),
                        java.util.Map.of("name", sourceId)),
                new GraphNode(targetId, java.util.List.of(),
                        java.util.Map.of("name", targetId)),
                properties);
    }

    public String getId() { return id; }
    public String getType() { return type; }

    /** Source endpoint of this relationship. Never {@code null}. */
    public GraphNode getSource() { return sourceNode; }
    /** Target endpoint of this relationship. Never {@code null}. */
    public GraphNode getTarget() { return targetNode; }

    /**
     * Convenience accessor that returns the source endpoint's id.
     * Delegates to {@link GraphNode#getId()} so callers that only have a
     * string-id-based pipeline keep working.
     */
    public String getSourceId() { return sourceNode.getId(); }

    /** Convenience accessor for the target endpoint's id. */
    public String getTargetId() { return targetNode.getId(); }

    public Map<String, Object> getProperties() { return Collections.unmodifiableMap(properties); }

    /**
     * Optional edge weight (typically a non-negative double; values &lt;= 0
     * are normalized to {@code 1.0} by {@link #getWeight()} so layouts can
     * safely compute {@code log(weight)}). Returns {@code null} when no
     * weight was supplied.
     */
    public Double getWeight() {
        Object w = properties.get(PROP_WEIGHT);
        if (w == null) return null;
        if (w instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(w)); }
        catch (NumberFormatException ignored) { return null; }
    }

    /**
     * Convenience accessor that returns a usable positive weight, falling
     * back to {@code 1.0} when no weight was supplied or the supplied weight
     * is non-positive. Useful for layout algorithms that pass the weight to
     * {@code Math.log()}.
     */
    public double getEffectiveWeight() {
        Double w = getWeight();
        if (w == null || w <= 0) return 1.0;
        return w;
    }

    /**
     * Convenience accessor that returns {@code log10(weight)} clamped to a
     * non-negative value. Useful for layout algorithms that scale edge
     * length by the logarithm of the weight — see the
     * {@code SampleGraphController} fcose options for a usage example.
     */
    public double getLog10Weight() {
        double w = getEffectiveWeight();
        return w <= 0 ? 0 : Math.log10(w);
    }

    /**
     * Fluent setter: writes {@code weight} into the {@code properties} map
     * (and removes it again when {@code null} is passed).
     */
    public GraphRelationship setWeight(Double weight) {
        if (weight == null) {
            properties.remove(PROP_WEIGHT);
        } else {
            properties.put(PROP_WEIGHT, weight.doubleValue());
        }
        return this;
    }

    /* ---- visual setters ---- */

    public GraphRelationship setTitle(String label) {
        visualAttrs.put("label", label);
        return this;
    }

    public GraphRelationship setColor(String color) {
        visualAttrs.put("color", color);
        return this;
    }

    public GraphRelationship setColor(ColorSpec color) {
        visualAttrs.put("color", color.toVisValue());
        return this;
    }

    public GraphRelationship setWidth(int width) {
        visualAttrs.put("width", width);
        return this;
    }

    public GraphRelationship setDashes(boolean dashes) {
        visualAttrs.put("dashes", dashes);
        return this;
    }

    public GraphRelationship setArrows(ArrowShape shape) {
        if (shape != null) {
            visualAttrs.put("arrows", shape.name().toLowerCase());
        }
        return this;
    }

    public GraphRelationship setSmooth(SmoothType type) {
        if (type != null) {
            visualAttrs.put("smooth", Map.of("type", type.name().toLowerCase()));
        }
        return this;
    }

    public GraphRelationship setAttribute(String key, Object value) {
        visualAttrs.put(key, value);
        return this;
    }

    /* ---- tooltip ---- */

    public GraphRelationship setTooltip(String html) {
        this.customTooltip = html;
        this.tooltipOverride = true;
        return this;
    }

    public GraphRelationship resetTooltip() {
        this.customTooltip = null;
        this.tooltipOverride = false;
        return this;
    }

    public boolean isTooltipOverridden() { return tooltipOverride; }

    /* ---- serialization ---- */

    public Map<String, Object> toVisNetworkData() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("from", sourceNode.getId());
        out.put("to", targetNode.getId());
        if (visualAttrs.containsKey("label")) {
            out.put("label", visualAttrs.get("label"));
        }
        if (visualAttrs.containsKey("title")) {
            out.put("title", visualAttrs.get("title"));
        } else {
            String title = tooltipOverride
                    ? customTooltip
                    : TooltipBuilder.fromProperties(id, properties);
            if (title != null && !title.isEmpty()) {
                out.put("title", title);
            }
        }
        for (Map.Entry<String, Object> e : visualAttrs.entrySet()) {
            String k = e.getKey();
            if ("label".equals(k) || "title".equals(k)) continue;
            out.put(k, e.getValue());
        }
        return out;
    }

    /**
     * Serializes the relationship for {@code @neo4j-nvl/base}.
     *
     * <p>NVL relationship shape: {@code { id, from, to, type, ... }}. The
     * {@code from} / {@code to} fields mirror the GraphRelationship
     * {@code sourceId}/{@code targetId}. Any {@code label}, {@code caption},
     * {@code color}, or other visual attributes are forwarded as-is.</p>
     */
    public Map<String, Object> toNvlData() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("from", sourceNode.getId());
        out.put("to", targetNode.getId());
        out.put("type", type);
        // Coerce non-string property values to strings so NVL's color
        // parser doesn't crash on Number/Boolean values when it scans
        // properties (see GraphNode.toNvlNode for the matching change).
        if (!properties.isEmpty()) {
            Map<String, Object> safeProps = new LinkedHashMap<>(properties.size());
            for (Map.Entry<String, Object> e : properties.entrySet()) {
                Object v = e.getValue();
                safeProps.put(e.getKey(), v == null ? null : (v instanceof String ? v : v.toString()));
            }
            out.put("properties", safeProps);
        }
        for (Map.Entry<String, Object> e : visualAttrs.entrySet()) {
            String k = e.getKey();
            if ("label".equals(k) || "title".equals(k)) continue;
            // NVL expects `color` to be a flat string, but vis-network's
            // ColorSpec may be a {background,border} object. Reduce it.
            if ("color".equals(k)) {
                String flat = ColorSpec.toNvlString(e.getValue());
                if (flat != null) out.put("color", flat);
                continue;
            }
            out.put(k, e.getValue());
        }
        return out;
    }

    /**
     * Serializes the relationship as a Cytoscape.js element entry:
     * {@code { data: { id, source, target, type, label?, weight?, ...all-properties } }}.
     *
     * <p>Cytoscape distinguishes edges from nodes by the presence of the
     * {@code source} field on the {@code data} object. The {@code source}
     * and {@code target} fields use Cytoscape's naming convention (not
     * vis-network's {@code from}/{@code to}).</p>
     *
     * <p>When a {@code weight} is present, both the raw value and the
     * pre-computed {@code log10Weight} are surfaced as top-level data
     * fields so the JS bridge can use them directly for fcose's
     * {@code idealEdgeLength} without having to dereference properties.</p>
     *
     * <p>Visual attributes are intentionally NOT included — Cytoscape styling
     * is configured separately by the bridge via style selectors.</p>
     */
    public Map<String, Object> toCytoscapeEdge() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("source", sourceNode.getId());
        data.put("target", targetNode.getId());
        data.put("type", type);
        if (visualAttrs.containsKey("label")) {
            Object lbl = visualAttrs.get("label");
            if (lbl != null && !String.valueOf(lbl).isEmpty()) {
                data.put("label", String.valueOf(lbl));
            }
        }
        // Surface the typed weight (and its log10 form) at the top level so
        // fcose / style selectors / tooltips can reference them without
        // touching the `properties` map.
        Double w = getWeight();
        if (w != null) {
            data.put(PROP_WEIGHT, w);
            data.put("log10Weight", Math.log10(getEffectiveWeight()));
        }
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            if (data.containsKey(e.getKey())) continue;
            data.put(e.getKey(), e.getValue());
        }
        // Tooltip: prefer override, otherwise build from properties. The
        // tooltip is exposed as `data.tooltip` so Cytoscape can use it
        // via the `text-valign` / `text-background-color` style.
        // The header "<from> -> <to>" is exposed separately as
        // `data.tooltipHeader` so the JS side can render it as a bold
        // title above the property table.
        String header = sourceNode.getId() + " -> " + targetNode.getId();
        String baseTooltip = tooltipOverride
                ? customTooltip
                : TooltipBuilder.fromProperties(id, properties);
        if (baseTooltip != null && !baseTooltip.isEmpty()) {
            data.put("tooltip", baseTooltip);
        }
        data.put("tooltipHeader", header);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        return out;
    }
}
