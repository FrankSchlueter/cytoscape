package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level regression guards for the Color Palette feature that
 * drives the NVL, Sigma and Cytoscape engines. The bridge code is
 * exercised only at runtime, so these tests inspect the Java source
 * the build packages into {@code target/classes} and assert the
 * structural contracts:
 *
 * <ul>
 *   <li>The palette's JS API ({@code vgv_applyColorPalette},
 *       {@code vgv_hideColorPalette} for NVL; {@code vg_applyColorPalette},
 *       {@code vg_hideColorPalette} for Sigma; {@code cgv_applyColorPalette},
 *       {@code cgv_hideColorPalette} for Cytoscape) is invoked from
 *       {@code setLeidenColors} / {@code applyNodeColors} / {@code clear}.</li>
 *   <li>An empty color map hides the palette (paletteVisible flips
 *       back to false and the matching hide-call is pushed).</li>
 *   <li>The legacy {@code applyLegend} / {@code clearLegend} entry points
 *       on the Sigma / Cytoscape bridges are removed.</li>
 * </ul>
 */
class ColorPaletteBridgeTest {

    /* ---------- NVL bridge ---------- */

    private static final String[] POSSIBLE_NVL_BRIDGE = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/NvlJsBridge.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/internal/NvlJsBridge.java"
    };

    private static String readNvlBridge() throws Exception {
        for (String p : POSSIBLE_NVL_BRIDGE) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("NvlJsBridge.java not found");
    }

    @Test
    void nvlBridgePushesPaletteFromSetLeidenColors() throws Exception {
        String src = readNvlBridge();
        // setLeidenColors must (1) update cached state, (2) push vgv_applyLeidenColors,
        // (3) refresh the palette so a non-empty map makes the panel appear.
        int idx = src.indexOf("public void setLeidenColors(");
        assertTrue(idx > 0, "NvlJsBridge.setLeidenColors must exist");
        String body = src.substring(idx, src.indexOf("public void applyNodeColors(", idx));
        assertTrue(body.contains("window.vgv_applyLeidenColors("),
                "NvlJsBridge.setLeidenColors must push vgv_applyLeidenColors");
        assertTrue(body.contains("refreshPalette("),
                "NvlJsBridge.setLeidenColors must invoke refreshPalette()");
    }

    @Test
    void nvlBridgePushesPaletteFromApplyNodeColors() throws Exception {
        String src = readNvlBridge();
        int idx = src.indexOf("public void applyNodeColors(");
        assertTrue(idx > 0, "NvlJsBridge.applyNodeColors must exist");
        String body = src.substring(idx, src.indexOf("public void clear(", idx));
        assertTrue(body.contains("window.vgv_applyNodeColors("),
                "NvlJsBridge.applyNodeColors must push vgv_applyNodeColors");
        assertTrue(body.contains("refreshPalette("),
                "NvlJsBridge.applyNodeColors must invoke refreshPalette()");
    }

    @Test
    void nvlBridgeClearHidesPalette() throws Exception {
        String src = readNvlBridge();
        int idx = src.indexOf("public void clear(");
        assertTrue(idx > 0, "NvlJsBridge.clear() must exist");
        String body = src.substring(idx, src.indexOf("public void fitToScreen(", idx));
        assertTrue(body.contains("window.vgv_clear()"),
                "NvlJsBridge.clear() must push vgv_clear()");
        assertTrue(body.contains("window.vgv_hideColorPalette()"),
                "NvlJsBridge.clear() must hide the Color Palette");
        assertTrue(body.contains("paletteVisible = false"),
                "NvlJsBridge.clear() must reset the cached paletteVisible flag");
    }

    @Test
    void nvlBridgeEmptyMapHidesPalette() throws Exception {
        String src = readNvlBridge();
        // refreshPalette() must hide the palette when both maps are empty,
        // and must NOT push vgv_applyColorPalette(entries, true) in that branch.
        int idx = src.indexOf("private void refreshPalette()");
        assertTrue(idx > 0, "NvlJsBridge.refreshPalette() must exist");
        String body = src.substring(idx, src.indexOf("private List<LegendEntry> derivePaletteEntries(", idx));
        assertTrue(body.contains("window.vgv_hideColorPalette()"),
                "NvlJsBridge.refreshPalette() must hide the palette when no colors are set");
        assertTrue(body.contains("paletteVisible = false"),
                "NvlJsBridge.refreshPalette() must reset paletteVisible when hiding");
    }

    /* ---------- Sigma bridge ---------- */

    private static final String[] POSSIBLE_SIGMA_BRIDGE = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/SigmaJsBridge.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/internal/SigmaJsBridge.java"
    };

    private static String readSigmaBridge() throws Exception {
        for (String p : POSSIBLE_SIGMA_BRIDGE) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("SigmaJsBridge.java not found");
    }

    @Test
    void sigmaBridgePushesPaletteFromSetLeidenColors() throws Exception {
        String src = readSigmaBridge();
        int idx = src.indexOf("public void setLeidenColors(");
        assertTrue(idx > 0, "SigmaJsBridge.setLeidenColors must exist");
        String body = src.substring(idx, src.indexOf("public void clear(", idx));
        assertTrue(body.contains("window.vg_applyLeidenColors("),
                "SigmaJsBridge.setLeidenColors must push vg_applyLeidenColors");
        assertTrue(body.contains("refreshPalette("),
                "SigmaJsBridge.setLeidenColors must invoke refreshPalette()");
    }

    @Test
    void sigmaBridgePushesPaletteFromApplyNodeColors() throws Exception {
        String src = readSigmaBridge();
        int idx = src.indexOf("public void applyNodeColors(");
        assertTrue(idx > 0, "SigmaJsBridge.applyNodeColors must exist");
        String body = src.substring(idx, src.indexOf("public void setLeidenColors(", idx));
        assertTrue(body.contains("window.vg_applyNodeColors("),
                "SigmaJsBridge.applyNodeColors must push vg_applyNodeColors");
        assertTrue(body.contains("refreshPalette("),
                "SigmaJsBridge.applyNodeColors must invoke refreshPalette()");
    }

    @Test
    void sigmaBridgeClearHidesPalette() throws Exception {
        String src = readSigmaBridge();
        int idx = src.indexOf("public void clear(");
        assertTrue(idx > 0, "SigmaJsBridge.clear() must exist");
        String body = src.substring(idx, src.indexOf("public void fitToScreen(", idx));
        assertTrue(body.contains("window.vg_clear()"),
                "SigmaJsBridge.clear() must push vg_clear()");
        assertTrue(body.contains("window.vg_hideColorPalette()"),
                "SigmaJsBridge.clear() must hide the Color Palette");
        assertTrue(body.contains("paletteVisible = false"),
                "SigmaJsBridge.clear() must reset paletteVisible");
    }

    @Test
    void sigmaBridgeRemovedLegacyApplyLegend() throws Exception {
        String src = readSigmaBridge();
        assertFalse(src.contains("public void applyLegend("),
                "SigmaJsBridge.applyLegend must be removed — Color Palette replaces the manual Legend API");
        assertFalse(src.contains("public void clearLegend("),
                "SigmaJsBridge.clearLegend must be removed — clear() now drives the palette lifecycle");
    }

    /* ---------- Sigma viewer ---------- */

    private static final String[] POSSIBLE_SIGMA_VIEWER = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/SigmaViewer.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/SigmaViewer.java"
    };

    private static String readSigmaViewer() throws Exception {
        for (String p : POSSIBLE_SIGMA_VIEWER) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("SigmaViewer.java not found");
    }

    @Test
    void sigmaViewerRemovedLegacySetLegend() throws Exception {
        String src = readSigmaViewer();
        assertFalse(src.contains("public void setLegend("),
                "SigmaViewer.setLegend must be removed — Color Palette replaces the manual Legend API");
        assertFalse(src.contains("public void clearLegend("),
                "SigmaViewer.clearLegend must be removed — clear() now drives the palette lifecycle");
    }

    /* ---------- Switching viewer routing ---------- */

    private static final String[] POSSIBLE_SWITCHING = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/SwitchingViewer.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/SwitchingViewer.java"
    };

    private static String readSwitching() throws Exception {
        for (String p : POSSIBLE_SWITCHING) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("SwitchingViewer.java not found");
    }

    @Test
    void switchingViewerExcludesSigmaFromSetLegend() throws Exception {
        String src = readSwitching();
        int idx = src.indexOf("public void setLegend(List<LegendEntry>");
        assertTrue(idx > 0, "SwitchingViewer.setLegend must exist (vis still needs it)");
        String body = src.substring(idx, src.indexOf("public void clearLegend()", idx));
        assertFalse(body.contains("sigmaViewer.setLegend("),
                "SwitchingViewer.setLegend must NOT route to Sigma — its Color Palette is auto-managed");
        assertFalse(body.contains("cytoscapeViewer.setLegend("),
                "SwitchingViewer.setLegend must NOT route to Cytoscape — its Color Palette is auto-managed");
        assertTrue(body.contains("visViewer.setLegend("),
                "SwitchingViewer.setLegend must still route to vis-network");
    }

    @Test
    void switchingViewerExcludesSigmaFromClearLegend() throws Exception {
        String src = readSwitching();
        int idx = src.indexOf("public void clearLegend()");
        assertTrue(idx > 0, "SwitchingViewer.clearLegend must exist (vis still needs it)");
        String body = src.substring(idx, src.indexOf("public List<LegendEntry> getLegend()", idx));
        assertFalse(body.contains("sigmaViewer.clearLegend("),
                "SwitchingViewer.clearLegend must NOT route to Sigma");
        assertFalse(body.contains("cytoscapeViewer.clearLegend("),
                "SwitchingViewer.clearLegend must NOT route to Cytoscape");
    }

    /* ---------- Cytoscape bridge ---------- */

    private static final String[] POSSIBLE_CYTOSCAPE_BRIDGE = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/CytoscapeJsBridge.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/internal/CytoscapeJsBridge.java"
    };

    private static String readCytoscapeBridge() throws Exception {
        for (String p : POSSIBLE_CYTOSCAPE_BRIDGE) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("CytoscapeJsBridge.java not found");
    }

    @Test
    void cytoscapeBridgePushesPaletteFromSetLeidenColors() throws Exception {
        String src = readCytoscapeBridge();
        int idx = src.indexOf("public void setLeidenColors(");
        assertTrue(idx > 0, "CytoscapeJsBridge.setLeidenColors must exist");
        String body = src.substring(idx, src.indexOf("public void applyCommunityView(", idx));
        assertTrue(body.contains("window.cgv_applyLeidenColors("),
                "CytoscapeJsBridge.setLeidenColors must push cgv_applyLeidenColors");
        assertTrue(body.contains("refreshPalette("),
                "CytoscapeJsBridge.setLeidenColors must invoke refreshPalette()");
    }

    @Test
    void cytoscapeBridgePushesPaletteFromApplyNodeColors() throws Exception {
        String src = readCytoscapeBridge();
        int idx = src.indexOf("public void applyNodeColors(");
        assertTrue(idx > 0, "CytoscapeJsBridge.applyNodeColors must exist");
        String body = src.substring(idx, src.indexOf("public void setLayout(", idx));
        assertTrue(body.contains("window.cgv_applyNodeColors("),
                "CytoscapeJsBridge.applyNodeColors must push cgv_applyNodeColors");
        assertTrue(body.contains("refreshPalette("),
                "CytoscapeJsBridge.applyNodeColors must invoke refreshPalette()");
    }

    @Test
    void cytoscapeBridgeClearHidesPalette() throws Exception {
        String src = readCytoscapeBridge();
        int idx = src.indexOf("public void clear(");
        assertTrue(idx > 0, "CytoscapeJsBridge.clear() must exist");
        String body = src.substring(idx, src.indexOf("public void fitToScreen(", idx));
        assertTrue(body.contains("window.cgv_clear()"),
                "CytoscapeJsBridge.clear() must push cgv_clear()");
        assertTrue(body.contains("window.cgv_hideColorPalette()"),
                "CytoscapeJsBridge.clear() must hide the Color Palette");
        assertTrue(body.contains("paletteVisible = false"),
                "CytoscapeJsBridge.clear() must reset the cached paletteVisible flag");
    }

    @Test
    void cytoscapeBridgeEmptyMapHidesPalette() throws Exception {
        String src = readCytoscapeBridge();
        int idx = src.indexOf("private void refreshPalette()");
        assertTrue(idx > 0, "CytoscapeJsBridge.refreshPalette() must exist");
        String body = src.substring(idx, src.indexOf("private List<LegendEntry> derivePaletteEntries(", idx));
        assertTrue(body.contains("window.cgv_hideColorPalette()"),
                "CytoscapeJsBridge.refreshPalette() must hide the palette when no colors are set");
        assertTrue(body.contains("paletteVisible = false"),
                "CytoscapeJsBridge.refreshPalette() must reset paletteVisible when hiding");
    }

    @Test
    void cytoscapeBridgeRemovedLegacyApplyLegend() throws Exception {
        String src = readCytoscapeBridge();
        assertFalse(src.contains("public void applyLegend("),
                "CytoscapeJsBridge.applyLegend must be removed — Color Palette replaces the manual Legend API");
        assertFalse(src.contains("public void clearLegend("),
                "CytoscapeJsBridge.clearLegend must be removed — clear() now drives the palette lifecycle");
    }

    /* ---------- Cytoscape viewer ---------- */

    private static final String[] POSSIBLE_CYTOSCAPE_VIEWER = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/CytoscapeViewer.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/CytoscapeViewer.java"
    };

    private static String readCytoscapeViewer() throws Exception {
        for (String p : POSSIBLE_CYTOSCAPE_VIEWER) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("CytoscapeViewer.java not found");
    }

    @Test
    void cytoscapeViewerRemovedLegacySetLegend() throws Exception {
        String src = readCytoscapeViewer();
        assertFalse(src.contains("public void setLegend("),
                "CytoscapeViewer.setLegend must be removed — Color Palette replaces the manual Legend API");
        assertFalse(src.contains("public void clearLegend("),
                "CytoscapeViewer.clearLegend must be removed — clear() now drives the palette lifecycle");
    }
}