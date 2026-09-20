package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Result container for a graph query.
 * Holds an immutable list of nodes and an immutable list of relationships,
 * with index-based lookups.
 *
 * Type-based customizers (via {@link #customizeNodes} / {@link #customizeRelationships})
 * are applied in the order they are registered. Per-instance setters on individual
 * nodes/relationships always win over type-based defaults (because they are applied
 * later during the same iteration during serialization).
 */
public final class GraphData {

    private final List<GraphNode> nodes;
    private final List<GraphRelationship> relationships;
    private final Map<String, GraphNode> nodeIndex;
    private final Map<String, GraphRelationship> relationshipIndex;
    private final Map<String, String> colorPalette;

    public GraphData(List<GraphNode> nodes, List<GraphRelationship> relationships) {
        this(nodes, relationships, Map.of());
    }

    /**
     * Full constructor — accepts an optional per-node color palette that
     * travels with the graph and is auto-pushed to the NVL engine's Color
     * Palette panel by {@code Neo4jNvlViewer.setGraphData(...)}.
     */
    public GraphData(List<GraphNode> nodes, List<GraphRelationship> relationships,
                     Map<String, String> colorPalette) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.relationships = relationships == null ? List.of() : List.copyOf(relationships);
        this.colorPalette = colorPalette == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(colorPalette));

        Map<String, GraphNode> ni = new LinkedHashMap<>();
        for (GraphNode n : this.nodes) {
            ni.put(n.getId(), n);
        }
        this.nodeIndex = Collections.unmodifiableMap(ni);

        Map<String, GraphRelationship> ri = new LinkedHashMap<>();
        for (GraphRelationship r : this.relationships) {
            ri.put(r.getId(), r);
        }
        this.relationshipIndex = Collections.unmodifiableMap(ri);
    }

    public static GraphData empty() {
        return new GraphData(List.of(), List.of());
    }

    public List<GraphNode> getNodes() { return nodes; }
    public List<GraphRelationship> getRelationships() { return relationships; }

    public Optional<GraphNode> findNode(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(nodeIndex.get(id));
    }

    public Optional<GraphRelationship> findRelationship(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(relationshipIndex.get(id));
    }

    public GraphData customizeNodes(Consumer<GraphNode> customizer) {
        Objects.requireNonNull(customizer, "customizer");
        for (GraphNode n : nodes) {
            customizer.accept(n);
        }
        return this;
    }

    public GraphData customizeRelationships(Consumer<GraphRelationship> customizer) {
        Objects.requireNonNull(customizer, "customizer");
        for (GraphRelationship r : relationships) {
            customizer.accept(r);
        }
        return this;
    }

    /**
     * Attach a per-node color palette (id → hex) to this graph. When this
     * graph is loaded into a viewer that supports the Color Palette panel
     * (currently {@code Neo4jNvlViewer} via {@code SwitchingViewer}), the
     * map is auto-pushed via {@code NvlJsBridge.setLeidenColors(...)},
     * which derives panel rows via {@code LegendBuilder.fromLeidenClusters}
     * and renders them in the iframe's Color Palette panel.
     *
     * <p>Returns a NEW {@code GraphData} instance — this class is
     * immutable by design. Pass {@code null} or an empty map to clear the
     * palette. Sigma / Cytoscape / vis-network ignore this map: their
     * palette state is still driven exclusively by
     * {@code SwitchingViewer.applyNodeColors(...)} /
     * {@code setLeidenClusterColors(...)}.</p>
     *
     * @param colorMap per-node id → hex map, or {@code null} to clear
     * @return a new {@code GraphData} with the palette replaced
     */
    public GraphData setColorPalette(Map<String, String> colorMap) {
        Map<String, String> safe = colorMap == null ? Map.of() : colorMap;
        return new GraphData(this.nodes, this.relationships, safe);
    }

    /**
     * Returns the per-node color palette attached to this graph, or
     * {@code Map.of()} when none was set. The returned map is immutable.
     */
    public Map<String, String> getColorPalette() {
        return colorPalette;
    }

    /**
     * Serializes the data into a vis-network data structure:
     *   { "nodes": [ {id, label, color, ...}, ... ],
     *     "edges": [ {id, from, to, ...}, ... ] }
     *
     * <p>Delegates to {@link #toVisNetworkData(de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig)}
     * with {@code null}.</p>
     */
    public Map<String, Object> toVisNetworkData() {
        return toVisNetworkData(null);
    }

    /**
     * Serializes the data into a vis-network payload, honoring the supplied
     * {@link de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig}.
     * See {@link GraphNode#toVisNetworkData(de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig)}
     * for the per-node behavior.
     */
    public Map<String, Object> toVisNetworkData(de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig config) {
        List<Map<String, Object>> nodeOut = new ArrayList<>(nodes.size());
        for (GraphNode n : nodes) {
            nodeOut.add(n.toVisNetworkData(config));
        }
        List<Map<String, Object>> edgeOut = new ArrayList<>(relationships.size());
        for (GraphRelationship r : relationships) {
            edgeOut.add(r.toVisNetworkData());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodeOut);
        out.put("edges", edgeOut);
        return out;
    }

    /**
     * Serializes the data into a Cytoscape.js {@code elements} array.
     *
     * <p>Cytoscape uses a single flat list of {@code { data: {...} }} objects
     * for both nodes and edges. Nodes are distinguished from edges by the
     * presence of a {@code source} field on the {@code data} object.</p>
     *
     * <p>For each node: {@code { data: { id, label, nodeType, nodeTag, ...all-properties } }}.</p>
     * <p>For each relationship: {@code { data: { id, source, target, type, weight?, label?, ...all-properties } } }.</p>
     *
     * <p>Visual attributes (color, shape, size, width, ...) are NOT included here.
     * Cytoscape styling is applied via the style-selector map, which the
     * bridge assembles from the {@link de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig}.</p>
     *
     * <p>The {@code config} argument is currently unused for Cytoscape — it is
     * accepted for API symmetry with {@link #toVisNetworkData}. Cytoscape
     * styling is configured separately via {@code CytoscapeJsBridge.applyNodeConfig}.</p>
     */
    public List<Map<String, Object>> toCytoscapeElements(
            de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig config) {
        List<Map<String, Object>> out = new ArrayList<>(nodes.size() + relationships.size());
        for (GraphNode n : nodes) {
            out.add(n.toCytoscapeNode());
        }
        for (GraphRelationship r : relationships) {
            out.add(r.toCytoscapeEdge());
        }
        return out;
    }

    /**
     * Serializes this graph into a graphology-style payload that sigma.js can
     * consume via two parallel REST endpoints (one for nodes, one for edges).
     *
     * <p>The return shape is a Map with two keys:</p>
     * <ul>
     *   <li>{@code nodes} — list of {@code {key, attributes}} where
     *       {@code key} is the node id and {@code attributes} carries
     *       {@code label}, {@code color}, {@code nodeType}, {@code tooltip},
     *       and the full {@code raw} property map.</li>
     *   <li>{@code edges} — list of {@code {key, source, target, attributes}}
     *       where {@code attributes} carries {@code label} (weight or
     *       custom label), {@code color}, {@code size}, {@code weight},
     *       {@code logWeight}, {@code tooltip}, {@code tooltipHeader}.</li>
     * </ul>
     *
     * <p>Visual styling mirrors {@link #toCytoscapeElements} so the same
     * {@link de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig} produces
     * consistent colours across all three engines. Tooltips are rendered via
     * the same {@link TooltipBuilder} helper used by the vis-network and
     * Cytoscape serializers, so the user sees the same tooltip table on every
     * engine.</p>
     */
    public Map<String, Object> toGraphologyElements(
            de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig config) {
        de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig cfg =
                config == null ? de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig.defaults() : config;
        List<Map<String, Object>> nodeOut = new ArrayList<>(nodes.size());
        for (GraphNode n : nodes) {
            nodeOut.add(n.toGraphologyNode(cfg));
        }
        List<Map<String, Object>> edgeOut = new ArrayList<>(relationships.size());
        for (GraphRelationship r : relationships) {
            edgeOut.add(r.toGraphologyEdge());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodeOut);
        out.put("edges", edgeOut);
        return out;
    }

    /**
     * Serialize this graph as a GML text document.
     *
     * <p>The output mirrors the format produced by the GML ingest path of
     * {@code GraphFileParser} so the document can be round-tripped: a graph
     * parsed from a GML file and exported via this method produces GML that
     * parses back to an equivalent graph (modulo ordering of unrelated
     * nodes / edges).</p>
     *
     * <p>Layout:</p>
     * <pre>{@code
     * graph [
     *   node [
     *     id "n1"
     *     label "..."
     *     ...arbitrary GML key/value pairs from node.properties...
     *   ]
     *   edge [
     *     source "n1"
     *     target "n2"
     *     value 1.0
     *     ...arbitrary GML key/value pairs from relationship.properties...
     *   ]
     * ]
     * }</pre>
     *
     * <p>Numeric values are emitted as bare numbers (no quotes), boolean
     * values as {@code "true"} / {@code "false"} ident tokens (GML's
     * accepted encoding), and all other values are quoted strings with the
     * GML-mandatory escaping for {@code "}, {@code \} and control characters.</p>
     *
     * @return a GML document describing this graph; never {@code null}
     */
    public String exportToGml() {
        StringBuilder sb = new StringBuilder(64 + nodes.size() * 80 + relationships.size() * 60);
        sb.append("Creator \"tk-dependencyanalyse GraphData.exportToGml\"\n");
        sb.append("graph\n[\n");
        for (GraphNode n : nodes) {
            appendGmlNode(sb, n);
        }
        for (GraphRelationship r : relationships) {
            appendGmlEdge(sb, r);
        }
        sb.append("]\n");
        return sb.toString();
    }

    /**
     * Internal: format a GML "label" for a property key name. GML accepts
     * most ident tokens as-is; only a few characters would need quoting.
     */
    private static boolean isBareIdent(String key) {
        if (key == null || key.isEmpty()) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || (i > 0 && c == '-');
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Internal: emit a single key/value pair as a GML line. Strings are
     * quoted with the GML escape rules; numerics go bare; everything else
     * is stringified and quoted.
     */
    private static void appendGmlProp(StringBuilder sb, String key, Object value) {
        sb.append("    ");
        sb.append(isBareIdent(key) ? key : quoteGmlString(key));
        sb.append(' ');
        if (value == null) {
            sb.append("\"\"");
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof GraphRelationship) {
            // Type-based customizers sometimes store nested relationship refs;
            // emit them as a stable string form to keep the round-trip robust.
            sb.append(quoteGmlString(value.toString()));
        } else {
            sb.append(quoteGmlString(String.valueOf(value)));
        }
        sb.append('\n');
    }

    /** Escape a string for use inside a GML {@code "..."} literal. */
    private static String quoteGmlString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /** Emit a {@code node [ ... ]} block, putting {@code id} / {@code label} first. */
    private static void appendGmlNode(StringBuilder sb, GraphNode n) {
        sb.append("  node\n  [\n");
        appendGmlProp(sb, "id", n.getId());
        if (!n.getLabels().isEmpty()) {
            // Emit only the first (primary) label; multi-label graphs are
            // not part of the GML spec we export.
            appendGmlProp(sb, "label", n.getLabels().get(0));
        }
        // All node properties follow in stable iteration order.
        for (Map.Entry<String, Object> e : n.getProperties().entrySet()) {
            String key = e.getKey();
            if (key.equals("id") || key.equals("label")) continue;
            appendGmlProp(sb, key, e.getValue());
        }
        sb.append("  ]\n");
    }

    /**
     * Emit a {@code edge [ ... ]} block, putting {@code source} / {@code target}
     * first and the {@link GraphRelationship#PROP_WEIGHT weight} as {@code value}
     * when present (the de-facto standard position used by Gephi exports and
     * what our GML parser reads back into PROP_WEIGHT).
     */
    private static void appendGmlEdge(StringBuilder sb, GraphRelationship r) {
        sb.append("  edge\n  [\n");
        appendGmlProp(sb, "source", r.getSourceId());
        appendGmlProp(sb, "target", r.getTargetId());
        Object weight = r.getProperties().get(GraphRelationship.PROP_WEIGHT);
        if (weight instanceof Number num) {
            appendGmlProp(sb, "value", num);
        }
        for (Map.Entry<String, Object> e : r.getProperties().entrySet()) {
            String key = e.getKey();
            if (key.equals(GraphRelationship.PROP_WEIGHT) || key.equals("value")
                    || key.equals("source") || key.equals("target")) continue;
            appendGmlProp(sb, key, e.getValue());
        }
        sb.append("  ]\n");
    }

    /**
     * Serializes the data into the {@code @neo4j-nvl/base} (NVL) payload:
     *   { "nodes": [ {id, labels, properties, caption, color}, ... ],
     *     "relationships": [ {id, from, to, type, ...}, ... ] }
     *
     * <p>Conversion rules (see design D4/D5):</p>
     * <ul>
     *   <li>Nodes carry {@code id}, {@code labels}, {@code properties}, plus a
     *       {@code caption} (from {@link GraphNode#getCaption()}, which falls
     *       back to {@code properties.name}) and a {@code color} (from
     *       {@code visualAttrs.color}).</li>
     *   <li>Relationships use {@code from}/{@code to} (not vis-network's
     *       {@code source}/{@code target}).</li>
     * </ul>
     *
     * <p>This is the NVL counterpart to {@link #toVisNetworkData()}. Delegates
     * to {@link GraphNode#toNvlNode()} and {@link GraphRelationship#toNvlData()}.</p>
     */
    public Map<String, Object> toNvlData() {
        List<Map<String, Object>> nodeOut = new ArrayList<>(nodes.size());
        for (GraphNode n : nodes) {
            nodeOut.add(n.toNvlNode());
        }
        List<Map<String, Object>> relOut = new ArrayList<>(relationships.size());
        for (GraphRelationship r : relationships) {
            relOut.add(r.toNvlData());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodeOut);
        out.put("relationships", relOut);
        return out;
    }    
}
