package de.tk.dependencyanalyse.rapui.visgraph.data;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link GraphNode#recolorSvgShape} mutation entry point
 * that the {@code GraphConfigurationDialog}'s color picker relies on.
 *
 * <p>When the dialog calls {@code setNodeConfig(...)} with a new color
 * map, the Cytoscape bridge re-runs every SVG-badge node's
 * {@code setSvgShape} with the new color baked in. Without that step
 * the badge URI keeps the original color (the URI is rendered once at
 * parse time) and the {@code Apply Tag Colors} / {@code Apply
 * NodeType Colors} buttons stay visually inert on the Cytoscape
 * viewer.</p>
 */
class GraphNodeRecolorSvgShapeTest {

    @Test
    void recolorSvgShapeChangesStoredColorAndRegeneratesImage() {
        GraphNode n = new GraphNode("BatchReader-1", List.of("Class"),
                Map.of("name", "Foo", "_nodeType_", "BatchReader"));
        n.setSvgShape("Foo", "C", "#00FFFF");

        Map<String, String> before = n.getSvgImage();
        assertEquals("#00FFFF", before.get("color"));
        String oldImage = n.toVisNetworkData().get("image").toString();

        boolean changed = n.recolorSvgShape("#FF00FF");
        assertTrue(changed, "recolorSvgShape must return true when the color actually changed");

        Map<String, String> after = n.getSvgImage();
        assertEquals("#FF00FF", after.get("color"),
                "svgImage.color must reflect the new color after recolor");

        String newImage = n.toVisNetworkData().get("image").toString();
        assertNotEquals(oldImage, newImage,
                "the pre-rendered image URI must change when the badge color changes");
        // Decode the base64 data URI and look for the new color in the
        // raw SVG body — the URI itself is base64-encoded and will not
        // contain the color in plaintext.
        String decodedSvg = decodeSvgDataUri(newImage);
        assertTrue(decodedSvg.contains("#FF00FF") || decodedSvg.contains("#ff00ff"),
                "the new SVG body must encode the new color; decoded: "
                        + decodedSvg.substring(0, Math.min(80, decodedSvg.length())));
    }

    /** Decode either a base64 or URL-encoded data:image/svg+xml URI. */
    private static String decodeSvgDataUri(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) throw new AssertionError("malformed data URI: " + uri);
        String payload = uri.substring(comma + 1);
        if (uri.startsWith("data:image/svg+xml;base64,")) {
            return new String(java.util.Base64.getDecoder().decode(payload),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return java.net.URLDecoder.decode(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void recolorSvgShapeIsNoOpWhenColorUnchanged() {
        GraphNode n = new GraphNode("n", List.of("Class"),
                Map.of("name", "Foo", "_nodeType_", "BatchReader"));
        n.setSvgShape("Foo", "C", "#00FFFF");
        String beforeImage = n.toVisNetworkData().get("image").toString();

        boolean changed = n.recolorSvgShape("#00FFFF");
        assertFalse(changed, "recolorSvgShape must return false when the color is unchanged");
        assertEquals(beforeImage, n.toVisNetworkData().get("image").toString(),
                "image URI must NOT change when the color is unchanged");
    }

    @Test
    void recolorSvgShapeReturnsFalseOnNodesWithoutSvgShape() {
        // Plain node — no setSvgShape was ever called.
        GraphNode n = new GraphNode("plain", List.of("Class"),
                Map.of("name", "Plain", "_nodeType_", "Class"));
        boolean changed = n.recolorSvgShape("#FF00FF");
        assertFalse(changed, "recolorSvgShape must return false when the node has no svgImage");
        assertNull(n.getSvgImage(), "non-badge nodes must continue to report no svgImage");
    }

    @Test
    void recolorSvgShapeNullColorFallsBackToDefault() {
        // Walk the same path recolorSvgShape() walks internally:
        // setSvgShape with an explicit "#4A90E2" (the documented default)
        // then recolor with null. The result must be the documented
        // default color — NOT the previous color and NOT absent.
        GraphNode n = new GraphNode("n", List.of("Class"),
                Map.of("name", "Foo", "_nodeType_", "BatchReader"));
        n.setSvgShape("Foo", "C", "#FF00FF");

        boolean changed = n.recolorSvgShape(null);
        assertTrue(changed, "recolorSvgShape(null) must always count as a change");
        assertEquals("#4A90E2", n.getSvgImage().get("color"),
                "recolorSvgShape(null) must restore the documented default badge color");
    }

    @Test
    void recolorSvgShapeThenCytoscapeSerializationUsesNewColor() {
        // After recolor, toCytoscapeNode() must produce a data.image URI
        // whose decoded SVG body contains the NEW color (the Cytoscape
        // surface re-renders from svgImage.color).
        GraphNode n = new GraphNode("n", List.of("Class"),
                Map.of("name", "Foo", "_nodeType_", "BatchReader"));
        n.setSvgShape("Foo", "C", "#00FFFF");
        n.recolorSvgShape("#FF00FF");

        String cytoscapeImage = (String)
                ((Map<String, Object>) n.toCytoscapeNode().get("data")).get("image");
        assertNotNull(cytoscapeImage);
        String body = decodeDataUri(cytoscapeImage);
        assertTrue(body.contains("fill=\"#FF00FF\""),
                "decoded Cytoscape SVG must contain the new color; got: "
                        + body.substring(0, Math.min(120, body.length())));
    }

    @Test
    void getSvgImageReturnsLiveDescriptorAfterRecolor() {
        // The descriptor returned by getSvgImage() is the LIVE map
        // (recolorSvgShape mutates it). Tests below guard the contract
        // because the Cytoscape bridge relies on it for color updates.
        GraphNode n = new GraphNode("n", List.of("Class"),
                Map.of("name", "Foo", "_nodeType_", "BatchReader"));
        n.setSvgShape("Foo", "C", "#00FFFF");
        Map<String, String> snap1 = n.getSvgImage();
        n.recolorSvgShape("#FF00FF");
        Map<String, String> snap2 = n.getSvgImage();
        assertEquals(snap1.get("label"), snap2.get("label"));
        assertEquals(snap1.get("type"), snap2.get("type"));
        assertEquals("#FF00FF", snap2.get("color"));
    }

    private static String decodeDataUri(String uri) {
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
