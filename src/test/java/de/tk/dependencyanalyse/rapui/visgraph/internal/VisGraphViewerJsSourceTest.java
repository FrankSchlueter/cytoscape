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
 * Static source-level checks against {@code vis-graph-viewer.js}. The
 * bridge is JS-only (no Java unit tests can exercise it directly), so
 * we verify the contract by inspecting the source the iframe loads.
 *
 * <p>The contract guarded here: vis-network must NEVER rebuild SVG
 * badges client-side. The Java side ({@code GraphNode.setSvgShape} →
 * {@code renderSvgIcon4}) produces a fully-rendered SVG (icon + circle
 * + typeChar) and stores it as the {@code image} attribute on the
 * serialized node. {@code applySvgImage} must simply clean up the
 * descriptor and let vis-network consume {@code n.image} verbatim —
 * re-rendering on the client would discard the icon.</p>
 */
class VisGraphViewerJsSourceTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/vis-graph/vis-graph-viewer.js",
            "target/classes/static/vis-graph/vis-graph-viewer.js",
    };

    private static String readViewerJs() throws IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("vis-graph-viewer.js not found in any known location");
    }

    @Test
    void applySvgImagePreservesJavaImageVerbatim() throws Exception {
        // The fix: applySvgImage must NOT call any client-side SVG
        // builder. It simply clears the svgImage descriptor and lets
        // vis-network consume n.image (the Java-side base64 data URI)
        // verbatim.
        String src = readViewerJs();
        assertTrue(src.contains("function applySvgImage"),
                "vis-graph-viewer.js must define applySvgImage()");
        // Find the function body.
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "function applySvgImage\\(n\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "applySvgImage() body not found");
        String fn = m.group(1);
        assertTrue(fn.contains("delete n.svgImage"),
                "applySvgImage must drop the svgImage descriptor after consuming it");
        assertTrue(fn.contains("n.shape = 'image'"),
                "applySvgImage must set n.shape='image' so vis-network consumes the kept image");
        assertTrue(fn.contains("return n"),
                "applySvgImage must return n for chaining");
        // Must NOT contain any encoding-fix helper — Java now ships a
        // base64 data URI that's already valid.
        assertFalse(fn.contains("vgv_normalizeSvgDataUri"),
                "applySvgImage must not call vgv_normalizeSvgDataUri; the Java "
                        + "side ships a base64 URI that needs no client-side fix");
        assertFalse(fn.contains("encodeURIComponent"),
                "applySvgImage must not URL-encode anything itself; the Java "
                        + "side already produces the final data URI");
    }

    @Test
    void applySvgImageDoesNotBuildSvgClientSide() throws Exception {
        // Belt-and-suspenders: confirm the client-side SVG builders
        // (vgv_createSvgNode, vgv_createSvgIcon, vgv_normalizeSvgDataUri)
        // are all gone. Each was a JS-side rendering/encoding helper
        // that has been replaced by Java's renderSvgIcon4 + base64 output.
        String src = readViewerJs();
        assertFalse(src.contains("vgv_createSvgNode"),
                "vis-graph-viewer.js must NOT define vgv_createSvgNode — Java "
                        + "owns SVG rendering and ships ready-to-use data URIs");
        assertFalse(src.contains("vgv_createSvgIcon"),
                "vis-graph-viewer.js must NOT define vgv_createSvgIcon — the "
                        + "Java renderSvgIcon4 helper already produces the final SVG");
        assertFalse(src.contains("vgv_normalizeSvgDataUri"),
                "vis-graph-viewer.js must NOT define vgv_normalizeSvgDataUri — "
                        + "base64 data URIs need no client-side encoding fix");
        assertFalse(src.contains("applySvgIcon"),
                "vis-graph-viewer.js must NOT call applySvgIcon — it was Dead Code "
                        + "(never invoked because Java sets n.image directly, not n.svgIcon)");
        // applySvgImage must not build SVG markup itself.
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "function applySvgImage\\(n\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "applySvgImage() body not found");
        String fn = m.group(1);
        assertFalse(fn.contains("'<svg"),
                "applySvgImage must not build SVG markup itself");
    }

    @Test
    void vgvApplyNodeImagesHandlerExistsAndDelegatesToDataSet() throws Exception {
        // Java sends batched recolor updates via window.vgv_applyNodeImages.
        // The handler must exist and forward to vis-network's DataSet.update
        // so vis-network re-renders with the new image / color. There is no
        // need for client-side image preloading: DataSet.update emits an
        // 'update' event that vis-network handles via its internal Mb cache.
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyNodeImages"),
                "vis-graph-viewer.js must register window.vgv_applyNodeImages");
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "vgv_applyNodeImages\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "vgv_applyNodeImages must define a function body");
        String fn = m.group(1);
        assertTrue(fn.contains("nodes.update"),
                "vgv_applyNodeImages must delegate to vis-network's DataSet.update");
        // networkReady guard: if vis-network hasn't booted yet, the handler
        // must be a no-op rather than throwing.
        assertTrue(fn.contains("networkReady") || fn.contains("!network"),
                "vgv_applyNodeImages must guard on networkReady to handle pre-boot calls");
    }

    @Test
    void __vgvNodeConfigDeadCodeRemoved() throws Exception {
        // The pre-recolor code stored the NodeConfig in a private
        // __vgv_nodeConfig variable but never read it (vis-network has no
        // stylesheet engine, so the config could not be applied to node
        // visuals). After unifying the recolor pipeline with Cytoscape, the
        // storage is gone — applyRecolorsBoth pushes per-node updates via
        // vgv_applyNodeImages instead.
        String src = readViewerJs();
        assertFalse(src.contains("__vgv_nodeConfig"),
                "vis-graph-viewer.js must NOT define __vgv_nodeConfig — it was "
                        + "Dead Code that stored the NodeConfig without ever reading it");
        assertFalse(src.contains("vgv_applyNodeConfig"),
                "vis-graph-viewer.js must NOT define vgv_applyNodeConfig — the "
                        + "Java bridge no longer ships NodeConfig updates to vis-network");
    }

    @Test
    void applySvgImageHandlesNodeWithImageButNoSvgImageDescriptor() throws Exception {
        // When a node carries a ready-to-use image but no svgImage
        // descriptor (e.g. plain setIcon(url) on the Java side), the
        // viewer must still set shape=image so vis-network renders it.
        String src = readViewerJs();
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "function applySvgImage\\(n\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "applySvgImage() body not found");
        String fn = m.group(1);
        // The early return `if (!n.svgImage) return n` keeps existing
        // image data flowing through to vis-network without rebuilding.
        assertTrue(fn.contains("if (!n.svgImage) return n"),
                "applySvgImage must early-return when there is no svgImage descriptor "
                        + "(the Java image is already in n.image)");
    }

    @Test
    void vgvApplyNodeImagesForcesNetworkRedraw() throws Exception {
        // The recolor pipeline ships per-node image / color updates to
        // vis-network. vis-network's DataSet.update emits an 'update'
        // event that schedules a render on the next animation frame, and
        // only repaints properties whose values actually changed. After
        // GraphConfigurationDialog-driven recoloring the user must see
        // the new colors immediately, especially when the recolor is the
        // last user action in a session. The handler therefore calls
        // network.redraw() right after nodes.update() to force a
        // synchronous repaint that takes effect before the next idle tick.
        String src = readViewerJs();
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "vgv_applyNodeImages\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "vgv_applyNodeImages must define a function body");
        String fn = m.group(1);
        assertTrue(fn.contains("nodes.update"),
                "vgv_applyNodeImages must delegate to vis-network's DataSet.update");
        assertTrue(fn.contains("network.redraw"),
                "vgv_applyNodeImages must force a synchronous network.redraw() so the "
                        + "new image / color is visible immediately, even when no property "
                        + "value actually changed (vis-network skips re-renders in that case)");
    }
}
