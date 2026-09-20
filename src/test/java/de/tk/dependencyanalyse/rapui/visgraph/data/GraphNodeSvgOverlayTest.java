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
 * Tests for the {@link GraphNode#setSvgOverlayIcon(String)} and
 * {@link GraphNode#setSvgOverlayIcon(String, char, String)} API plus the
 * matching {@link GraphNode#toNvlNode()} serialization of the NVL
 * {@code overlayIcon} payload.
 */
class GraphNodeSvgOverlayTest {

    @Test
    void hasSvgOverlayFalseByDefault() {
        GraphNode n = new GraphNode("n1", "Node", List.of(), Map.of());
        assertFalse(n.hasSvgOverlay());
        assertNull(n.getSvgOverlay());
    }

    @Test
    void setSvgOverlayIconStoresDescriptorAndRawSvg() {
        GraphNode n = new GraphNode("n1", "Node", List.of(), Map.of());
        n.setSvgOverlayIcon("java-16-svgrepo-com.svg");
        assertTrue(n.hasSvgOverlay());
        Map<String, Object> overlay = n.getSvgOverlay();
        assertNotNull(overlay);
        assertEquals("java-16-svgrepo-com.svg", overlay.get("iconName"));
        // No annotation: type stays as the serialized ' ' sentinel.
        assertEquals(" ", String.valueOf(overlay.get("type")));
        // rawSvg must be the transparent SVG body (no background rect).
        Object raw = overlay.get("rawSvg");
        assertNotNull(raw);
        assertTrue(raw.toString().contains("<svg"),
                "rawSvg must be a valid SVG body");
        assertFalse(raw.toString().contains("<rect"),
                "rawSvg must not contain a background <rect> (transparent)");
    }

    @Test
    void setSvgOverlayIconWithAnnotationStoresCharAndCircleColor() {
        GraphNode n = new GraphNode("n1", "Node", List.of(), Map.of());
        n.setSvgOverlayIcon("java-16-svgrepo-com.svg", 'X', "#FF6B6B");
        Map<String, Object> overlay = n.getSvgOverlay();
        assertNotNull(overlay);
        assertEquals("java-16-svgrepo-com.svg", overlay.get("iconName"));
        assertEquals("X", String.valueOf(overlay.get("type")));
        assertEquals("#FF6B6B", overlay.get("circleBackgroundColor"));
    }

    @Test
    void setSvgOverlayIconDelegatesToSetSvgIconForCytoscapeAndVis() {
        // Cytoscape and vis-network have no overlay-icon concept, so the
        // call must also populate the `image` attribute via setSvgIcon so
        // the existing composite-badge rendering is reused.
        GraphNode withAnnotation = new GraphNode("a", "Node", List.of(), Map.of());
        withAnnotation.setSvgOverlayIcon("java-16-svgrepo-com.svg", 'C', "#4ECDC4");

        Map<String, Object> cytoscape = withAnnotation.toCytoscapeNode();
        Map<String, Object> cytoscapeData = (Map<String, Object>) cytoscape.get("data");
        assertNotNull(cytoscapeData.get("image"),
                "Cytoscape serialization must carry an `image` attribute");

        Map<String, Object> vis = withAnnotation.toVisNetworkData();
        assertNotNull(vis.get("image"),
                "vis-network serialization must carry an `image` attribute");
        assertEquals("image", String.valueOf(vis.get("shape")),
                "vis-network shape must be set to 'image' for the badge");

        // Without annotation — image is still populated (white composite badge).
        GraphNode withoutAnnotation = new GraphNode("b", "Node", List.of(), Map.of());
        withoutAnnotation.setSvgOverlayIcon("java-16-svgrepo-com.svg");
        Map<String, Object> cytoscape2 = withoutAnnotation.toCytoscapeNode();
        assertNotNull(((Map<String, Object>) cytoscape2.get("data")).get("image"),
                "Cytoscape must carry image even when no annotation is set");
    }

    @Test
    void toNvlNodeEmitsOverlayIcon() {
        GraphNode n = new GraphNode("n1", "Node", List.of(), Map.of());
        n.setColor("#3498DB");
        n.setSvgOverlayIcon("java-16-svgrepo-com.svg", 'C', "#FF6B6B");

        Map<String, Object> nvl = n.toNvlNode();
        assertEquals("n1", nvl.get("id"));
        // The colored circle is shipped as the node color.
        assertEquals("#3498DB", nvl.get("color"));
        // The transparent overlay is shipped as overlayIcon.
        Map<String, Object> overlay = (Map<String, Object>) nvl.get("overlayIcon");
        assertNotNull(overlay,
                "toNvlNode must emit an overlayIcon entry when setSvgOverlayIcon was called");
        Object url = overlay.get("url");
        assertNotNull(url);
        assertTrue(url.toString().startsWith("data:image/svg+xml;base64,"),
                "overlayIcon.url must be a base64-encoded data URI, got: " + url);
        assertEquals(0.7, ((Number) overlay.get("size")).doubleValue(), 1e-9,
                "overlayIcon.size must be 0.7 (= 70% of node diameter)");
    }

    @Test
    void toNvlNodeOverlayIconIsShiftedUp() {
        // NVL's overlayIcon.position is in units of node-radius:
        //   position[0] = horizontal shift in radii (0 = centered)
        //   position[1] = vertical shift in radii (negative = up)
        // We emit position=[0, -0.5] when a caption is set so the
        // icon's vertical center sits 0.5·radius above the node center,
        // leaving clean room for the captionAlign="bottom" text below.
        // Without a caption, the icon stays centered (see
        // toNvlNodeCentersIconWhenNodeHasNoCaption).
        GraphNode n = new GraphNode("n1", "Node", List.of(), Map.of());
        n.setColor("#3498DB");
        n.setSvgOverlayIcon("java-16-svgrepo-com.svg");
        n.setCaption("Service");   // forces shifted-up position

        Map<String, Object> nvl = n.toNvlNode();
        Map<String, Object> overlay = (Map<String, Object>) nvl.get("overlayIcon");
        assertNotNull(overlay);
        java.util.List<?> position = (java.util.List<?>) overlay.get("position");
        assertNotNull(position, "overlayIcon.position must be emitted when caption is set");
        assertEquals(2, position.size(),
                "overlayIcon.position must be a [x, y] tuple");
        assertEquals(0.0, ((Number) position.get(0)).doubleValue(), 1e-9,
                "x offset must be 0 (horizontal center)");
        assertEquals(-0.5, ((Number) position.get(1)).doubleValue(), 1e-9,
                "y offset must be -0.5 (= 0.5 radii up) so the icon sits above the caption");
    }

    @Test
    void toNvlNodeOmitsOverlayIconWhenNotSet() {
        GraphNode n = new GraphNode("n1", "Node", List.of(), Map.of());
        n.setColor("#3498DB");
        Map<String, Object> nvl = n.toNvlNode();
        assertFalse(nvl.containsKey("overlayIcon"),
                "toNvlNode must not emit overlayIcon for a plain node");
    }

    @Test
    void toNvlNodeEmitsCaptionAlignBottomForOverlayNodesWithCaption() {
        // NVL renders the caption AFTER the overlayIcon in DOM order, so
        // the caption sits on top z-order-wise. Without captionAlign, NVL
        // defaults to "center", which would put the text right over the
        // icon and hide it. captionAlign="bottom" drops the caption below
        // the icon (text at y = nodeY + radius/π, icon centered on nodeY).
        // Only emitted when getCaption() != null — see
        // toNvlNodeCentersIconWhenNodeHasNoCaption for the no-caption case.
        GraphNode withAnnotation = new GraphNode("a", "Node", List.of(),
                Map.of("name", "Service"));  // provides caption via properties.name
        withAnnotation.setColor("#3498DB");
        withAnnotation.setSvgOverlayIcon("java-16-svgrepo-com.svg", 'C', "#FF6B6B");
        Map<String, Object> nvlAnnotated = withAnnotation.toNvlNode();
        assertEquals("bottom", nvlAnnotated.get("captionAlign"),
                "captionAlign must be 'bottom' when overlayIcon is set and caption exists");

        GraphNode withoutAnnotation = new GraphNode("b", "Node", List.of(),
                Map.of("name", "Plain"));
        withoutAnnotation.setColor("#3498DB");
        withoutAnnotation.setSvgOverlayIcon("java-16-svgrepo-com.svg");
        Map<String, Object> nvlPlain = withoutAnnotation.toNvlNode();
        assertEquals("bottom", nvlPlain.get("captionAlign"),
                "captionAlign must also be 'bottom' when overlayIcon is set without annotation");
    }

    @Test
    void toNvlNodeOmitsCaptionAlignForPlainNodes() {
        // Plain nodes (no overlayIcon) must NOT carry captionAlign so
        // NVL's default "center" positioning applies.
        GraphNode plain = new GraphNode("n1", "Node", List.of(), Map.of());
        plain.setColor("#3498DB");
        Map<String, Object> nvl = plain.toNvlNode();
        assertFalse(nvl.containsKey("captionAlign"),
                "plain nodes must not emit captionAlign so NVL's default ('center') applies");
    }

    @Test
    void toNvlNodeCentersIconWhenNodeHasNoCaption() {
        // getCaption() returns null → no caption text rendered by NVL.
        // The icon doesn't need to make room for a caption, so it stays
        // centered on the node (position: [0, 0]) and no captionAlign
        // field is emitted. Plain nodes without an overlayIcon are also
        // unaffected — only nodes with setSvgOverlayIcon participate.
        GraphNode unlabeled = new GraphNode("n1", "Node", List.of(), Map.of());
        unlabeled.setColor("#3498DB");
        unlabeled.setSvgOverlayIcon("java-16-svgrepo-com.svg");
        // No setCaption() call — getCaption() returns null.

        Map<String, Object> nvl = unlabeled.toNvlNode();
        Map<String, Object> overlay = (Map<String, Object>) nvl.get("overlayIcon");
        assertNotNull(overlay);
        java.util.List<?> position = (java.util.List<?>) overlay.get("position");
        assertNotNull(position);
        assertEquals(0.0, ((Number) position.get(0)).doubleValue(), 1e-9,
                "x offset must be 0 (horizontal center)");
        assertEquals(0.0, ((Number) position.get(1)).doubleValue(), 1e-9,
                "y offset must be 0 (vertical center) when no caption is set");
        assertFalse(nvl.containsKey("captionAlign"),
                "no captionAlign must be emitted when no caption text exists");
    }

    @Test
    void toNvlNodeShiftsIconUpWhenCaptionIsSet() {
        // Explicit caption via setCaption() → captionAlign: "bottom" +
        // overlayIcon.position: [0, -0.5] so the icon and text don't
        // overlap. This is the inverse of the unlabeled case.
        GraphNode labeled = new GraphNode("n1", "Node", List.of(), Map.of());
        labeled.setColor("#3498DB");
        labeled.setSvgOverlayIcon("java-16-svgrepo-com.svg");
        labeled.setCaption("Service");

        Map<String, Object> nvl = labeled.toNvlNode();
        assertEquals("bottom", nvl.get("captionAlign"),
                "captionAlign must be 'bottom' when caption is set");
        Map<String, Object> overlay = (Map<String, Object>) nvl.get("overlayIcon");
        java.util.List<?> position = (java.util.List<?>) overlay.get("position");
        assertEquals(0.0, ((Number) position.get(0)).doubleValue(), 1e-9);
        assertEquals(-0.5, ((Number) position.get(1)).doubleValue(), 1e-9,
                "y offset must be -0.5 (icon shifted up) when caption is set");
    }

    @Test
    void setSvgOverlayIconIgnoresMissingIcon() {
        // Missing icon file → no descriptor, no exception, the node stays plain.
        GraphNode n = new GraphNode("n1", "Node", List.of(), Map.of());
        n.setSvgOverlayIcon("does-not-exist.svg");
        assertFalse(n.hasSvgOverlay(),
                "missing icon must NOT populate the svgOverlay descriptor");
        Map<String, Object> nvl = n.toNvlNode();
        assertFalse(nvl.containsKey("overlayIcon"));
    }
}