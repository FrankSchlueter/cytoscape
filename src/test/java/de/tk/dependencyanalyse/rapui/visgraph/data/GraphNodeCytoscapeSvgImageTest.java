package de.tk.dependencyanalyse.rapui.visgraph.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link GraphNode#toCytoscapeNode()} propagates SVG-image
 * visual attributes into the Cytoscape {@code data.image} field so the
 * cytoscape-viewer.js bridge can render the node as an SVG badge via
 * {@code background-image} + {@code shape: round-rectangle}.
 */
class GraphNodeCytoscapeSvgImageTest {

    @Test
    void setSvgShapeWithColorProducesDataImageDataUri() {
        GraphNode n = new GraphNode("n1", List.of("Class"),
                Map.of("name", "Foo"));
        n.setSvgShape("Foo", "C", "#dc39bb");

        Map<String, Object> data = (Map<String, Object>) n.toCytoscapeNode().get("data");
        assertNotNull(data, "Cytoscape element should have a data block");
        Object image = data.get("image");
        assertNotNull(image, "data.image must be set when setSvgShape was called");
        // Cytoscape uses base64 to dodge the comma-splitting the URL-encoded
        // payload gets exposed to (Cytoscape parses background-image as a
        // list of URLs, splitting on ','). Both prefixes are valid for the
        // browser's Image() loader.
        assertTrue(image.toString().startsWith("data:image/svg+xml;"),
                "data.image must be a data: URI, was: "
                        + (image == null ? "null" : image.toString().substring(0, Math.min(40, image.toString().length()))));
        // The decoded SVG body must contain the caller's hex color. We
        // detect base64 vs URL-encoded and decode accordingly.
        String body = decodeSvg(image.toString());
        assertTrue(body.contains("#dc39bb"),
                "rendered SVG must contain the caller's color (#dc39bb)");
    }

    @Test
    void setSvgShapeWithoutColorFallsBackToDefault() {
        GraphNode n = new GraphNode("n2", List.of("Class"),
                Map.of("name", "Bar"));
        n.setSvgShape("Bar", "E");   // no color → DEFAULT_ICON_COLOR (#4A90E2)

        Object image = ((Map<String, Object>) n.toCytoscapeNode().get("data")).get("image");
        assertNotNull(image);
        String body = decodeSvg(image.toString());
        assertTrue(body.contains("#4A90E2"),
                "rendered SVG must contain the default fallback color");
    }

    @Test
    void setIconUrlIsPassedThroughUnchanged() {
        GraphNode n = new GraphNode("n3", List.of("Class"),
                Map.of("name", "Baz"));
        n.setIcon("http://example.test/foo.png");

        Object image = ((Map<String, Object>) n.toCytoscapeNode().get("data")).get("image");
        assertEquals("http://example.test/foo.png", image,
                "setIcon(url) must surface the url verbatim in data.image");
    }

    @Test
    void unsetSvgShapeLeavesDataImageNull() {
        GraphNode n = new GraphNode("n4", List.of("Class"),
                Map.of("name", "Qux"));
        Map<String, Object> data = (Map<String, Object>) n.toCytoscapeNode().get("data");
        assertNull(data.get("image"),
                "nodes without setSvgShape / setIcon must NOT carry a data.image entry");
    }

    @Test
    void dataBlockDoesNotCarryCytoscapeShapeProperty() {
        // The Cytoscape shape lives in the stylesheet (imageNodeStyle rule
        // in cytoscape-viewer.js), NOT in `data`. Putting `shape: image`
        // here would be ignored by Cytoscape but pollutes the element payload.
        GraphNode n = new GraphNode("n5", List.of("Class"),
                Map.of("name", "Quux"));
        n.setSvgShape("Quux", "C", "#123456");

        Map<String, Object> data = (Map<String, Object>) n.toCytoscapeNode().get("data");
        assertFalse(data.containsKey("shape"),
                "data.shape is a stylesheet concern, must not appear in data");
    }

    @Test
    void setSvgShapeTypeIsXmlEscapedInSvgBody() {
        // "<Table>" contains an XML-breaking character. The
        // renderSvgIcon4 path reduces `type` to its first character
        // (the `typeChar` placed inside the annotation circle's <text>
        // element) and runs it through SvgRenderer's xmlEscape helper.
        // Verify the angle bracket ends up escaped in the SVG body so
        // the document stays well-formed when the data URI is decoded
        // by the browser's Image() loader.
        GraphNode n = new GraphNode("n6", List.of("Class"),
                Map.of("name", "<Table>"));
        // setSvgShape signature: (label, type, color). Put the dangerous
        // string in the `type` slot — renderSvgIcon4 takes its first
        // character ('<') and renders it as the badge's type label.
        n.setSvgShape("ignored-display-label", "<Table>", "#abcdef");

        Object image = ((Map<String, Object>) n.toCytoscapeNode().get("data")).get("image");
        assertNotNull(image);
        String body = decodeSvg(image.toString());
        // XML-escaping turns "<" into "&lt;" inside the SVG <text> element.
        assertTrue(body.contains("&lt;"),
                "the type-char '<' must be XML-escaped in the SVG body: "
                        + body.substring(0, Math.min(120, body.length())));
        // And the raw angle bracket must NOT appear unescaped inside a
        // <text>...</text> payload.
        assertFalse(body.matches("(?s).*<text[^>]*>[^<]*<[^/][^<]*</text>.*"),
                "raw '<' must not appear unescaped inside the <text> element");
    }

    /** Decode either a base64 or URL-encoded data:image/svg+xml URI. */
    private static String decodeSvg(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) throw new AssertionError("malformed data URI: " + uri);
        String payload = uri.substring(comma + 1);
        if (uri.startsWith("data:image/svg+xml;base64,")) {
            return new String(java.util.Base64.getDecoder().decode(payload),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return java.net.URLDecoder.decode(payload, java.nio.charset.StandardCharsets.UTF_8);
    }
}
