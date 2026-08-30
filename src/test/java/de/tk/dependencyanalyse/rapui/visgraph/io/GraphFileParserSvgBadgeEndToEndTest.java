package de.tk.dependencyanalyse.rapui.visgraph.io;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test: parse a GML fixture that carries a {@code _nodeType_}
 * property on every node, then verify the SVG-badge rendering pipeline
 * produces a ready-to-use image URI for both Cytoscape and vis-network.
 *
 * <p>The bug this guards against: a previous revision of
 * {@link GraphFileParser#readGmlNode} called
 * {@link GraphNode#renderSvgIcon3} and discarded the return value, so the
 * parsed graph never carried an image attribute and the SVG badge was
 * silently invisible in both viewers.</p>
 */
class GraphFileParserSvgBadgeEndToEndTest {

    private static final String FIXTURE_GML =
            "graph [\n" +
            "  directed 1\n" +
            "  node [\n" +
            "    id \"BatchReader-1\"\n" +
            "    label \"AmtshilfeReader\"\n" +
            "    _nodeType_ \"BatchReader\"\n" +
            "    product \"Rente\"\n" +
            "  ]\n" +
            "  node [\n" +
            "    id \"BatchWriter-1\"\n" +
            "    label \"SendenWriter\"\n" +
            "    _nodeType_ \"BatchWriter\"\n" +
            "    product \"Rente\"\n" +
            "  ]\n" +
            "  edge [\n" +
            "    source \"BatchReader-1\"\n" +
            "    target \"BatchWriter-1\"\n" +
            "    label \"writes\"\n" +
            "  ]\n" +
            "]\n";

    @Test
    void cytoscapeImageUriCarriesSvgBadgeWithNodeType() throws Exception {
        GraphData data = parse(FIXTURE_GML);

        GraphNode batchReader = findNode(data, "BatchReader-1");
        GraphNode batchWriter = findNode(data, "BatchWriter-1");

        // -- Cytoscape serialization --
        // toCytoscapeNode() must populate data.image with a data URI whose
        // body contains the SVG badge, including the type char and the
        // background-color baked in by readGmlNode ("#00FFFF").
        Map<String, Object> cytoscapeReader =
                (Map<String, Object>) batchReader.toCytoscapeNode().get("data");
        assertSvgBadgeDataUri(cytoscapeReader.get("image"),
                "BatchReader", "#00FFFF");

        Map<String, Object> cytoscapeWriter =
                (Map<String, Object>) batchWriter.toCytoscapeNode().get("data");
        assertSvgBadgeDataUri(cytoscapeWriter.get("image"),
                "BatchWriter", "#00FFFF");

        // The elements array the bridge actually serializes must carry the
        // same data.image on every parsed node — this is the field the
        // cytoscape-viewer.js bridge turns into background-image.
        List<Map<String, Object>> elements =
                data.toCytoscapeElements(null);
        // toCytoscapeElements returns a single flat list of nodes+edges;
        // count just the node entries via the absence of `source`.
        int nodeCount = 0;
        for (Map<String, Object> ele : elements) {
            Map<String, Object> d = (Map<String, Object>) ele.get("data");
            if (d == null || d.containsKey("source")) continue;
            nodeCount++;
            Object image = d.get("image");
            assertNotNull(image,
                    "Cytoscape element must carry data.image after parse: "
                            + ele);
            assertTrue(image.toString().startsWith("data:image/svg+xml;"),
                    "image must be a data: URI, was: " + image);
        }
        assertEquals(2, nodeCount, "expected two node elements");
    }

    @Test
    void visNetworkSerializationCarriesShapeAndImage() throws Exception {
        GraphData data = parse(FIXTURE_GML);

        GraphNode batchReader = findNode(data, "BatchReader-1");

        // -- vis-network serialization --
        // toVisNetworkData() must surface shape=image AND image=<data URI>.
        // Before the fix the svgImage descriptor map was emitted verbatim
        // and vis-network never rendered anything.
        Map<String, Object> visData = batchReader.toVisNetworkData();
        assertEquals("image", visData.get("shape"),
                "vis-network node shape must be 'image' to render an SVG badge");
        Object image = visData.get("image");
        assertNotNull(image, "vis-network node must carry an image attribute");
        assertTrue(image.toString().startsWith("data:image/svg+xml;"),
                "image must be a data: URI for vis-network to render the SVG");
        // And the SVG body must still carry the type char and color.
        assertSvgBadgeDataUri(image, "BatchReader", "#00FFFF");
    }

    @Test
    void cytoscapeAndVisNetworkSurfacesRenderIdenticalSvgBody() throws Exception {
        GraphData data = parse(FIXTURE_GML);
        GraphNode node = findNode(data, "BatchReader-1");

        // Both surfaces must end up encoding the SAME underlying SVG body.
        // The Cytoscape surface uses base64 (Cytoscape splits
        // background-image on commas, which would shred a URL-encoded
        // payload); the vis-network surface continues to use URL-encoding
        // for backwards compatibility. Both must decode to the same SVG.
        String cytoscapeUri = (String)
                ((Map<String, Object>) node.toCytoscapeNode().get("data")).get("image");
        String visUri = (String) node.toVisNetworkData().get("image");

        String cytoscapeSvg = decodeSvgDataUri(cytoscapeUri);
        String visSvg       = decodeSvgDataUri(visUri);
        assertEquals(cytoscapeSvg, visSvg,
                "Cytoscape and vis-network must encode identical SVG bodies");
        // Sanity check on the SVG body itself.
        assertTrue(cytoscapeSvg.contains("<svg"),
                "decoded SVG body must contain <svg>");
        assertTrue(cytoscapeSvg.contains("fill=\"#00FFFF\""),
                "decoded SVG must contain the requested background color");
        // renderSvgIcon4 maps node-types to single-character typeChars
        // (BatchReader -> 'R', BatchWriter -> 'W', ...). Verify the
        // expected typeChar shows up in the badge's <text> element so
        // the user can still distinguish batch types in the rendered UI.
        assertTrue(cytoscapeSvg.indexOf(">R<") >= 0
                        || cytoscapeSvg.indexOf(">R</text>") >= 0,
                "decoded SVG must contain the BatchReader typeChar 'R' "
                        + "in the <text> element; full body was: " + cytoscapeSvg);
    }

    @Test
    void cytoscapeElementsPayloadPreservesDataImageField() throws Exception {
        // Last-mile check: the elements map the CytoscapeJsBridge ships to
        // the iframe via Gson is the same map produced by toCytoscapeElements().
        // Gson does not drop the `image` field — verify directly so a future
        // refactor that swaps Gson for a hand-rolled serializer doesn't
        // accidentally strip the data.image attribute.
        GraphData data = parse(FIXTURE_GML);
        List<Map<String, Object>> elements = data.toCytoscapeElements(null);
        boolean sawImage = false;
        for (Map<String, Object> ele : elements) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) ele.get("data");
            if (d == null || d.containsKey("source")) continue;
            Object image = d.get("image");
            if (image == null) {
                throw new AssertionError(
                        "Cytoscape element data is missing image: " + d);
            }
            assertTrue(image.toString().startsWith("data:image/svg+xml"),
                    "Cytoscape data.image must be a data: URI, was: " + image);
            sawImage = true;
        }
        assertTrue(sawImage, "test fixture must contain at least one node");
    }

    /**
     * Smoke-test against the real TVERS-Usage.gml sample the user originally
     * reported. Every node in that file carries a {@code _nodeType_} property
     * (BatchReader, BatchWriter, ...). Without the fix, readGmlNode silently
     * discarded the rendered SVG string and no node carried a data.image
     * attribute — so the Cytoscape + vis-network bridges both rendered the
     * graph WITHOUT any SVG badge.
     */
    @Test
    void tversUsageRealSampleProducesSvgBadgeOnEveryNode() throws Exception {
        Path p = samplePath("TVERS-Usage.gml");
        if (p == null) {
            System.err.println("TVERS-Usage.gml not on classpath; skipping");
            return;
        }
        GraphData data;
        try (InputStream in = Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }
        assertTrue(data.getNodes().size() > 0,
                "real TVERS sample must parse to > 0 nodes");

        int nodesWithImage = 0;
        for (GraphNode n : data.getNodes()) {
            // Cytoscape surface.
            String cyImage = (String)
                    ((Map<String, Object>) n.toCytoscapeNode().get("data")).get("image");
            assertNotNull(cyImage,
                    "real-sample node " + n.getId() + " must carry data.image");
            assertTrue(cyImage.startsWith("data:image/svg+xml"),
                    "node " + n.getId() + " data.image must be a data: URI");

            // vis-network surface.
            Map<String, Object> vis = n.toVisNetworkData();
            assertEquals("image", vis.get("shape"),
                    "real-sample node " + n.getId() + " must have shape=image");
            assertTrue(String.valueOf(vis.get("image")).startsWith("data:image/svg+xml"),
                    "real-sample node " + n.getId() + " vis image must be a data: URI");
            nodesWithImage++;
        }
        assertEquals(data.getNodes().size(), nodesWithImage,
                "every parsed node must carry an SVG-badge image");

        // The Cytoscape element payload the bridge serializes must carry the
        // image on every node entry (not the edges).
        List<Map<String, Object>> elements = data.toCytoscapeElements(null);
        int nodeCount = 0, nodeWithImage = 0;
        for (Map<String, Object> ele : elements) {
            Map<String, Object> d = (Map<String, Object>) ele.get("data");
            if (d == null || d.containsKey("source")) continue;
            nodeCount++;
            if (d.get("image") != null) nodeWithImage++;
        }
        assertEquals(nodeCount, nodeWithImage,
                "every Cytoscape node element must carry data.image");
    }

    /* --------------------------------------------------------- helpers */

    private static GraphData parse(String gml) throws Exception {
        try (InputStream in = new java.io.ByteArrayInputStream(
                gml.getBytes(StandardCharsets.UTF_8))) {
            return GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }
    }

    private static GraphNode findNode(GraphData data, String id) {
        return data.findNode(id).orElseThrow(
                () -> new AssertionError("expected node " + id));
    }

    private static void assertSvgBadgeDataUri(Object image, String type, String color) {
        assertNotNull(image, "expected a data:image/svg+xml URI");
        String s = image.toString();
        assertTrue(s.startsWith("data:image/svg+xml"),
                "expected data:image/svg+xml prefix, was: " + s.substring(0, Math.min(60, s.length())));
        String body = decodeSvgDataUri(s);
        assertTrue(body.contains("<svg"),
                "decoded URI must contain <svg>, was: " + body.substring(0, Math.min(80, body.length())));
        assertTrue(body.contains("fill=\"" + color + "\""),
                "decoded SVG must contain backgroundColor " + color + " baked in: " + body);
        // renderSvgIcon4 maps node-types to a single-character typeChar
        // rendered inside the annotation circle's <text> element.
        // For "BatchReader" -> 'R', for "BatchWriter" -> 'W'. Verify
        // the expected typeChar shows up so the badge carries a usable
        // marker letter in the rendered UI.
        char expectedChar = expectedTypeChar(type);
        assertTrue(body.indexOf(">" + expectedChar + "<") >= 0
                        || body.indexOf(">" + expectedChar + "</text>") >= 0,
                "decoded SVG must contain typeChar '" + expectedChar
                        + "' in the <text> element for nodeType '" + type + "'; "
                        + "full body was: " + body);
    }

    /** Map a node-type string to the typeChar renderSvgIcon4 renders. */
    private static char expectedTypeChar(String nodeType) {
        if (nodeType == null) return ' ';
        switch (nodeType.toLowerCase()) {
            case "class":           return 'C';
            case "enum":            return 'E';
            case "tkentity":        return 'E';
            case "tkcontroller":    return 'C';
            case "batchreader":     return 'R';
            case "batchwriter":     return 'W';
            case "tableinfo":       return 'T';
            default:                return Character.toUpperCase(nodeType.charAt(0));
        }
    }

    /** Decode a {@code data:image/svg+xml,...} URI regardless of encoding. */
    private static String decodeSvgDataUri(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) throw new AssertionError("malformed data URI: " + uri);
        String payload = uri.substring(comma + 1);
        if (uri.startsWith("data:image/svg+xml;base64,")) {
            return new String(java.util.Base64.getDecoder().decode(payload),
                    StandardCharsets.UTF_8);
        }
        return URLDecoder.decode(payload, StandardCharsets.UTF_8);
    }

    /** Resolve a sample file from either target/classes or src/main/resources. */
    private static Path samplePath(String name) {
        String[] tries = {
                "target/classes/sample/" + name,
                "src/main/resources/sample/" + name,
        };
        for (String t : tries) {
            Path p = Paths.get(t);
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
