package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @Test
    void vgvApplyLeidenColorsHandlerRecolorsPlainNodes() throws Exception {
        // The Leiden-cluster pipeline (GraphConfigurationDialog → vis-graph
        // bridge → iframe) needs a JS handler that converts the per-node
        // color map into vis-network-compatible updates. Without it, the
        // "Apply Leiden Clustering" button is a no-op for vis-network:
        // the colors are pushed from Java but the iframe ignores them.
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyLeidenColors"),
                "vis-graph-viewer.js must register window.vgv_applyLeidenColors");
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "vgv_applyLeidenColors\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "vgv_applyLeidenColors must define a function body");
        String fn = m.group(1);
        // Must iterate the color map and push one nodes.update entry per
        // node with vis-network's ColorSpec (background + border +
        // highlight/hover overrides).
        assertTrue(fn.contains("nodes.update"),
                "vgv_applyLeidenColors must call nodes.update() so vis-network "
                        + "re-renders the node with the new color");
        assertTrue(fn.contains("background"),
                "vgv_applyLeidenColors must write the color into the "
                        + "color.background slot — vis-network's ellipse/box/circle shapes "
                        + "read `color.background` for the shape fill, NOT `color.color`");
        assertTrue(fn.contains("network.redraw"),
                "vgv_applyLeidenColors must force a synchronous network.redraw() so the "
                        + "new cluster colors are visible immediately");
    }

    /* ------------------------------------------------------------------ */
    /*  Stability guards (Fix 5 + Fix 6)                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void vgvDisposalHandlerCleansUpOrphanDom() throws Exception {
        // Without an explicit disposal hook, the legend + context-menu
        // panels in the vis-network iframe would survive the engine
        // switch to Cytoscape. window.vgv_dispose() lets the bridge
        // clean the DOM before tearing down the BrowserFunction shim.
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_dispose"),
                "vis-graph-viewer.js must register window.vgv_dispose");
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "window\\.vgv_dispose\\s*=\\s*function\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "window.vgv_dispose must define a function body");
        String fn = m.group(1);
        assertTrue(fn.contains("clearLegendHighlight"),
                "vgv_dispose must call clearLegendHighlight so a fresh vis-network "
                        + "instance does not inherit the previous cluster dimming");
        assertTrue(fn.contains("vgv-legend"),
                "vgv_dispose must remove the legend panel DOM (#vgv-legend)");
    }

    @Test
    void vgvResizeHandlerResizesNetworkToContainer() throws Exception {
        // The SWT Resize listener on GraphViewer delegates to
        // window.vgv_resize, which must call network.setSize() +
        // network.redraw() so the canvas follows the composite's actual
        // size (vis-network does not auto-detect parent resize from
        // inside the iframe).
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_resize"),
                "vis-graph-viewer.js must register window.vgv_resize");
        java.util.regex.Pattern body = java.util.regex.Pattern.compile(
                "window\\.vgv_resize\\s*=\\s*function\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = body.matcher(src);
        assertTrue(m.find(), "window.vgv_resize must define a function body");
        String fn = m.group(1);
        assertTrue(fn.contains("network.setSize"),
                "vgv_resize must call network.setSize so the canvas matches the container");
        assertTrue(fn.contains("network.redraw"),
                "vgv_resize must call network.redraw so the new size takes effect");
    }

    @Test
    void bootIsIdempotentAndGuarded() throws Exception {
        // boot() can be triggered from two competing paths during the
        // FillLayout flush — the ResizeObserver callback AND the
        // setTimeout fallback. Without a guard, vis-network gets
        // initialised twice and the second new vis.Network() throws.
        // The earlier booting-flag variant blocked the ResizeObserver
        // re-entry during a doBoot in flight and left vis stuck on a
        // 0×0 canvas after engine switches — so networkReady is the
        // single source of truth now.
        String src = readViewerJs();
        java.util.regex.Pattern bootBody = java.util.regex.Pattern.compile(
                "function boot\\(container\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        java.util.regex.Matcher m = bootBody.matcher(src);
        assertTrue(m.find(), "boot() must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("networkReady"),
                "boot() must early-return when networkReady is already true "
                        + "(idempotency guard against double-init from ResizeObserver + setTimeout)");
        assertFalse(fn.contains("booting"),
                "boot() must NOT reference the obsolete booting flag — that guard blocked "
                        + "the ResizeObserver fallback during doBoot and left vis stuck on 0×0");
        assertFalse(src.contains("var booting = false"),
                "vis-graph-viewer.js must not declare a booting flag at the top of the IIFE");
    }

    @Test
    void bootHardTimeoutSurfacesContainerSizeError() throws Exception {
        // If the parent composite never reaches a non-zero size (FillLayout
        // race on first engine switch), the boot script must surface a
        // clear error in the #vgv-debug banner instead of leaving the
        // user staring at an empty canvas.
        String src = readViewerJs();
        assertTrue(src.contains("Container size 0×0"),
                "vis-graph-viewer.js must surface a hard-timeout error in #vgv-debug when the container never resizes");
    }

    /* ------------------------------------------------------------------ */
    /*  Render-stability guards (Fix 0-size + boot recovery)             */
    /* ------------------------------------------------------------------ */

    @Test
    void bootWindowResizeListenerGuardsAgainstZeroContainerSize() throws Exception {
        // The window resize listener registered in boot() must not call
        // network.setSize(0, 0) when the iframe container briefly
        // reports 0×0 during the FillLayout flush of an engine switch
        // — that paints an invisible canvas. Skip the call when the
        // container reads 0 in either dimension; the next Resize event
        // after the flush carries the real size.
        String src = readViewerJs();
        // Match the listener body at its actual indentation (8 spaces).
        Pattern body = Pattern.compile(
                "window\\.addEventListener\\(\\s*'resize'\\s*,\\s*function\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{8}\\}\\s*\\)");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "boot() must register a window resize listener");
        String fn = m.group(1);
        assertTrue(fn.contains("clientWidth"),
                "resize listener must read container.clientWidth to detect 0×0 flushes");
        assertTrue(fn.matches("(?s).*[\\s\\S]*if\\s*\\(w\\s*<=\\s*0\\s*\\|\\|\\s*h\\s*<=\\s*0\\)\\s*return[\\s\\S]*"),
                "resize listener must early-return when w<=0 or h<=0");
    }

    @Test
    void bootClearsBootingFlagOnEveryExitPath() throws Exception {
        // The booting flag has been completely removed. This test is
        // kept as a regression guard: if anyone re-introduces a booting
        // flag, doBoot() must reset it on every early-return path AND
        // on the success path so a single boot failure doesn't brick the
        // viewer.
        String src = readViewerJs();
        assertFalse(src.matches("(?s).*[\\s\\S]*\\bvar\\s+booting\\b[\\s\\S]*"),
                "bootClearsBootingFlagOnEveryExitPath is now an anti-regression guard: the "
                        + "booting flag was removed because it blocked the ResizeObserver fallback "
                        + "during doBoot and left vis stuck on 0×0. If anyone re-adds it, the "
                        + "resets-on-every-path logic must follow.");
    }

    /* ------------------------------------------------------------------ */
    /*  Stability guards (Fix C: vis 0-size + re-entrancy)                */
    /* ------------------------------------------------------------------ */

    @Test
    void bootCallsFitCanvasAfterNetworkConstruction() throws Exception {
        // After `new vis.Network(container, data, options)` the canvas
        // may briefly be 0×0 during the FillLayout flush. The boot must
        // proactively call network.setSize(w, h) and, if the container
        // is still 0×0, register a ResizeObserver to retry. Without this
        // the "switched to Vis, nothing appears" symptom is permanent
        // until something else triggers a window Resize event.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function doBoot\\(container\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "doBoot must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("fitCanvas()"),
                "doBoot must call fitCanvas() after new vis.Network to set the canvas size");
        assertTrue(fn.contains("bootContainerSizeWaiter"),
                "doBoot must call bootContainerSizeWaiter() to register a ResizeObserver fallback");
    }

    @Test
    void fitCanvasGuardsAgainstZeroSize() throws Exception {
        // fitCanvas() must early-return when the container is 0×0 —
        // calling network.setSize(0, 0) would paint an invisible canvas.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function fitCanvas\\(\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "fitCanvas must be defined");
        String fn = m.group(1);
        assertTrue(fn.matches("(?s).*[\\s\\S]*if\\s*\\(w\\s*<=\\s*0\\s*\\|\\|\\s*h\\s*<=\\s*0\\)\\s*return\\s*false[\\s\\S]*"),
                "fitCanvas must early-return false when the container reports w<=0 or h<=0");
    }

    @Test
    void vgvSetDataIsReentrantSafe() throws Exception {
        // Same guard pattern as Cytoscape — multiple queued exec() calls
        // can fire during the same ResizeObserver flush. Without a
        // busy flag + pending-payload slot the second call would
        // clobber the first's mid-flight state.
        String src = readViewerJs();
        assertTrue(src.contains("vgvSetDataBusy"),
                "vgv_setData must guard against re-entrancy with a busy flag");
        assertTrue(src.contains("vgvSetDataPending"),
                "vgv_setData must capture the latest payload in a pending slot so a re-entrant call doesn't drop it");
    }

    /* ------------------------------------------------------------------ */
    /*  Syntax + post-boot fitCanvas guards                                */
    /* ------------------------------------------------------------------ */

    @Test
    void visGraphViewerSourceCompiles() throws Exception {
        // The most reliable regression guard for the "no graph in vis"
        // symptom: the IIFE at the top of the file must be syntactically
        // valid JavaScript. Earlier the file ended with three stray
        // `window.__vgv_* = null;` statements plus a trailing `};` that
        // Node parsed as "Unexpected token ';'", which prevented the
        // entire IIFE from running, which is why vis rendered nothing
        // after an engine switch.
        //
        // Try to parse the IIFE through a JavaScript engine if one is
        // available (nashorn is gone in JDK 15+ but Graal.js or other
        // engines may be on the classpath). Fall back to a brace-depth
        // heuristic when no engine is available — a stray top-level
        // `};` line is the one specific pattern that broke the file.
        String src = readViewerJs();
        javax.script.ScriptEngine eng = null;
        for (String name : new String[] {"nashorn", "graal.js", "js"}) {
            eng = new javax.script.ScriptEngineManager().getEngineByName(name);
            if (eng != null) break;
        }
        if (eng != null) {
            try {
                // Wrap in a Function body so the engine parses it as JS
                // rather than executing it as a program — that way the
                // stray ';' doesn't actually run anything dangerous.
                String stripped = src
                        .replaceFirst("^\\(function\\s*\\(\\)\\s*\\{\\n\\s*'use strict';\\n", "")
                        .replaceAll("\\}\\)\\(\\);\\s*$", "");
                String probe = "(function(){var window={};var vis={};var document={};"
                        + "function fitCanvas(){};function applySvgImage(){};function wrapTitleAsElement(){};"
                        + stripped + "})";
                eng.eval(probe);
            } catch (javax.script.ScriptException se) {
                fail("vis-graph-viewer.js has a JS syntax error: " + se.getMessage()
                        + " — earlier the file ended with stray `window.__vgv_* = null;` "
                        + "statements plus a trailing `};` that broke parsing entirely. "
                        + "With the IIFE failing to execute, vgv_setData was unreachable and "
                        + "vis-network rendered nothing after an engine switch.");
            }
            return;
        }
        // Fallback heuristic — walk the file's top-level structure and
        // flag any line that is exactly `};` once the IIFE has been
        // closed (we use brace depth to track that).
        int braceDepth = 0;
        for (String line : src.split("\\R")) {
            String trimmed = line.trim();
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }
            // A top-level (braceDepth == 0) statement that is exactly
            // `};` after a function has closed is the broken pattern.
            // (Function-closing `};` lines at non-zero brace depths are
            // perfectly legal and show up everywhere in the file.)
            if (braceDepth == 0 && trimmed.equals("};")) {
                fail("vis-graph-viewer.js contains a stray top-level '};' line that "
                        + "produces a JS SyntaxError — earlier the file ended with stray "
                        + "`window.__vgv_* = null;` statements plus a trailing `};` that broke "
                        + "parsing entirely. With the IIFE failing to execute, vgv_setData was "
                        + "unreachable and vis-network rendered nothing after an engine switch.");
            }
        }
    }

    @Test
    void applyPendingDataInlineCallsFitCanvas() throws Exception {
        // The post-boot path (vgv_setData after the first boot) routes
        // through applyPendingDataInline, NOT the legacy applyPendingData
        // queue. The legacy queue called fitCanvas() at the end; the new
        // function must do the same — otherwise vis-network stays at its
        // boot-time canvas size (possibly 0×0 if the FillLayout flush hit
        // during construction) and renders nothing.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function applyPendingDataInline\\(payload\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "applyPendingDataInline must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("fitCanvas()"),
                "applyPendingDataInline must call fitCanvas() after nodes.add/edges.add so the "
                        + "canvas size is re-asserted after the data has been applied — "
                        + "without this the vis-network canvas stays at its boot-time size "
                        + "(possibly 0×0 if the FillLayout flush hit during construction)");
    }

    @Test
    void vgvSetDataClearsWindowGlobalsAfterApplyCycle() throws Exception {
        // After a successful apply cycle, the Globalen
        // window.__vgv_nodes / __vgv_edges / __vgv_options must be cleared
        // so a subsequent applyData() that fires its vgv_setData() between
        // the while-loop and the next capture doesn't accidentally
        // double-render with the previous payload's leftovers.
        String src = readViewerJs();
        // Locate vgv_setData = function() { ... } and grab the body up
        // to its matching close. The regex below is forgiving about the
        // exact whitespace inside the close — it tolerates both `    };`
        // and `};` and any trailing newline.
        int start = src.indexOf("window.vgv_setData = function");
        assertTrue(start >= 0, "vgv_setData must be defined");
        int open = src.indexOf("{", start);
        assertTrue(open > 0, "vgv_setData body opener must exist");
        // Crude-but-effective brace matcher that handles nested {} and
        // skips over string literals / regex literals by counting raw
        // braces. For our IIFE-style source this is enough.
        int depth = 0;
        int close = -1;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { close = i; break; }
            }
        }
        assertTrue(close > open, "vgv_setData body closer must exist");
        String body = src.substring(open, close);
        assertTrue(body.contains("window.__vgv_nodes = null"),
                "vgv_setData's while-loop body must clear window.__vgv_nodes after applying "
                        + "so a subsequent applyData() doesn't double-render");
        assertTrue(body.contains("window.__vgv_edges = null"),
                "vgv_setData's while-loop body must clear window.__vgv_edges after applying");
        assertTrue(body.contains("window.__vgv_options = null"),
                "vgv_setData's while-loop body must clear window.__vgv_options after applying");
    }

    /* ------------------------------------------------------------------ */
    /*  Render-stability guards (booting-Flag-Free + Watchdog)             */
    /* ------------------------------------------------------------------ */

    @Test
    void bootingFlagIsNotDeclared() throws Exception {
        // The earlier booting-Flag-Variante blockte die ResizeObserver-
        // Re-entry während eines laufenden doBoot()-Aufrufs, sodass vis
        // nach einem Engine-Switch dauerhaft auf 0×0 hängen blieb. Das
        // Flag wurde komplett entfernt; nur networkReady bleibt als
        // Idempotenz-Guard übrig.
        String src = readViewerJs();
        assertFalse(src.matches("(?s).*[\\s\\S]*\\bvar\\s+booting\\b[\\s\\S]*"),
                "vis-graph-viewer.js must not declare a top-level 'var booting' — that "
                        + "guard was blocking the ResizeObserver fallback during a FillLayout "
                        + "flush and left vis stuck on a 0×0 canvas after engine switches. "
                        + "Use networkReady as the only idempotency guard.");
    }

    @Test
    void visOptionsDoNotEnableStabilization() throws Exception {
        // vis-network's default stabilization.fit: true blocks the first
        // paint until Force-Atlas2 reports "stabilized", and on a 1010-
        // edge graph the default 1000 iterations never converge. The
        // user sees an empty canvas even though the data is loaded.
        // Without stabilization, vis-network paints the initial random
        // positions immediately and the explicit Watchdog fits them.
        String src = readViewerJs();
        Pattern options = Pattern.compile(
                "var\\s+options\\s*=\\s*\\{([\\s\\S]*?)\\n        \\};");
        Matcher m = options.matcher(src);
        assertTrue(m.find(), "vis options object must be defined");
        String body = m.group(1);
        assertFalse(body.matches("(?s).*[\\s\\S]*stabilization\\s*:[\\s\\S]*"),
                "vis options must NOT include a stabilization: {...} block — it blocks the "
                        + "first paint until Force-Atlas2 converges (which it never does on a "
                        + "1010-edge graph), and the user sees an empty canvas");
    }

    @Test
    void doBootRegistersWatchdogPollingForCanvasFit() throws Exception {
        // doBoot() must install a setInterval that calls fitCanvas()
        // repeatedly after construction. The earlier boot path only
        // called fitCanvas() once at construction time and registered
        // a ResizeObserver that fires on size CHANGES; if the container
        // settles at a positive size without a change event (the typical
        // case after the FillLayout flush during SwitchingViewer.switchTo),
        // the observer never got a chance to call setSize. The explicit
        // polling covers that race.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function\\s+doBoot\\(container\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "doBoot must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("setInterval"),
                "doBoot must register a setInterval-based Watchdog that polls fitCanvas() — "
                        + "the earlier ResizeObserver-only path left vis stuck on 0×0 when the "
                        + "container settled at a positive size without a change event");
        assertTrue(fn.contains("fitCanvas"),
                "doBoot's Watchdog must call fitCanvas() — that's the whole point of the "
                        + "polling fallback (retrying the canvas resize until it succeeds)");
    }

    @Test
    void bootGuardsSolelyOnNetworkReady() throws Exception {
        // The boot() entry point must guard only on networkReady, not
        // on a separate booting flag. The earlier booting-flag variant
        // blocked the ResizeObserver path while doBoot was still running
        // and left vis stuck on a 0×0 canvas after engine switches.
        String src = readViewerJs();
        Pattern bootBody = Pattern.compile(
                "function\\s+boot\\(container\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = bootBody.matcher(src);
        assertTrue(m.find(), "boot() must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("networkReady"),
                "boot() must guard on networkReady (the source of truth)");
        assertFalse(fn.contains("booting"),
                "boot() must NOT reference the obsolete booting flag — that guard blocked "
                        + "the ResizeObserver fallback during doBoot and left vis stuck on 0×0");
    }
}
