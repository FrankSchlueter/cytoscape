package de.tk.dependencyanalyse.rapui.visgraph.examples;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the contract of the {@link NvlIconEntryPoint} demo graph:
 * 20 nodes (10 labeled + 10 unlabeled) / 40 edges, 10 distinct
 * background colors per batch, 8 SVG icons used cyclically in batch 1
 * and in shuffled order in batch 2, 10 nodes with annotation
 * characters (5 per batch).
 *
 * <p>Pure unit test — exercises the {@code buildDemoGraph()} factory
 * without spinning up the RAP Browser widget.</p>
 */
class NvlIconEntryPointTest {

    @Test
    void buildsTwentyNodesAndFortyEdges() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        assertEquals(20, data.getNodes().size(), "demo must have 20 nodes (2 batches of 10)");
        assertEquals(40, data.getRelationships().size(), "demo must have 40 edges (2 per node)");
    }

    @Test
    void everyNodeHasSvgOverlayDescriptor() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        for (GraphNode n : data.getNodes()) {
            assertTrue(n.hasSvgOverlay(),
                    "node " + n.getId() + " must carry an overlay icon");
        }
    }

    @Test
    void tenNodesCarryAnnotationTenDoNot() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        int annotated = 0;
        int plain = 0;
        for (GraphNode n : data.getNodes()) {
            Map<String, Object> overlay = n.getSvgOverlay();
            assertNotNull(overlay);
            String type = String.valueOf(overlay.get("type"));
            if (" ".equals(type)) {
                plain++;
            } else {
                annotated++;
            }
        }
        assertEquals(10, annotated,
                "first 5 nodes per batch must carry an annotation character (10 total)");
        assertEquals(10, plain,
                "remaining 5 nodes per batch must NOT carry an annotation (10 total)");
    }

    @Test
    void everyNodeHasDistinctBackgroundColor() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        Set<String> colors = new HashSet<>();
        for (GraphNode n : data.getNodes()) {
            Object raw = n.toNvlNode().get("color");
            assertNotNull(raw,
                    "node " + n.getId() + " must carry a NVL color");
            colors.add(String.valueOf(raw));
        }
        assertEquals(20, colors.size(),
                "all 20 nodes must use distinct background colors, got: " + colors);
    }

    @Test
    void annotationColorsAreIndependentFromNodeColors() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        for (GraphNode n : data.getNodes()) {
            Map<String, Object> overlay = n.getSvgOverlay();
            Object annotationColorRaw = overlay.get("circleBackgroundColor");
            if (annotationColorRaw == null) continue;   // not an annotated node
            String nodeColor = String.valueOf(n.toNvlNode().get("color"));
            String annotationColor = String.valueOf(annotationColorRaw);
            assertTrue(!nodeColor.equalsIgnoreCase(annotationColor),
                    "node " + n.getId() + " has identical node + annotation color "
                            + nodeColor + " — must be independent");
        }
    }

    @Test
    void allEightIconsAreUsedAtLeastOnce() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        Set<String> icons = new HashSet<>();
        for (GraphNode n : data.getNodes()) {
            icons.add(String.valueOf(n.getSvgOverlay().get("iconName")));
        }
        assertEquals(NvlIconEntryPoint.ICON_FILES.length, icons.size(),
                "every icon in ICON_FILES must appear at least once, got: " + icons);
        for (String icon : NvlIconEntryPoint.ICON_FILES) {
            assertTrue(icons.contains(icon),
                    "icon " + icon + " must appear in the demo graph");
        }
    }

    @Test
    void iconNamesMatchCyclicAssignmentInBatch1() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        List<GraphNode> nodes = data.getNodes();
        for (int i = 0; i < NvlIconEntryPoint.BATCH_SIZE; i++) {
            String expected = NvlIconEntryPoint.ICON_FILES[i % NvlIconEntryPoint.ICON_FILES.length];
            String actual = String.valueOf(nodes.get(i).getSvgOverlay().get("iconName"));
            assertEquals(expected, actual,
                    "batch-1 node " + i + " must use icon " + expected);
        }
    }

    @Test
    void iconNamesMatchShuffledOrderInBatch2() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        List<GraphNode> nodes = data.getNodes();
        for (int i = 0; i < NvlIconEntryPoint.BATCH_SIZE; i++) {
            int orderIdx = i % NvlIconEntryPoint.BATCH2_ICON_ORDER.length;
            String expected = NvlIconEntryPoint.ICON_FILES[NvlIconEntryPoint.BATCH2_ICON_ORDER[orderIdx]];
            String actual = String.valueOf(
                    nodes.get(NvlIconEntryPoint.BATCH_SIZE + i).getSvgOverlay().get("iconName"));
            assertEquals(expected, actual,
                    "batch-2 node " + (NvlIconEntryPoint.BATCH_SIZE + i)
                            + " must use icon " + expected);
        }
    }

    @Test
    void toNvlNodeSerializesOverlayForEveryNode() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        for (GraphNode n : data.getNodes()) {
            Map<String, Object> nvl = n.toNvlNode();
            assertNotNull(nvl.get("overlayIcon"),
                    "node " + n.getId() + " must serialize overlayIcon for NVL");
            Map<String, Object> overlay = (Map<String, Object>) nvl.get("overlayIcon");
            assertNotNull(overlay.get("url"));
            assertEquals(0.7, ((Number) overlay.get("size")).doubleValue(), 1e-9);
            assertNotNull(overlay.get("position"),
                    "every overlay must carry a position [x, y] tuple");
        }
    }

    @Test
    void batch1NodesAreLabeledAndShiftIconUp() {
        // Nodes n0…n9 carry an explicit caption via setCaption(). The
        // serializer must therefore emit captionAlign: "bottom" +
        // overlayIcon.position: [0, -0.5] so the icon doesn't overlap
        // the caption text below it.
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        for (int i = 0; i < NvlIconEntryPoint.BATCH_SIZE; i++) {
            GraphNode n = data.getNodes().get(i);
            Map<String, Object> nvl = n.toNvlNode();
            assertEquals("bottom", nvl.get("captionAlign"),
                    "batch-1 node n" + i + " must emit captionAlign='bottom' (has caption)");
            Map<String, Object> overlay = (Map<String, Object>) nvl.get("overlayIcon");
            java.util.List<?> pos = (java.util.List<?>) overlay.get("position");
            assertEquals(0.0, ((Number) pos.get(0)).doubleValue(), 1e-9);
            assertEquals(-0.5, ((Number) pos.get(1)).doubleValue(), 1e-9,
                    "batch-1 node n" + i + " must shift icon up to make room for caption");
        }
    }

    @Test
    void batch2NodesAreUnlabeledAndCenterIcon() {
        // Nodes n10…n19 have no caption. The serializer must therefore
        // NOT emit captionAlign and the icon position stays at [0, 0]
        // so the icon sits centered on the node (no need to shift up
        // to make room for a non-existent caption).
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        for (int i = 0; i < NvlIconEntryPoint.BATCH_SIZE; i++) {
            GraphNode n = data.getNodes().get(NvlIconEntryPoint.BATCH_SIZE + i);
            Map<String, Object> nvl = n.toNvlNode();
            assertFalse(nvl.containsKey("captionAlign"),
                    "batch-2 node " + n.getId()
                            + " must NOT emit captionAlign (no caption)");
            Map<String, Object> overlay = (Map<String, Object>) nvl.get("overlayIcon");
            java.util.List<?> pos = (java.util.List<?>) overlay.get("position");
            assertEquals(0.0, ((Number) pos.get(0)).doubleValue(), 1e-9);
            assertEquals(0.0, ((Number) pos.get(1)).doubleValue(), 1e-9,
                    "batch-2 node " + n.getId()
                            + " must keep icon centered (no caption)");
        }
    }

    /* ============== Color Palette (GraphData.setColorPalette) ============== */

    /** Build the demo graph + attach a 10-entry palette via {@link GraphData#setColorPalette}. */
    private static GraphData buildDemoGraphWithPalette() {
        GraphData data = NvlIconEntryPoint.buildDemoGraph();
        Map<String, String> palette = new LinkedHashMap<>();
        for (int i = 0; i < NvlIconEntryPoint.BATCH_SIZE; i++) {
            palette.put("n" + i, NvlIconEntryPoint.NODE_BG_COLORS[i]);
        }
        return data.setColorPalette(palette);
    }

    @Test
    void colorPaletteIsStoredOnGraphData() {
        GraphData baseline = NvlIconEntryPoint.buildDemoGraph();
        assertNotNull(baseline.getColorPalette(),
                "every GraphData must expose a (possibly empty) palette map");
        assertTrue(baseline.getColorPalette().isEmpty(),
                "freshly built graph must carry an empty palette by default");

        GraphData withPalette = buildDemoGraphWithPalette();
        Map<String, String> palette = withPalette.getColorPalette();
        assertEquals(10, palette.size(),
                "demo palette must contain one entry per batch-1 node");
        for (int i = 0; i < NvlIconEntryPoint.BATCH_SIZE; i++) {
            String id = "n" + i;
            assertEquals(NvlIconEntryPoint.NODE_BG_COLORS[i], palette.get(id),
                    "palette entry for " + id + " must mirror NODE_BG_COLORS");
        }

        GraphData cleared = withPalette.setColorPalette(null);
        assertNotNull(cleared.getColorPalette());
        assertTrue(cleared.getColorPalette().isEmpty(),
                "setColorPalette(null) must reset the palette to an empty map");
        GraphData cleared2 = withPalette.setColorPalette(Map.of());
        assertTrue(cleared2.getColorPalette().isEmpty(),
                "setColorPalette(Map.of()) must reset the palette to an empty map");
    }

    @Test
    void colorPaletteFlowsThroughViewerIntoNvlBridge() throws IOException {
        // Source-level regression guards: every link in the palette
        // pipeline must exist so that the demo's Color Palette panel
        // appears after GraphData.setColorPalette(...) + setGraphData(...).
        Path nvlViewerSrc = Paths.get(
                "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/Neo4jNvlViewer.java");
        String nvlViewer = Files.exists(nvlViewerSrc)
                ? new String(Files.readAllBytes(nvlViewerSrc), StandardCharsets.UTF_8)
                : "";
        assertFalse(nvlViewer.isEmpty(), "Neo4jNvlViewer.java must be readable for source-level guards");
        int idx = nvlViewer.indexOf("public void setGraphData(");
        assertTrue(idx > 0, "Neo4jNvlViewer.setGraphData(...) must exist");
        int end = nvlViewer.indexOf("public GraphData getGraphData()", idx);
        String body = nvlViewer.substring(idx, end);
        assertTrue(body.contains("data.getColorPalette()"),
                "Neo4jNvlViewer.setGraphData must read the palette from data.getColorPalette()");
        // The graph-attached palette routes through bridge.applyGraphPalette(...)
        // (NOT bridge.setLeidenColors(...)) so the original map keys survive as
        // legend row labels instead of being renamed to "ClusterN" by the
        // Leiden resolver.
        assertTrue(body.contains("bridge.applyGraphPalette("),
                "Neo4jNvlViewer.setGraphData must push the palette via bridge.applyGraphPalette(...) "
                        + "so the NVL Color Palette panel appears with the caller's labels");

        Path nvlBridgeSrc = Paths.get(
                "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/NvlJsBridge.java");
        String nvlBridge = Files.exists(nvlBridgeSrc)
                ? new String(Files.readAllBytes(nvlBridgeSrc), StandardCharsets.UTF_8)
                : "";
        assertFalse(nvlBridge.isEmpty(), "NvlJsBridge.java must be readable for source-level guards");
        int bridIdx = nvlBridge.indexOf("public void setLeidenColors(");
        assertTrue(bridIdx > 0, "NvlJsBridge.setLeidenColors(...) must exist");
        int bridEnd = nvlBridge.indexOf("public void applyNodeColors(", bridIdx);
        String bridBody = nvlBridge.substring(bridIdx, bridEnd);
        assertTrue(bridBody.contains("refreshPalette("),
                "NvlJsBridge.setLeidenColors must invoke refreshPalette() "
                        + "so the palette panel is auto-shown");
        assertTrue(bridBody.contains("window.vgv_applyLeidenColors("),
                "NvlJsBridge.setLeidenColors must push vgv_applyLeidenColors to the iframe");
        // applyGraphPalette must also exist and route through refreshPalette.
        assertTrue(nvlBridge.contains("public void applyGraphPalette("),
                "NvlJsBridge.applyGraphPalette(...) must exist so the graph-attached palette "
                        + "is pushed without going through the Leiden resolver");
        int gpIdx = nvlBridge.indexOf("public void applyGraphPalette(");
        int gpEnd = nvlBridge.indexOf("public void clear(", gpIdx);
        String gpBody = nvlBridge.substring(gpIdx, gpEnd);
        assertTrue(gpBody.contains("refreshPalette("),
                "NvlJsBridge.applyGraphPalette must invoke refreshPalette() "
                        + "so the panel re-derives entries from the cached maps");

        Path nvlJs = Paths.get("src/main/resources/static/nvl/nvl-graph-viewer.js");
        String nvlJsSrc = Files.exists(nvlJs)
                ? new String(Files.readAllBytes(nvlJs), StandardCharsets.UTF_8)
                : "";
        assertFalse(nvlJsSrc.isEmpty(), "nvl-graph-viewer.js must be readable");
        assertTrue(nvlJsSrc.contains("window.vgv_applyColorPalette"),
                "nvl-graph-viewer.js must register window.vgv_applyColorPalette "
                        + "to render the Color Palette panel");
        assertTrue(nvlJsSrc.contains("window.vgv_hideColorPalette"),
                "nvl-graph-viewer.js must register window.vgv_hideColorPalette "
                        + "so clear() can hide the panel");
    }

    @Test
    void nvlIconDemoGraphDeclaresNonEmptyColorPalette() {
        GraphData data = buildDemoGraphWithPalette();
        Map<String, String> palette = data.getColorPalette();
        assertTrue(palette.size() >= 10,
                "demo palette must contain at least 10 entries, got: " + palette.size());
        // Every key must be a node id from the demo graph.
        Set<String> nodeIds = new HashSet<>();
        for (GraphNode n : data.getNodes()) {
            nodeIds.add(n.getId());
        }
        for (String key : palette.keySet()) {
            assertTrue(nodeIds.contains(key),
                    "palette key " + key + " must reference a real demo node id");
        }
        // Every value must look like a hex color (#RRGGBB).
        for (Map.Entry<String, String> e : palette.entrySet()) {
            String hex = e.getValue();
            assertNotNull(hex, "palette value for " + e.getKey() + " must not be null");
            assertTrue(hex.matches("#[0-9A-Fa-f]{6}"),
                    "palette value " + hex + " must be a #RRGGBB hex string");
        }
    }
}