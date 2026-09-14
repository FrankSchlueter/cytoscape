package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level checks against {@code nvl-graph-viewer.js}. The bridge
 * is JS-only (no Java unit tests can exercise it directly), so we
 * verify the contract by inspecting the source the iframe loads.
 *
 * <p>Guarded here: NVL must respond to the unified color-update
 * protocol — {@code vgv_applyLeidenColors} (so the "Apply Leiden
 * Clustering" button works under NVL) and
 * {@code vgv_applyNodeColors} (so the resolved per-node color map
 * from {@code NodeColorResolver} reaches the NVL canvas).</p>
 *
 * <p>Mirror of {@code CytoscapeViewerJsSourceTest} /
 * {@code VisGraphViewerJsSourceTest}: same static-source pattern, same
 * "fail loudly when the contract regresses" intent.</p>
 */
class NvlViewerJsSourceTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/nvl/nvl-graph-viewer.js",
            "target/classes/static/nvl/nvl-graph-viewer.js",
    };

    private static String readViewerJs() throws IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("nvl-graph-viewer.js not found in any known location");
    }

    /**
     * Regression test for the "Apply Leiden Clustering is a no-op on
     * NVL" bug. The handler must call
     * {@code nvl.updateElementsInGraph} with one {@code {id, color}}
     * entry per node.
     */
    @Test
    void vgvApplyLeidenColorsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyLeidenColors"),
                "nvl-graph-viewer.js must register window.vgv_applyLeidenColors");
        assertTrue(src.contains("nvl.updateElementsInGraph"),
                "vgv_applyLeidenColors must call nvl.updateElementsInGraph to push the Leiden colors");
    }

    /**
     * The unified per-node color update handler must exist and route
     * through {@code nvl.updateElementsInGraph}.
     */
    @Test
    void vgvApplyNodeColorsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyNodeColors"),
                "nvl-graph-viewer.js must register window.vgv_applyNodeColors");
        assertTrue(src.contains("nvl.updateElementsInGraph"),
                "vgv_applyNodeColors must call nvl.updateElementsInGraph");
    }

    /**
     * The legacy {@code vgv_applyNodeConfig} (kept for backwards
     * compatibility with the bridge) must NOT be a no-op without
     * comment. Either it routes to vgv_applyNodeColors or it
     * explicitly logs that NVL uses a different path — pure no-op
     * means the bridge's applyNodeConfig call silently drops the
     * NodeConfig (the original bug).
     */
    @Test
    void vgvApplyNodeConfigIsNotASilentNoOp() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyNodeConfig"),
                "nvl-graph-viewer.js must register window.vgv_applyNodeConfig (called by the bridge)");
        // The legacy handler must NOT be a bare `/* no-op */` — either
        // it dispatches to vgv_applyNodeColors or it logs the
        // rationale so a regression to a silent drop is visible.
        String bareNoOp = "window.vgv_applyNodeConfig = function () { /* no-op */ };";
        assertTrue(!src.contains(bareNoOp),
                "vgv_applyNodeConfig must not be a bare no-op (the original NVL bug)");
    }

    /**
     * Regression guard for the Color Palette feature. NVL must
     * expose {@code vgv_applyColorPalette(entries, enabled)} so the
     * Java bridge can auto-push the palette panel after every
     * {@code applyNodeColors} / {@code setLeidenColors} call, plus
     * {@code vgv_hideColorPalette()} so {@code clear()} can reset it.
     */
    @Test
    void vgvApplyColorPaletteIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyColorPalette"),
                "nvl-graph-viewer.js must register window.vgv_applyColorPalette");
        assertTrue(src.contains("window.vgv_hideColorPalette"),
                "nvl-graph-viewer.js must register window.vgv_hideColorPalette");
    }

    /**
     * The palette panel markup (#vgv-color-palette) must exist in the
     * NVL viewer HTML so the JS renderer has a place to mount rows.
     */
    @Test
    void colorPaletteMarkupIsPresentInNvlViewerHtml() throws Exception {
        String[] paths = {
                "src/main/resources/static/nvl/nvl-viewer.html",
                "target/classes/static/nvl/nvl-viewer.html"
        };
        String html = null;
        for (String p : paths) {
            java.nio.file.Path path = java.nio.file.Paths.get(p);
            if (java.nio.file.Files.exists(path)) {
                html = new String(java.nio.file.Files.readAllBytes(path),
                        java.nio.charset.StandardCharsets.UTF_8);
                break;
            }
        }
        assertNotNull(html, "nvl-viewer.html must be readable");
        assertTrue(html.contains("vgv-color-palette"),
                "nvl-viewer.html must contain the #vgv-color-palette container");
        assertTrue(html.contains("Color Palette"),
                "nvl-viewer.html must label the panel 'Color Palette'");
    }

    /**
     * Pan + Zoom regression guard. The official NVL Interaction
     * Handlers {@code Pan} (drag on empty canvas) and {@code Zoom}
     * (mouse wheel) must be constructed during the boot sequence so
     * users can navigate the canvas. Without these lines the canvas
     * is read-only outside of node-selection and the Fit button.
     */
    @Test
    void panAndZoomHandlersAreConstructed() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("new window.Neo4jNVLInteractions.Pan("),
                "nvl-graph-viewer.js must construct a Pan interaction handler "
                        + "(drag on empty canvas) so the canvas is pannable");
        assertTrue(src.contains("new window.Neo4jNVLInteractions.Zoom("),
                "nvl-graph-viewer.js must construct a Zoom interaction handler "
                        + "(mouse wheel) so the canvas is zoomable");
    }

    /**
     * Negative guard for the Pan + Zoom regression. The previous
     * implementation intentionally left the handlers as {@code null}
     * with a long comment about RAP scroll-container quirks. The
     * NVL Interaction Handlers call {@code preventDefault()} on the
     * wheel event themselves so the legacy no-op rationale no
     * longer applies — a future refactor that re-introduces the
     * silent null assignment regresses the user-facing navigation.
     */
    @Test
    void panZoomNullAssignmentsAreRemoved() throws Exception {
        String src = readViewerJs();
        // The initial `var panHandler = null;` declaration at module
        // scope is fine — only the post-construction "explicit no-op
        // assignment" pattern is the regression we want to catch.
        assertFalse(src.contains("panHandler = null;"),
                "nvl-graph-viewer.js must not re-assign panHandler to null "
                        + "after boot — the Pan handler must be constructed "
                        + "and stay constructed");
        assertFalse(src.contains("zoomHandler = null;"),
                "nvl-graph-viewer.js must not re-assign zoomHandler to null "
                        + "after boot — the Zoom handler must be constructed "
                        + "and stay constructed");
    }
}
