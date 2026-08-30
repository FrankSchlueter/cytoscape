package de.tk.dependencyanalyse.rapui.visgraph.io;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads graph data from {@code csv} (edge list) and {@code gml} files.
 *
 * <p><b>CSV</b> — first line may contain a header {@code Source,Target,Weight[,...]}.
 * Subsequent lines are {@code source,target[,weight[,label]]}. Lines with fewer
 * than two columns or with an empty source / target are skipped. Weights are
 * parsed as doubles; unparseable weights default to {@code 1.0}. Nodes are
 * auto-created from any source / target id that has not been seen before.</p>
 *
 * <p><b>GML</b> — supports the keys {@code node}, {@code edge}, {@code id},
 * {@code source}, {@code target}, {@code label}, {@code weight}, and the
 * generic {@code graphics} block (we only inspect it to discover display
 * hints like {@code fill} / {@code type} when present). String values are
 * quoted; bracketed values may be nested. Unknown keys are tolerated
 * silently.</p>
 */
public final class GraphFileParser {

    /** File formats the parser understands. */
    public enum Format { CSV, GML }

    private GraphFileParser() {}

    /** Detect format from file name (case-insensitive extension). */
    public static Format detectFormat(String fileName) {
        if (fileName == null) return Format.CSV;
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".gml") || n.endsWith(".graphml")) return Format.GML;
        return Format.CSV;
    }

    /**
     * Parse {@code in} as the given format and return a {@link GraphData}.
     * The stream is consumed but not closed by this method.
     */
    public static GraphData parse(InputStream in, Format format) throws IOException {
        if (in == null) throw new IOException("InputStream is null");
        if (format == null) format = Format.CSV;
        return switch (format) {
            case CSV -> parseCsv(in);
            case GML -> parseGml(in);
        };
    }

    /** Convenience overload: detect the format from {@code fileName}. */
    public static GraphData parse(InputStream in, String fileName) throws IOException {
        return parse(in, detectFormat(fileName));
    }

    /* ---------------------------------------------------------------- CSV */

    private static GraphData parseCsv(InputStream in) throws IOException {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphRelationship> rels = new ArrayList<>();
        int edgeSeq = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean firstNonBlank = true;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                if (firstNonBlank) {
                    firstNonBlank = false;
                    String[] head = line.split(",", -1);
                    if (head.length >= 1 && head[0].trim().equalsIgnoreCase("Source")) {
                        continue;
                    }
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 2) continue;
                String src = parts[0].trim();
                String tgt = parts[1].trim();
                if (src.isEmpty() || tgt.isEmpty()) continue;

                double weight = 1.0;
                boolean hasWeight = false;
                if (parts.length >= 3) {
                    try {
                        weight = Double.parseDouble(parts[2].trim());
                        hasWeight = true;
                    } catch (NumberFormatException ignored) {}
                }

                GraphNode s = nodeOf(nodes, src);
                GraphNode t = nodeOf(nodes, tgt);
                edgeSeq++;
                Map<String, Object> props = new LinkedHashMap<>();
                if (hasWeight) props.put(GraphRelationship.PROP_WEIGHT, weight);
                if (parts.length >= 4) {
                    props.put("label", parts[3].trim());
                }
                rels.add(new GraphRelationship("e" + edgeSeq, "REL", s, t, props));
            }
        }
        return new GraphData(new ArrayList<>(nodes.values()), rels);
    }

    private static GraphNode nodeOf(Map<String, GraphNode> idx, String id) {
        return idx.computeIfAbsent(id, k -> new GraphNode(k, List.of("Node"),
                Map.of("name", k, "nodeTag", "entity")));
    }

    /* ---------------------------------------------------------------- GML */

    private static GraphData parseGml(InputStream in) throws IOException {
        GmlTokenizer tok = new GmlTokenizer(in);
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphRelationship> rels = new ArrayList<>();
        int edgeSeq = 0;
        GmlToken head;
        while ((head = tok.next()) != null) {
            if (!head.isIdent("graph")) {
                head.skipValue(tok);
                continue;
            }
            tok.expectOpen();
            while (true) {
                GmlToken section = tok.next();
                if (section == null || section.kind == GmlToken.Kind.CLOSE) break;
                switch (section.text) {
                    case "node" -> readGmlNode(tok, nodes);
                    case "edge" -> {
                        edgeSeq = readGmlEdge(tok, nodes, rels, edgeSeq);
                        // nodes may have been auto-created by the edge; nothing else to do.
                    }
                    default -> section.skipValue(tok);
                }
            }
            // The CLOSE token above ended the outer graph block; we're done.
            return new GraphData(new ArrayList<>(nodes.values()), rels);
        }
        // Empty file or no graph block.
        return new GraphData(new ArrayList<>(nodes.values()), rels);
    }

    private static void readGmlNode(GmlTokenizer tok, Map<String, GraphNode> nodes) throws IOException {
        tok.expectOpen();
        String id = null;
        // Capture the GML "label" so we can seed GraphNode properties.name
        // when present. All other key/value pairs (including the key "id",
        // and any nested graphics [...] blocks) are forwarded into the
        // GraphNode properties bag as-is.
        String label = null;
        Map<String, Object> extraProps = new LinkedHashMap<>();
        while (true) {
            GmlToken nk = tok.next();
            if (nk == null || nk.kind == GmlToken.Kind.CLOSE) break;
            if (nk.isIdent("id")) {
                id = tok.readScalar();
            } else if (nk.isIdent("label")) {
                label = tok.readScalar();
                if (label != null) extraProps.put("label", label);
            } else {
                captureKeyValue(tok, nk, extraProps);
            }
        }
        if (id != null && !id.isEmpty()) {
            // id is also surfaced as a property (the user explicitly asked
            // for "all values from gml to land in properties"), defaulting
            // to the same string we use as the GraphNode identifier.
            extraProps.put("id", id);
            // seed sensible defaults on top of the GML properties
            extraProps.putIfAbsent("name", label != null ? label : id);
            extraProps.putIfAbsent("nodeTag", "entity");
            // Use the GML label as the primary GraphNode label when available
            // so GML roundtrips preserve the original nodeType.
            List<String> labels = (label != null && !label.isEmpty())
                    ? List.of(label)
                    : List.of("Node");
            GraphNode graphNode = new GraphNode(id, labels, extraProps);
            // Mark the node as an SVG-badge so the Cytoscape bridge renders
            // it via background-image / round-rectangle and vis-network
            // renders it via shape=image. setSvgShape does both; calling
            // renderSvgIcon3() directly is a no-op because its return value
            // is the rendered SVG string, not a setter side-effect.
            if( extraProps.get("_nodeType_") != null ) {
                graphNode.setSvgShape(label, (String) extraProps.get("_nodeType_"), "#00FFFF");
            } else {
                graphNode.setSvgShape(label, "Node", id);
            }
            nodes.putIfAbsent(id, graphNode);
        }
    }

    /** @return the updated edge sequence number. */
    private static int readGmlEdge(GmlTokenizer tok, Map<String, GraphNode> nodes,
                                   List<GraphRelationship> rels, int edgeSeq) throws IOException {
        tok.expectOpen();
        String source = null;
        String target = null;
        Map<String, Object> extraProps = new LinkedHashMap<>();
        while (true) {
            GmlToken ek = tok.next();
            if (ek == null || ek.kind == GmlToken.Kind.CLOSE) break;
            if (ek.isIdent("source")) {
                source = tok.readScalar();
            } else if (ek.isIdent("target")) {
                target = tok.readScalar();
            } else {
                // Everything else — including "label", "weight", "value" and
                // arbitrary nested blocks like "graphics [...]" — becomes a
                // property on the GraphRelationship.
                captureKeyValue(tok, ek, extraProps);
            }
        }
        if (source != null && target != null) {
            if (!nodes.containsKey(source)) nodes.put(source, autoNode(source));
            if (!nodes.containsKey(target)) nodes.put(target, autoNode(target));
            // Make sure weight is always a double — the Cytoscape
            // serializer computes logWeight from PROP_WEIGHT, and a
            // string "1" would yield null downstream.
            coerceWeight(extraProps);
            edgeSeq++;
            // Pass actual GraphNode references so the relationship carries
            // direct pointers to its endpoints (downstream code can call
            // getSource().getLabels() etc. without lookups).
            rels.add(new GraphRelationship("e" + edgeSeq, "REL",
                    nodes.get(source), nodes.get(target), extraProps));
        }
        return edgeSeq;
    }

    /**
     * Read a single {@code key value} pair into {@code props}, coercing
     * numeric literals to {@link Double} / {@link Boolean} as appropriate.
     * For nested [...] values we record the full raw text representation
     * (so no nested structure is silently dropped).
     */
    private static void captureKeyValue(GmlTokenizer tok, GmlToken keyToken,
                                        Map<String, Object> props) throws IOException {
        GmlToken next = tok.peek();
        if (next == null) return;
        if (next.kind == GmlToken.Kind.OPEN) {
            props.put(keyToken.text, tok.readBalancedBlock());
            return;
        }
        GmlToken v = tok.next();
        if (v == null) return;
        Object val = switch (v.kind) {
            case STRING -> v.text;
            case NUMBER -> {
                try { yield Double.parseDouble(v.text); }
                catch (NumberFormatException e) { yield v.text; }
            }
            case IDENT -> parseIdentLiteral(v.text);
            default -> v.text;
        };
        props.put(keyToken.text, val);
    }

    private static Object parseIdentLiteral(String s) {
        // GML allows bare "true" / "false" identifiers — capture them as
        // booleans; everything else stays a String.
        if (s == null) return null;
        if (s.equalsIgnoreCase("true"))  return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        return s;
    }

    private static void coerceWeight(Map<String, Object> props) {
        Object w = props.get(GraphRelationship.PROP_WEIGHT);
        if (w == null) {
            // Some GML dialects (e.g. Gephi exports) use "value" instead of
            // "weight". Promote "value" into PROP_WEIGHT so the downstream
            // logWeight computation kicks in.
            Object v = props.get("value");
            if (v instanceof Number num) {
                w = num.doubleValue();
                props.put(GraphRelationship.PROP_WEIGHT, w);
            } else if (v instanceof String s) {
                try { w = Double.parseDouble(s); props.put(GraphRelationship.PROP_WEIGHT, w); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (w instanceof String s) {
            try { props.put(GraphRelationship.PROP_WEIGHT, Double.parseDouble(s)); }
            catch (NumberFormatException ignored) {}
        }
    }

    private static GraphNode autoNode(String id) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", id);
        props.put("nodeTag", "entity");
        return new GraphNode(id, List.of("Node"), props);
    }

    /* --------------------------------------------------------- GML tokenizer */

    private static final class GmlToken {
        enum Kind { IDENT, STRING, NUMBER, OPEN, CLOSE }
        final Kind kind;
        final String text;

        private GmlToken(Kind kind, String text) { this.kind = kind; this.text = text; }

        boolean isIdent(String s) { return kind == Kind.IDENT && s.equals(text); }

        /**
         * Skip an arbitrary value following an identifier: a single scalar, or
         * a balanced [...] block. After return the cursor sits just before the
         * next sibling identifier or the closing '}' of the parent block.
         */
        void skipValue(GmlTokenizer tok) throws IOException {
            if (kind != Kind.IDENT) {
                // safety net — strings/numbers already represent their whole value
                return;
            }
            GmlToken next = tok.peek();
            if (next == null) return;
            if (next.kind == GmlToken.Kind.OPEN) {
                tok.consumeOpen();
                int depth = 1;
                while (depth > 0) {
                    GmlToken x = tok.next();
                    if (x == null) return;
                    if (x.kind == GmlToken.Kind.OPEN) depth++;
                    else if (x.kind == GmlToken.Kind.CLOSE) depth--;
                }
                return;
            }
            tok.next();
        }
    }

    private static final class GmlTokenizer {
        private final BufferedReader br;

        /** One-token lookahead so {@link GmlToken#skipValue} can decide. */
        private GmlToken pending;

        GmlTokenizer(InputStream in) {
            this.br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        /** Peek the next token without consuming it. */
        GmlToken peek() throws IOException {
            if (pending == null) pending = read();
            return pending;
        }

        /** Consume and return the next token. */
        GmlToken next() throws IOException {
            if (pending != null) {
                GmlToken t = pending;
                pending = null;
                return t;
            }
            return read();
        }

        void consumeOpen() throws IOException {
            GmlToken t = next();
            if (t == null || t.kind != GmlToken.Kind.OPEN) {
                throw new IOException("GML: expected '['");
            }
        }

        void expectOpen() throws IOException { consumeOpen(); }

        void expectClose() throws IOException {
            GmlToken t = next();
            if (t == null || t.kind != GmlToken.Kind.CLOSE) {
                throw new IOException("GML: expected ']'");
            }
        }

        /** Read the scalar that follows an identifier as a string. */
        String readScalar() throws IOException {
            GmlToken t = next();
            if (t == null) return null;
            return switch (t.kind) {
                case STRING, IDENT, NUMBER -> t.text;
                default -> { t.skipValue(this); yield null; }
            };
        }

        /**
         * Consume the OPEN token (already verified) and everything up to the
         * matching CLOSE token. Returns the textual payload (including the
         * surrounding brackets) so callers can store it as a property
         * without losing nested structure.
         */
        String readBalancedBlock() throws IOException {
            consumeOpen();
            StringBuilder sb = new StringBuilder("[");
            int depth = 1;
            while (depth > 0) {
                GmlToken t = next();
                if (t == null) break;
                switch (t.kind) {
                    case OPEN -> { depth++; sb.append('['); }
                    case CLOSE -> { depth--; sb.append(']'); }
                    case STRING -> sb.append('"').append(escapeForLog(t.text)).append('"');
                    case NUMBER, IDENT -> sb.append(t.text);
                }
                if (depth > 0) sb.append(' ');
            }
            return sb.toString();
        }

        private static String escapeForLog(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private GmlToken read() throws IOException {
            int c;
            while ((c = readSkipWs()) != -1) {
                switch (c) {
                    case '[' -> { return new GmlToken(GmlToken.Kind.OPEN, "["); }
                    case ']' -> { return new GmlToken(GmlToken.Kind.CLOSE, "]"); }
                    case '"' -> { return new GmlToken(GmlToken.Kind.STRING, readQuoted()); }
                    default -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append((char) c);
                        while (true) {
                            int p = br.read();
                            if (p == -1) break;
                            char pc = (char) p;
                            if (Character.isWhitespace(pc) || pc == '[' || pc == ']' || pc == '"') {
                                br.mark(1);
                                if (pc != '"') br.reset();   // push whitespace/punct back
                                break;
                            }
                            sb.append(pc);
                        }
                        String s = sb.toString();
                        if (looksNumeric(s)) return new GmlToken(GmlToken.Kind.NUMBER, s);
                        return new GmlToken(GmlToken.Kind.IDENT, s);
                    }
                }
            }
            return null;
        }

        private int readSkipWs() throws IOException {
            int c;
            while ((c = br.read()) != -1) {
                char ch = (char) c;
                if (!Character.isWhitespace(ch) && ch != '\n' && ch != '\r') return c;
            }
            return -1;
        }

        private String readQuoted() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = br.read()) != -1) {
                char ch = (char) c;
                if (ch == '"') return sb.toString();
                if (ch == '\\') {
                    int esc = br.read();
                    if (esc == -1) break;
                    switch ((char) esc) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\', '"' -> sb.append((char) esc);
                        default -> { sb.append('\\').append((char) esc); }
                    }
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        private static boolean looksNumeric(String s) {
            if (s == null || s.isEmpty()) return false;
            int dots = 0;
            int start = (s.charAt(0) == '-' || s.charAt(0) == '+') ? 1 : 0;
            if (start >= s.length()) return false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '.') { dots++; if (dots > 1) return false; }
                else if (!Character.isDigit(c)) return false;
            }
            return true;
        }
    }
}
