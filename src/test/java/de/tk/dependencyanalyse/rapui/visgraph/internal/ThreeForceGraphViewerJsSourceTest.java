package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level checks against {@code three-force-viewer.js}. The bridge
 * is JS-only (no Java unit tests can exercise it directly), so we verify
 * the contract by inspecting the source the iframe loads.
 *
 * <p>Mirror of {@code NvlViewerJsSourceTest}: same static-source pattern,
 * same "fail loudly when the contract regresses" intent. Guards every
 * {@code tfgv_*} function that the Java bridge pushes and every callback
 * the JS side fires (selection, palette, tooltip).</p>
 */
class ThreeForceGraphViewerJsSourceTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/three/three-force-viewer.js",
            "target/classes/static/three/three-force-viewer.js",
    };

    private static final String[] POSSIBLE_HTML_PATHS = {
            "src/main/resources/static/three/three-force-viewer.html",
            "target/classes/static/three/three-force-viewer.html",
    };

    private static final String[] POSSIBLE_VENDOR_PATHS = {
            "src/main/resources/static/three/three.min.js",
            "src/main/resources/static/three/three-force-graph.min.js",
            "src/main/resources/static/three/d3-force-3d.min.js",
            "src/main/resources/static/three/kapsule.min.js",
            "target/classes/static/three/three.min.js",
            "target/classes/static/three/three-force-graph.min.js",
            "target/classes/static/three/d3-force-3d.min.js",
            "target/classes/static/three/kapsule.min.js",
    };

    private static String readViewerJs() throws IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("three-force-viewer.js not found in any known location");
    }

    private static String readViewerHtml() throws IOException {
        for (String p : POSSIBLE_HTML_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("three-force-viewer.html not found in any known location");
    }

    private static boolean vendorFileExists(String name) {
        for (String p : POSSIBLE_VENDOR_PATHS) {
            if (p.endsWith(name) && Files.exists(Paths.get(p))) return true;
        }
        return false;
    }

    /* ---- Bridge functions (Java → JS) ---- */

    @Test
    void tfgvSetDataIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_setData"),
                "three-force-viewer.js must register window.tfgv_setData");
    }

    @Test
    void tfgvApplyLeidenColorsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_applyLeidenColors"),
                "three-force-viewer.js must register window.tfgv_applyLeidenColors");
    }

    @Test
    void tfgvApplyNodeColorsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_applyNodeColors"),
                "three-force-viewer.js must register window.tfgv_applyNodeColors");
    }

    @Test
    void tfgvApplyColorPaletteIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_applyColorPalette"),
                "three-force-viewer.js must register window.tfgv_applyColorPalette");
        assertTrue(src.contains("window.tfgv_hideColorPalette"),
                "three-force-viewer.js must register window.tfgv_hideColorPalette");
    }

    @Test
    void tfgvClearIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_clear"),
                "three-force-viewer.js must register window.tfgv_clear");
    }

    @Test
    void tfgvFitToScreenIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_fitToScreen"),
                "three-force-viewer.js must register window.tfgv_fitToScreen");
    }

    @Test
    void tfgvSetLayoutIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_setLayout"),
                "three-force-viewer.js must register window.tfgv_setLayout");
        assertTrue(src.contains("pauseAnimation"),
                "tfgv_setLayout must call pauseAnimation/resumeAnimation (NONE → paused)");
    }

    @Test
    void tfgvSetPhysicsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.tfgv_setPhysics"),
                "three-force-viewer.js must register window.tfgv_setPhysics");
    }

    /* ---- JS → Java callbacks (selection, context menu) ---- */

    @Test
    void tfgvNotifyNodeSelectedIsFired() throws Exception {
        String src = readViewerJs();
        // The onNodeClick handler must call javaCall('tfgv_notifyNodeSelected', ...)
        assertTrue(src.contains("'tfgv_notifyNodeSelected'"),
                "onNodeClick must call javaCall('tfgv_notifyNodeSelected', id)");
    }

    @Test
    void tfgvNotifyLinkSelectedIsFired() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("'tfgv_notifyLinkSelected'"),
                "onLinkClick must call javaCall('tfgv_notifyLinkSelected', id)");
    }

    @Test
    void tfgvNotifySelectionClearedIsFired() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("'tfgv_notifySelectionCleared'"),
                "onBackgroundClick must call javaCall('tfgv_notifySelectionCleared')");
    }

    @Test
    void tfgvRequestNodeContextMenuIsFired() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("'tfgv_requestNodeContextMenu'"),
                "right-click on a hovered node must call javaCall('tfgv_requestNodeContextMenu', id, x, y)");
    }

    @Test
    void tfgvRequestLinkContextMenuIsFired() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("'tfgv_requestLinkContextMenu'"),
                "right-click on a hovered link must call javaCall('tfgv_requestLinkContextMenu', id, x, y)");
    }

    /* ---- Tooltip ---- */

    @Test
    void tooltipElementIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("tfg-tooltip"),
                "three-force-viewer.js must create a tooltip element with id 'tfg-tooltip'");
        assertTrue(src.contains("renderTooltipHTML"),
                "tooltip rendering must be split into a reusable HTML builder");
    }

    @Test
    void tooltipRendersNodeAndLinkProperties() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("'node'"),
                "tooltip renderer must support 'node' kind");
        assertTrue(src.contains("'link'"),
                "tooltip renderer must support 'link' kind");
        assertTrue(src.contains("properties"),
                "tooltip must read element.properties to render the property table");
    }

    /* ---- WebGL probe (hard requirement) ---- */

    @Test
    void webglProbeIsPresent() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("probeWebGL"),
                "three-force-viewer.js must define probeWebGL()");
        assertTrue(src.contains("getContext('webgl')"),
                "probeWebGL must test the WebGL canvas context");
        assertTrue(src.contains("tfg-error"),
                "WebGL-missing branch must show the inline error element with id 'tfg-error'");
    }

    /* ---- Color Palette panel markup ---- */

    @Test
    void colorPaletteMarkupIsPresentInViewerHtml() throws Exception {
        String html = readViewerHtml();
        // The viewer HTML links the CSS that styles the panel.
        assertTrue(html.contains("three-force-viewer.css"),
                "three-force-viewer.html must link three-force-viewer.css");
    }

    @Test
    void colorPaletteCssSelectorsArePresent() throws Exception {
        String css = new String(Files.readAllBytes(Paths.get("src/main/resources/static/three/three-force-viewer.css")),
                StandardCharsets.UTF_8);
        assertTrue(css.contains("#tfg-color-palette"),
                "CSS must define #tfg-color-palette panel");
        assertTrue(css.contains("#tfg-tooltip"),
                "CSS must define #tfg-tooltip tooltip");
        assertTrue(css.contains("tfg-cp-row"),
                "CSS must define .tfg-cp-row legend row");
    }

    /* ---- Vendor bundle presence ---- */

    @Test
    void vendorBundlesAreBundled() throws Exception {
        assertTrue(vendorFileExists("three.min.js"),
                "three.min.js must be bundled under src/main/resources/static/three/");
        assertTrue(vendorFileExists("three-force-graph.min.js"),
                "three-force-graph.min.js must be bundled under src/main/resources/static/three/");
        assertTrue(vendorFileExists("d3-force-3d.min.js"),
                "d3-force-3d.min.js must be bundled under src/main/resources/static/three/");
        assertTrue(vendorFileExists("kapsule.min.js"),
                "kapsule.min.js must be bundled under src/main/resources/static/three/");
    }

    @Test
    void viewerReadyCallbackIsFired() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("'tfgv_viewerReady'"),
                "the viewer must fire javaCall('tfgv_viewerReady') once the iframe boots");
    }

    /* ---- Edge weight styling ---- */

    @Test
    void jsUsesEdgeWeightStyling() throws Exception {
        String src = readViewerJs();
        // The JS must read the per-link weight attribute and drive
        // linkWidth, linkColor (rgba with weight-scaled alpha), and the
        // d3-force-3d link distance so heavy edges cluster the connected
        // nodes and become visually dominant. Without this, all edges are
        // equally thick and the layout ignores the weight semantics that
        // the Cytoscape/Sigma engines already honor.
        assertTrue(src.contains("getEffectiveWeight"),
                "three-force-viewer.js must define getEffectiveWeight(link) helper");
        assertTrue(src.contains("applyEdgeWeightStyling"),
                "three-force-viewer.js must define applyEdgeWeightStyling()");
        assertTrue(src.contains("recomputeWeightStats"),
                "applyEdgeWeightStyling must recompute min/max weight across pendingLinks");
        assertTrue(src.contains("d3Force('link')"),
                "applyEdgeWeightStyling must call graph.d3Force('link').distance(...) to cluster nodes by weight");
        assertTrue(src.contains("d3Force('charge')"),
                "applyEdgeWeightStyling must call graph.d3Force('charge').strength(...) to tune repulsion");
        assertTrue(src.contains("d3ReheatSimulation"),
                "applyEdgeWeightStyling must reheat the simulation so the new forces take effect");
        assertTrue(src.contains("linkWidthAccessor"),
                "three-force-viewer.js must define linkWidthAccessor(link)");
        assertTrue(src.contains("linkColorAccessor"),
                "three-force-viewer.js must define linkColorAccessor(link)");
        assertTrue(src.contains("rgba("),
                "linkColorAccessor must produce rgba() strings (weight-scaled alpha)");
        assertTrue(src.contains("normalizedT"),
                "normalizedT must clamp the sqrt-scaled weight into [0,1]");
    }
}
