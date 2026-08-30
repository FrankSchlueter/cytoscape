package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the color-update pipeline that drives
 * {@code GraphConfigurationDialog}'s "Apply NodeType Colors" and "Apply
 * Tag Colors" buttons. Validates that:
 *
 * <ul>
 *   <li>{@link SvgBadgeColorUpdater} resolves a node's effective color
 *       through the {@code globalTagColors} → {@code tagColors} →
 *       {@code labelColors} priority chain.</li>
 *   <li>When the resolved color differs from the badge's current color,
 *       the helper regenerates the Cytoscape {@code data:image} URI and
 *       produces an {@link SvgBadgeColorUpdater.ImageUpdate} entry.</li>
 *   <li>When the color is unchanged or no rule applies, no update is
 *       emitted (prevents unnecessary Cytoscape redraws).</li>
 *   <li>Non-badge nodes are never touched.</li>
 * </ul>
 */
class SvgBadgeColorUpdaterTest {

    private static GraphNode makeBadgeNode(String id, String nodeType, String label,
                                           Map<String, Object> extraProps, String color) {
        // Construct a node whose `_nodeType_` property plus a custom
        // node-label together exercise the nodeType resolution path.
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("name", label);
        if (nodeType != null) props.put("_nodeType_", nodeType);
        props.putAll(extraProps);
        GraphNode n = new GraphNode(id, List.of(nodeType == null ? "Node" : nodeType), props);
        n.setSvgShape(label, "C", color);
        return n;
    }

    @Test
    void labelColorRecolorEmitsUpdateWhenColorChanges() {
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of("product", "Rente"), "#00FFFF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .build();

        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.applyRecolors(data, cfg);

        assertEquals(1, updates.size(), "one update expected for the label-color override");
        SvgBadgeColorUpdater.ImageUpdate upd = updates.get(0);
        assertEquals("r1", upd.id);
        assertNotNull(upd.image);
        assertTrue(upd.image.startsWith("data:image/svg+xml;"),
                "update image must be a Cytoscape-compatible data URI");

        // The SVG body decoded must contain the NEW color, not the old.
        String body = decodeSvg(upd.image);
        assertTrue(body.contains("fill=\"#FF00FF\""),
                "re-rendered SVG must contain the new label color");
        assertEquals("#FF00FF", reader.getSvgImage().get("color"),
                "node must remember the new color after applyRecolors");
    }

    @Test
    void applyRecolorsIsNoOpWhenColorMatchesCurrent() {
        // The node's badge color already equals the override — must NOT
        // emit an update (would trigger an unnecessary Cytoscape redraw).
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of("product", "Rente"), "#FF00FF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .build();

        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.applyRecolors(data, cfg);
        assertTrue(updates.isEmpty(),
                "no update must be emitted when the resolved color matches the current one");
    }

    @Test
    void globalTagColorsWinOverLabelColors() {
        // nodeType → BatchReader → label color #00FFFF (default-ish).
        // Global tag color product=Rente → #FF00FF (must win).
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of("product", "Rente"), "#00FFFF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#00FFFF")
                .globalTagColors(Map.of("product", Map.of("Rente", "#FF00FF")))
                .build();

        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.applyRecolors(data, cfg);

        assertEquals(1, updates.size());
        String body = decodeSvg(updates.get(0).image);
        assertTrue(body.contains("fill=\"#FF00FF\""),
                "globalTagColors must take precedence over labelColors");
    }

    @Test
    void appliesToEveryBadgeNodeInTheGraph() {
        GraphNode r1 = makeBadgeNode("r1", "BatchReader", "R1",
                Map.of("product", "Rente"), "#00FFFF");
        GraphNode r2 = makeBadgeNode("r2", "BatchWriter", "R2",
                Map.of("product", "Rente"), "#00FFFF");
        // Plain (non-badge) node — must NOT receive an update.
        GraphNode plain = new GraphNode("plain", List.of("Class"),
                Map.of("name", "Plain", "_nodeType_", "Class"));
        GraphData data = new GraphData(List.of(r1, r2, plain), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .globalTagColors(Map.of("product", Map.of("Rente", "#FF00FF")))
                .build();

        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.applyRecolors(data, cfg);
        assertEquals(2, updates.size(),
                "only the two badge nodes should be recolored");
        assertTrue(updates.stream().anyMatch(u -> u.id.equals("r1")));
        assertTrue(updates.stream().anyMatch(u -> u.id.equals("r2")));
        assertTrue(updates.stream().noneMatch(u -> u.id.equals("plain")),
                "non-badge nodes must never receive an update");
    }

    @Test
    void unknownNodeTypeOrUnmatchedPropertyEmitsNoUpdate() {
        // Node has _nodeType_="Other" and the config knows BatchReader.
        GraphNode reader = makeBadgeNode("r1", "Other", "Reader",
                Map.of("product", "Rente"), "#00FFFF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .globalTagColors(Map.of("product", Map.of("INVALID", "#000000")))
                .build();

        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.applyRecolors(data, cfg);
        assertTrue(updates.isEmpty(),
                "no rule applies → no update must be emitted");
    }

    @Test
    void nodeTypeDefaultsToFirstLabelWhenPropertyAbsent() {
        // No `_nodeType_` property — fallback to the first label
        // ("BatchReader"), which IS in the config.
        GraphNode reader = makeBadgeNode("r1", null, "Reader",
                Map.of("product", "Rente"), "#00FFFF");
        // re-tag the labels manually because makeBadgeNode fell back to "Node"
        // when nodeType was null:
        GraphNode n = new GraphNode("r1", List.of("BatchReader"),
                Map.of("name", "Reader", "product", "Rente"));
        n.setSvgShape("Reader", "C", "#00FFFF");

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .build();

        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.applyRecolors(new GraphData(List.of(n), List.of()), cfg);
        assertEquals(1, updates.size(),
                "nodeType must resolve to the first label when _nodeType_ is missing");
        String body = decodeSvg(updates.get(0).image);
        assertTrue(body.contains("fill=\"#FF00FF\""));
    }

    @Test
    void internalCytoscapePropertiesDoNotParticipateInTagMatching() {
        // The node carries a value for the internal "nodeTag" property
        // that happens to match a globalTagColors entry. We must NOT
        // match on it — nodeTag is internal and never user-meaningful.
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of("nodeTag", "entity", "id", "entity"), "#00FFFF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .globalTagColors(Map.of("nodeTag", Map.of("entity", "#FF00FF")))
                .build();

        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.applyRecolors(data, cfg);
        assertTrue(updates.isEmpty(),
                "internal properties (nodeTag, id, label, ...) must not trigger tag matching");
    }

    @Test
    void previewRecolorsEmitsUpdatesWithoutMutatingNodeState() {
        // previewRecolors is used by tests / debug tooling — must NOT
        // mutate the node, only emit the would-be update list.
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of(), "#00FFFF");
        String imageBefore = reader.toVisNetworkData().get("image").toString();
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .build();
        List<SvgBadgeColorUpdater.ImageUpdate> updates =
                SvgBadgeColorUpdater.previewRecolors(data, cfg);
        assertEquals(1, updates.size());
        assertEquals("#00FFFF", reader.getSvgImage().get("color"),
                "previewRecolors must NOT mutate the node's stored color");
        assertEquals(imageBefore, reader.toVisNetworkData().get("image").toString(),
                "previewRecolors must NOT regenerate the image");
    }

    @Test
    void toJsonUpdatesPreservesIdThenImageOrder() {
        // The bridge sends the JSON via Gson; the JS handler reads
        // {id, image}. LinkedHashMap keeps insertion order so the JSON
        // keys are stable for downstream diffing.
        SvgBadgeColorUpdater.ImageUpdate u =
                new SvgBadgeColorUpdater.ImageUpdate("n1", "data:...");
        List<Map<String, Object>> json = SvgBadgeColorUpdater.toJsonUpdates(List.of(u));
        assertEquals("n1", json.get(0).get("id"));
        assertEquals("data:...", json.get(0).get("image"));
    }

    @Test
    void nullDataOrNullConfigIsSafe() {
        // Both must be tolerated without throwing — the bridge calls
        // applyRecolors on every applyNodeConfig and the graph may not
        // be set yet.
        assertNotNull(SvgBadgeColorUpdater.applyRecolors(null, NodeConfig.defaults()));
        assertNotNull(SvgBadgeColorUpdater.applyRecolors(
                new GraphData(List.of(), List.of()), null));
        // Both lists must be empty:
        assertEquals(0, SvgBadgeColorUpdater.applyRecolors(null, NodeConfig.defaults()).size());
        assertEquals(0, SvgBadgeColorUpdater.applyRecolors(
                new GraphData(List.of(), List.of()), null).size());
    }

    // ----- applyRecolorsBoth (mixed update format for vis-network) -----

    @Test
    void applyRecolorsBoth_PlainNodeProducesColorUpdate() {
        // A plain (non-badge) node must emit a color.background update so
        // vis-network's ellipse/box/etc. shape picks it up. vis-network has
        // no stylesheet engine like Cytoscape, so the color has to ride
        // on the node itself.
        GraphNode plain = new GraphNode("plain", List.of("Class"),
                Map.of("name", "Plain", "_nodeType_", "Class"));
        GraphData data = new GraphData(List.of(plain), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("Class", "#FF0000")
                .build();

        List<Map<String, Object>> updates =
                SvgBadgeColorUpdater.applyRecolorsBoth(data, cfg);
        assertEquals(1, updates.size());
        Map<String, Object> upd = updates.get(0);
        assertEquals("plain", upd.get("id"));
        // No image key — plain nodes don't carry an SVG badge.
        assertFalse(upd.containsKey("image"));
        // Color key must carry both background and border.
        @SuppressWarnings("unchecked")
        Map<String, Object> color = (Map<String, Object>) upd.get("color");
        assertNotNull(color, "plain-node update must carry a color object");
        assertEquals("#FF0000", color.get("background"));
        assertEquals("#FF0000", color.get("border"));
    }

    @Test
    void applyRecolorsBoth_BadgeNodeProducesBase64ImageUpdate() {
        // A badge node must emit an image update with the freshly-rendered
        // base64 data URI (same format Cytoscape consumes — see
        // SvgBadgeColorUpdater.applyRecolors / GraphNode.toSvgDataUri).
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of("product", "Rente"), "#00FFFF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .build();

        List<Map<String, Object>> updates =
                SvgBadgeColorUpdater.applyRecolorsBoth(data, cfg);
        assertEquals(1, updates.size());
        Map<String, Object> upd = updates.get(0);
        assertEquals("r1", upd.get("id"));
        assertFalse(upd.containsKey("color"),
                "badge-node update must not carry a color key");
        String image = (String) upd.get("image");
        assertNotNull(image, "badge-node update must carry an image");
        assertTrue(image.startsWith("data:image/svg+xml;base64,"),
                "badge-node image must be a base64 data URI for vis-network to render");
        String decoded = decodeSvg(image);
        assertTrue(decoded.contains("fill=\"#FF00FF\""),
                "decoded SVG must contain the new color; body was: "
                        + decoded.substring(0, Math.min(80, decoded.length())));
        // And the node's stored svgImage.color must reflect the new color.
        assertEquals("#FF00FF", reader.getSvgImage().get("color"),
                "recolorSvgShape must mutate svgImage.color as a side effect");
    }

    @Test
    void applyRecolorsBoth_MixedNodes() {
        // Mix of badge and plain nodes — both must be recolored, each
        // with its own update format.
        GraphNode badge = makeBadgeNode("b1", "BatchReader", "B",
                Map.of("product", "Rente"), "#00FFFF");
        GraphNode plain = new GraphNode("plain", List.of("Class"),
                Map.of("name", "Plain", "_nodeType_", "Class"));
        GraphData data = new GraphData(List.of(badge, plain), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .labelColor("Class", "#0000FF")
                .build();

        List<Map<String, Object>> updates =
                SvgBadgeColorUpdater.applyRecolorsBoth(data, cfg);
        assertEquals(2, updates.size());
        // Locate each update by id and verify the shape-specific payload.
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> u : updates) byId.put((String) u.get("id"), u);
        Map<String, Object> badgeUpdate = byId.get("b1");
        Map<String, Object> plainUpdate = byId.get("plain");
        assertNotNull(badgeUpdate, "badge node update missing");
        assertNotNull(plainUpdate, "plain node update missing");
        assertTrue(badgeUpdate.containsKey("image"));
        assertFalse(badgeUpdate.containsKey("color"));
        assertTrue(plainUpdate.containsKey("color"));
        assertFalse(plainUpdate.containsKey("image"));
        @SuppressWarnings("unchecked")
        Map<String, Object> plainColor =
                (Map<String, Object>) plainUpdate.get("color");
        assertEquals("#0000FF", plainColor.get("background"));
    }

    @Test
    void applyRecolorsBoth_NoMatchEmitsNoUpdates() {
        // No rule resolves → no updates. Same null-safe contract as
        // applyRecolors.
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of("product", "Rente"), "#00FFFF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("Other", "#FF00FF")
                .globalTagColors(Map.of("product", Map.of("INVALID", "#000000")))
                .build();

        List<Map<String, Object>> updates =
                SvgBadgeColorUpdater.applyRecolorsBoth(data, cfg);
        assertTrue(updates.isEmpty(),
                "no rule applies → applyRecolorsBoth must emit no updates");
    }

    @Test
    void applyRecolorsBoth_ColorUnchangedEmitsNoUpdate() {
        // The resolved color equals the node's current svgImage.color — no
        // emission. Important to avoid needless vis-network redraws.
        GraphNode reader = makeBadgeNode("r1", "BatchReader", "Reader",
                Map.of("product", "Rente"), "#FF00FF");
        GraphData data = new GraphData(List.of(reader), List.of());

        NodeConfig cfg = NodeConfig.defaults().toBuilder()
                .labelColor("BatchReader", "#FF00FF")
                .build();

        List<Map<String, Object>> updates =
                SvgBadgeColorUpdater.applyRecolorsBoth(data, cfg);
        assertTrue(updates.isEmpty(),
                "no color change → applyRecolorsBoth must emit no updates");
    }

    @Test
    void applyRecolorsBoth_NullSafe() {
        assertNotNull(SvgBadgeColorUpdater.applyRecolorsBoth(null, NodeConfig.defaults()));
        assertNotNull(SvgBadgeColorUpdater.applyRecolorsBoth(
                new GraphData(List.of(), List.of()), null));
        assertEquals(0, SvgBadgeColorUpdater.applyRecolorsBoth(null, NodeConfig.defaults()).size());
        assertEquals(0, SvgBadgeColorUpdater.applyRecolorsBoth(
                new GraphData(List.of(), List.of()), null).size());
    }

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
