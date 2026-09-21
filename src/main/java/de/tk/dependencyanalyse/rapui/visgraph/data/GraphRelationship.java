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
    private Map<String, Object> properties;
    private final Map<String, Object> visualAttrs = new LinkedHashMap<>();
    private String customTooltip;
    private boolean tooltipOverride = false;

    /**
     * Primary constructor â source and target are full node references.
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
     *             â constructing with bare strings loses the connection
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

    public void setProperties(Map<String, Object> newProperties) {
		if (newProperties != null) {
			properties = newProperties;
		}
	}
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
     * length by the logarithm of the weight â see the
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
        // Surface weight + pre-computed logWeight so the vis-network
        // Cluster-Layout-Strategie (Cluster-Layout.md §3) can derive
        // per-edge length and filter thresholds directly in
        // vis-graph-viewer.js without a second roundtrip. Cytoscape's
        // toCytoscapeEdge() does the same — see CytoscapeViewer for the
        // fcose counterpart. Missing / non-positive weight → both fields
        // are omitted; the JS side falls back to its own defaults.
        Double w = getWeight();
        if (w != null && w > 0) {
            out.put("weight", w);
            out.put("logWeight", Math.log10(w + 1.0));
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
     * Build the link payload consumed by the 3D Force-Directed Graph bridge.
     *
     * <p>3d-force-graph link shape: {@code { id, source, target, type, color?, properties?, weight?, logWeight? }}.
     * Mirrors {@link #toNvlData()} but uses {@code source}/{@code target} (the
     * d3-force-3d convention) instead of NVL's {@code from}/{@code to}.
     * Non-string property values are coerced to strings (same safety measure
     * as the NVL serializer).</p>
     *
     * <p>{@code weight} and the pre-computed {@code logWeight} are surfaced
     * at the top level of the payload — same pattern as
     * {@link #toVisNetworkData()} and {@link #toCytoscapeEdge()} — so the
     * JS bridge can drive {@code d3Force('link').distance(...)} and
     * {@code linkWidth(...)} without dereferencing the {@code properties}
     * map. Missing / non-positive weight → both fields are omitted; the JS
     * side falls back to its own defaults.</p>
     */
    public Map<String, Object> toThreeForceGraphLink() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("source", sourceNode.getId());
        out.put("target", targetNode.getId());
        out.put("type", type);
        Double w = getWeight();
        if (w != null && w > 0) {
            out.put("weight", w);
            out.put("logWeight", Math.log10(w + 1.0));
        }
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
     * pre-computed {@code logWeight} are surfaced as top-level data
     * fields so the JS bridge can use them directly for fcose's
     * {@code idealEdgeLength} without having to dereference properties.</p>
     *
     * <p>Visual attributes are intentionally NOT included â Cytoscape styling
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
            data.put("logWeight", Math.log(getEffectiveWeight()+1));
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

    /**
     * Serializes this relationship for the sigma.js engine via the
     * graphology schema consumed by the sigma iframe (delivered through
     * the Rap-JS bridge, see {@code SigmaJsBridge.applyData}).
     *
     * <p>The {@code attributes} map mirrors the Cytoscape {@code data}
     * payload: {@code label} (the {@code weight} value when present,
     * otherwise the explicit label), {@code weight}, {@code logWeight},
     * {@code size}, {@code color}, {@code tooltip}, {@code tooltipHeader}.
     * Edge size scales logarithmically with the weight so heavy edges
     * stand out visually (mirrors the Cytoscape sqrt-style scaling).</p>
     */
    public Map<String, Object> toGraphologyEdge() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", id);
        attributes.put("type", type);
        Double w = getWeight();
        if (w != null) {
            attributes.put("weight", w);
            attributes.put("logWeight", Math.log(getEffectiveWeight() + 1));
        }
        String labelText = null;
        if (visualAttrs.containsKey("label")) {
            Object lbl = visualAttrs.get("label");
            if (lbl != null && !String.valueOf(lbl).isEmpty()) {
                labelText = String.valueOf(lbl);
            }
        }
        if (labelText == null && w != null) {
            // Default edge label is the raw weight value — matches the
            // Cytoscape-side label default in cytoscape-viewer.js.
            labelText = formatEdgeLabel(w);
        }
        if (labelText != null) attributes.put("label", labelText);
        // Sqrt-style size scaling: 0.6 + 0.9 * sqrt(min(max(logWeight, 0), 4))
        if (w != null) {
            double lw = Math.log(getEffectiveWeight() + 1);
            double clamped = Math.min(Math.max(lw, 0), 4);
            attributes.put("size", 0.6 + 0.9 * Math.sqrt(clamped));
        }
        Object colorAttr = visualAttrs.get("color");
        if (colorAttr != null) attributes.put("color", String.valueOf(colorAttr));
        String baseTooltip = tooltipOverride
                ? customTooltip
                : TooltipBuilder.fromProperties(id, properties);
        if (baseTooltip != null && !baseTooltip.isEmpty()) {
            attributes.put("tooltip", baseTooltip);
        }
        String header = sourceNode.getId() + " -> " + targetNode.getId();
        attributes.put("tooltipHeader", header);
        if (!properties.isEmpty()) {
            Map<String, Object> raw = new LinkedHashMap<>(properties);
            attributes.put("raw", raw);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", id);
        out.put("source", sourceNode.getId());
        out.put("target", targetNode.getId());
        out.put("attributes", attributes);
        return out;
    }

    private static String formatEdgeLabel(Double w) {
        if (w == Math.floor(w)) {
            return Long.toString(w.longValue());
        }
        return String.format("%.2f", w);
    }
}
