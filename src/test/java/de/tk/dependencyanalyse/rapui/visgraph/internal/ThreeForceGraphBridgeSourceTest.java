package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level regression guards for the 3D Force-Directed Graph bridge
 * ({@code ThreeForceGraphJsBridge}). Mirror of the NVL/Sigma/Cytoscape
 * sections of {@link ColorPaletteBridgeTest}: every
 * {@code tfgv_applyColorPalette} / {@code tfgv_hideColorPalette}
 * contract is verified at the source level so a regression that drops
 * the panel updates would fail the build.
 */
class ThreeForceGraphBridgeSourceTest {

    private static final String[] POSSIBLE_BRIDGE = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/ThreeForceGraphJsBridge.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/internal/ThreeForceGraphJsBridge.java"
    };

    private static String readBridge() throws Exception {
        for (String p : POSSIBLE_BRIDGE) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("ThreeForceGraphJsBridge.java not found");
    }

    private static final String[] POSSIBLE_VIEWER = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/ThreeForceGraphViewer.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/ThreeForceGraphViewer.java"
    };

    private static String readViewer() throws Exception {
        for (String p : POSSIBLE_VIEWER) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("ThreeForceGraphViewer.java not found");
    }

    /* ---- Palette plumbing ---- */

    @Test
    void bridgePushesPaletteFromSetLeidenColors() throws Exception {
        String src = readBridge();
        int idx = src.indexOf("public void setLeidenColors(");
        assertTrue(idx > 0, "ThreeForceGraphJsBridge.setLeidenColors must exist");
        String body = src.substring(idx, src.indexOf("public void applyNodeColors(", idx));
        assertTrue(body.contains("window.tfgv_applyLeidenColors("),
                "ThreeForceGraphJsBridge.setLeidenColors must push tfgv_applyLeidenColors");
        assertTrue(body.contains("refreshPalette("),
                "ThreeForceGraphJsBridge.setLeidenColors must invoke refreshPalette()");
    }

    @Test
    void bridgePushesPaletteFromApplyNodeColors() throws Exception {
        String src = readBridge();
        int idx = src.indexOf("public void applyNodeColors(");
        assertTrue(idx > 0, "ThreeForceGraphJsBridge.applyNodeColors must exist");
        String body = src.substring(idx, src.indexOf("public void applyGraphPalette(", idx));
        assertTrue(body.contains("window.tfgv_applyNodeColors("),
                "ThreeForceGraphJsBridge.applyNodeColors must push tfgv_applyNodeColors");
        assertTrue(body.contains("refreshPalette("),
                "ThreeForceGraphJsBridge.applyNodeColors must invoke refreshPalette()");
    }

    @Test
    void bridgeClearHidesPalette() throws Exception {
        String src = readBridge();
        int idx = src.indexOf("public void clear(");
        assertTrue(idx > 0, "ThreeForceGraphJsBridge.clear() must exist");
        String body = src.substring(idx, src.indexOf("public void applyNodeImages(", idx));
        assertTrue(body.contains("window.tfgv_clear()"),
                "ThreeForceGraphJsBridge.clear() must push tfgv_clear()");
        assertTrue(body.contains("window.tfgv_hideColorPalette()"),
                "ThreeForceGraphJsBridge.clear() must hide the Color Palette");
        assertTrue(body.contains("paletteVisible = false"),
                "ThreeForceGraphJsBridge.clear() must reset the cached paletteVisible flag");
    }

    @Test
    void bridgeEmptyMapHidesPalette() throws Exception {
        String src = readBridge();
        int idx = src.indexOf("private void refreshPalette()");
        assertTrue(idx > 0, "ThreeForceGraphJsBridge.refreshPalette() must exist");
        String body = src.substring(idx, src.indexOf("private List<LegendEntry> derivePaletteEntries(", idx));
        assertTrue(body.contains("window.tfgv_hideColorPalette()"),
                "ThreeForceGraphJsBridge.refreshPalette() must hide the palette when no colors are set");
        assertTrue(body.contains("paletteVisible = false"),
                "ThreeForceGraphJsBridge.refreshPalette() must reset paletteVisible when hiding");
    }

    /* ---- Selection / context-menu callbacks ---- */

    @Test
    void bridgeRegistersNodeSelectionCallback() throws Exception {
        String src = readBridge();
        assertTrue(src.contains("FN_NODE_SELECTED"),
                "ThreeForceGraphJsBridge must register a node-selection BrowserFunction");
        assertTrue(src.contains("tfgv_notifyNodeSelected"),
                "BrowserFunction name must be tfgv_notifyNodeSelected");
    }

    @Test
    void bridgeRegistersLinkSelectionCallback() throws Exception {
        String src = readBridge();
        assertTrue(src.contains("FN_LINK_SELECTED"),
                "ThreeForceGraphJsBridge must register a link-selection BrowserFunction");
        assertTrue(src.contains("tfgv_notifyLinkSelected"),
                "BrowserFunction name must be tfgv_notifyLinkSelected");
    }

    @Test
    void bridgeRegistersSelectionClearedCallback() throws Exception {
        String src = readBridge();
        assertTrue(src.contains("FN_SEL_CLEARED"),
                "ThreeForceGraphJsBridge must register a selection-cleared BrowserFunction");
        assertTrue(src.contains("tfgv_notifySelectionCleared"),
                "BrowserFunction name must be tfgv_notifySelectionCleared");
    }

    @Test
    void bridgeRegistersContextMenuCallbacks() throws Exception {
        String src = readBridge();
        assertTrue(src.contains("FN_REQ_NODE_CTX"),
                "ThreeForceGraphJsBridge must register a node context-menu request");
        assertTrue(src.contains("FN_REQ_LINK_CTX"),
                "ThreeForceGraphJsBridge must register a link context-menu request");
        assertTrue(src.contains("FN_INVOKE_CTX"),
                "ThreeForceGraphJsBridge must register a context-menu action invoke");
    }

    @Test
    void bridgeRegistersViewerReadyCallback() throws Exception {
        String src = readBridge();
        assertTrue(src.contains("FN_VIEWER_READY"),
                "ThreeForceGraphJsBridge must register a viewer-ready BrowserFunction");
        assertTrue(src.contains("tfgv_viewerReady"),
                "BrowserFunction name must be tfgv_viewerReady");
    }

    /* ---- Apply data path ---- */

    @Test
    void bridgeApplyDataPushesThreeForceGraphData() throws Exception {
        String src = readBridge();
        int idx = src.indexOf("public void applyData(");
        assertTrue(idx > 0, "ThreeForceGraphJsBridge.applyData must exist");
        String body = src.substring(idx, src.indexOf("public void applyNodeConfig(", idx));
        assertTrue(body.contains("__tfg_nodes"),
                "applyData must push window.__tfg_nodes");
        assertTrue(body.contains("__tfg_links"),
                "applyData must push window.__tfg_links");
        assertTrue(body.contains("window.tfgv_setData()"),
                "applyData must trigger window.tfgv_setData()");
    }

    /* ---- Palette schema reprojection ---- */

    @Test
    void bridgeRefreshPaletteUsesJsSchema() throws Exception {
        String src = readBridge();
        int idx = src.indexOf("private void refreshPalette()");
        assertTrue(idx > 0, "ThreeForceGraphJsBridge.refreshPalette() must exist");
        String body = src.substring(idx, src.indexOf("private List<LegendEntry> derivePaletteEntries(", idx));
        // The Java LegendEntry record has fields colorHex, label, count —
        // the JS-side three-force-viewer.js expects {hex, label, count}.
        // Without reprojection every entry falls back to '#cccccc' in JS
        // and the palette renders grey regardless of the actual cluster
        // colors.
        assertTrue(body.contains("m.put(\"hex\""),
                "refreshPalette() must reproject LegendEntry.colorHex into JS-side field 'hex'");
        assertTrue(body.contains("m.put(\"label\""),
                "refreshPalette() must reproject LegendEntry.label into JS-side field 'label'");
        assertTrue(body.contains("m.put(\"count\""),
                "refreshPalette() must reproject LegendEntry.count into JS-side field 'count'");
        assertTrue(body.contains("e.colorHex()"),
                "refreshPalette() must read colorHex from the LegendEntry record");
    }

    /* ---- Viewer (Java) sanity ---- */

    @Test
    void viewerReturnsThreeForceGraphEngine() throws Exception {
        String src = readViewer();
        assertTrue(src.contains("return GraphEngine.THREE_FORCE_GRAPH"),
                "ThreeForceGraphViewer.getEngine() must return GraphEngine.THREE_FORCE_GRAPH");
    }

    @Test
    void viewerHasNoLegacySetLegend() throws Exception {
        String src = readViewer();
        assertFalse(src.contains("public void setLegend("),
                "ThreeForceGraphViewer.setLegend must NOT exist — Color Palette is auto-managed");
        assertFalse(src.contains("public void clearLegend("),
                "ThreeForceGraphViewer.clearLegend must NOT exist");
    }
}
