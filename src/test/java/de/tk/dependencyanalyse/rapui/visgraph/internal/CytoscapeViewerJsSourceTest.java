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

/**
 * Static checks against the cytoscape-viewer.js source. These guard the
 * contract between the Java bridge and the JavaScript renderer:
 *
 * <ul>
 *   <li>{@code imageNodeStyle()} must exist and emit a {@code node[?image]}
 *       selector with {@code background-image: data(image)}.</li>
 *   <li>{@code applyElements()} / {@code cgv_applyNodeConfig()} /
 *       {@code cgv_applyLeidenColors()} must invoke
 *       {@code preloadSvgImagesAndRedraw()} so SVG badges are not drawn
 *       into the texture cache before the browser has finished parsing
 *       the {@code data:image/svg+xml} URI.</li>
 * </ul>
 *
 * <p>The renderer bug fixed here: Cytoscape's {@code drawNode} only paints
 * {@code background-image} when the underlying {@code Image} object is
 * {@code complete}. The first frame was being committed to the layer
 * cache before the SVG finished parsing, so badges stayed invisible. The
 * preload-then-redraw workaround lives in
 * {@code preloadSvgImagesAndRedraw()} and is now invoked everywhere a
 * stylesheet swap could re-prime the layer cache.</p>
 */
class CytoscapeViewerJsSourceTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/cytoscape/cytoscape-viewer.js",
            "target/classes/static/cytoscape/cytoscape-viewer.js",
    };

    private static String readViewerJs() throws IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("cytoscape-viewer.js not found in any known location");
    }

    @Test
    void imageNodeStyleRuleTargetsNodeWithImageAttribute() throws Exception {
        String src = readViewerJs();

        // The function must exist.
        assertTrue(src.contains("function imageNodeStyle"),
                "cytoscape-viewer.js must define imageNodeStyle()");

        // The selector must be 'node[?image]' (matches every node whose
        // data has a non-empty `image` field — which is exactly what the
        // Java bridge sets on each SVG-badge node).
        Pattern selector = Pattern.compile(
                "selector:\\s*'node\\[\\?image\\]'");
        assertTrue(selector.matcher(src).find(),
                "imageNodeStyle() must use the 'node[?image]' selector");

        // background-image must point at data(image) — the same property
        // the Java serializer writes into the Cytoscape elements payload.
        assertTrue(src.contains("'background-image': 'data(image)'"),
                "imageNodeStyle() must set background-image to data(image)");

        // The node shape must be a rounded rectangle so background-clip:node
        // can clip the SVG to the badge outline.
        assertTrue(src.contains("'shape': 'round-rectangle'"),
                "imageNodeStyle() must use shape: round-rectangle");
        assertTrue(src.contains("'background-clip': 'node'"),
                "imageNodeStyle() must set background-clip: node");

        // The Cytoscape-native label must be suppressed because the SVG
        // already contains the rendered label text.
        assertTrue(src.contains("'label': ''"),
                "imageNodeStyle() must suppress Cytoscape's native label");

        // Minimum-size floor. SVG badges can carry a 40x40 icon body but
        // the surrounding padding makes them hard to read at that size;
        // min-width/min-height gives layout collision detection a
        // larger bounding box (Cytoscape uses max(width, min-width)),
        // which also prevents adjacent badges from overlapping.
        assertTrue(src.contains("'min-width': 50"),
                "imageNodeStyle() must set min-width: 50 so SVG badges stay legible");
        assertTrue(src.contains("'min-height': 31"),
                "imageNodeStyle() must set min-height: 31 to match the badge's height");
    }

    @Test
    void preloadSvgImagesAndRedrawExistsAndIsInvoked() throws Exception {
        String src = readViewerJs();

        assertTrue(src.contains("function preloadSvgImagesAndRedraw"),
                "viewer must define preloadSvgImagesAndRedraw()");

        // Find every call site of preloadSvgImagesAndRedraw(); there must
        // be at least three: applyElements, cgv_applyNodeConfig,
        // cgv_applyLeidenColors. autoLoadFallback has its own merge site.
        // We match the closing `()` on its own line so we don't accidentally
        // count the definition `function preloadSvgImagesAndReddraw()`.
        Pattern call = Pattern.compile("preloadSvgImagesAndRedraw\\(\\)\\s*;");
        Matcher m = call.matcher(src);
        int hits = 0;
        while (m.find()) {
            hits++;
        }
        assertTrue(hits >= 3,
                "preloadSvgImagesAndRedraw() must be called from at least 3 places "
                        + "(applyElements, cgv_applyNodeConfig, cgv_applyLeidenColors); "
                        + "found " + hits + " call sites");
    }

    @Test
    void applyElementsCallsPreloadAfterResizingContainer() throws Exception {
        String src = readViewerJs();
        // Find the applyElements body and confirm preloadSvgImagesAndRedraw
        // is called AFTER cy.resize() — the resize must complete first or
        // the layer cache re-initializes with empty image slots.
        Pattern applyBody = Pattern.compile(
                "function applyElements\\(elements\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}\\s*\\n", Pattern.MULTILINE);
        Matcher m = applyBody.matcher(src);
        assertTrue(m.find(), "applyElements() must be present in the viewer source");
        String body = m.group(1);
        // applyElements must call preloadSvgImagesAndRedraw() — the
        // function schedules the post-image-load layout + cy.resize()
        // once every Image object reports complete=true.
        int preloadIdx = body.indexOf("preloadSvgImagesAndRedraw()");
        assertTrue(preloadIdx >= 0,
                "applyElements() must call preloadSvgImagesAndRedraw() so the layout "
                        + "runs against the final image bounding boxes, not the default 40x40");
        // applyElements must NOT run the layout synchronously anymore —
        // doing so paints edges against pre-image bounding boxes and the
        // user sees "edges disappear" after the next reflow.
        assertFalse(body.matches("(?s).*[\\s\\S]*cy\\.layout\\(.*"),
                "applyElements() must NOT run a synchronous cy.layout() — the layout "
                        + "must run from preloadSvgImagesAndRedraw's image-load callback");
    }

    @Test
    void preloadSvgImagesAndRedrawRunsLayoutAfterImagesLoad() throws Exception {
        // Regression guard for the "edges disappear after Load Data…"
        // bug: the layout + cy.fit() must run ONLY after every Image has
        // reported complete=true. Running them synchronously inside
        // applyElements paints edges against the default 40×40 bounding
        // boxes (the SVG data URIs have not parsed yet) and produces
        // a transient "no edges" state that the next reflow does not
        // always recover from.
        String src = readViewerJs();
        // preloadSvgImagesAndRedraw() must define a runPostLoadLayout (or
        // similarly-named) function that contains cy.layout(...) and is
        // called from inside the im.onload/onerror handler.
        assertTrue(src.contains("runPostLoadLayout"),
                "preloadSvgImagesAndRedraw must define runPostLoadLayout() that runs "
                        + "the layout AFTER images have reported complete=true");
        // Find the function body.
        Pattern fnBody = Pattern.compile(
                "function runPostLoadLayout\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = fnBody.matcher(src);
        assertTrue(m.find(), "runPostLoadLayout function body not found");
        String fn = m.group(1);
        // Must contain the layout + fit calls.
        assertTrue(fn.contains("cy.layout"),
                "runPostLoadLayout must run a Cytoscape layout");
        assertTrue(fn.contains("cy.fit"),
                "runPostLoadLayout must run cy.fit to frame the new graph");
        // Must be invoked from the im.onload / im.onerror handler so it
        // runs AFTER every image has loaded (or failed). vis-network
        // sets both onload and onerror to the same handler via
        // `im.onload = im.onerror = function () { ... }`.
        // We extract a window of 400 chars after `im.onload = im.onerror`
        // and check if `runPostLoadLayout` appears inside it.
        int imHandler = src.indexOf("im.onload = im.onerror");
        assertTrue(imHandler >= 0,
                "preloadSvgImagesAndRedraw must register an im.onload/im.onerror handler");
        String window = src.substring(imHandler, Math.min(imHandler + 600, src.length()));
        assertTrue(window.contains("runPostLoadLayout"),
                "runPostLoadLayout must be invoked from the im.onload/im.onerror "
                        + "handler so the layout runs against the final image dimensions");
    }

    @Test
    void imageNodeStyleIsAppendedAfterAllDefaultsInEveryStylePush() throws Exception {
        // In every place the bridge replaces the stylesheet
        // (applyNodeConfig, applyLeidenColors, autoLoadFallback's Leiden
        // apply path) we must put imageNodeStyle() at the END of the
        // merged array. Cytoscape applies the LAST matching rule, so a
        // late entry is what guarantees background-image wins over
        // background-color from tag / Leiden overrides.
        //
        // Two patterns appear in the source:
        //   var merged = defaults.concat(styles).concat([imageNodeStyle()]);
        //   style.push(imageNodeStyle());
        // Both append imageNodeStyle() to the array passed into fromJson().
        String src = readViewerJs();

        // The simplest invariant: every fromJson(...).update() must be
        // preceded (within the same lexical scope, ~600 chars) by a
        // reference to imageNodeStyle(). That covers both patterns.
        Pattern fromJson = Pattern.compile(
                "fromJson\\(([\\s\\S]{0,60}?)\\)\\.update\\(\\)");
        Matcher m = fromJson.matcher(src);
        int seen = 0;
        int withImageNode = 0;
        while (m.find()) {
            seen++;
            // Look back ~600 chars for an imageNodeStyle() reference.
            int lookbackStart = Math.max(0, m.start() - 600);
            String context = src.substring(lookbackStart, m.start());
            if (context.contains("imageNodeStyle()")) {
                withImageNode++;
            }
        }
        assertTrue(seen >= 2,
                "expected at least 2 fromJson().update() style pushes; found " + seen);
        assertEqualsInt(seen, withImageNode,
                "every fromJson().update() site must reference imageNodeStyle() in its build-up");
    }

    private static void assertEqualsInt(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + " (expected " + expected + " but was " + actual + ")");
        }
    }

    @Test
    void hoverHandlersAreScopedToImageNodes() throws Exception {
        String src = readViewerJs();

        // The hover highlight listener must scope to 'node[?image]'
        // so plain (non-badge) nodes don't flash a colored border when
        // the user moves the mouse across them.
        Pattern hoverSelector = Pattern.compile(
                "cy\\.on\\('(mouseover|mouseout)',\\s*'node\\[\\?image\\]'");
        assertTrue(hoverSelector.matcher(src).find(),
                "mouseover/mouseout must be scoped to 'node[?image]'");
    }

    @Test
    void preloadSvgImagesAndRedrawSkipsNodesWithoutDataImage() throws Exception {
        // Defensive: the preload function must skip nodes that don't carry
        // data.image (the function is called unconditionally after every
        // style swap). If it doesn't guard, it would create phantom Image
        // objects for nodes without a badge — wasteful but harmless, so
        // we just check the guard is present.
        String src = readViewerJs();

        // Find the function body.
        Pattern body = Pattern.compile(
                "function preloadSvgImagesAndRedraw\\(\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "preloadSvgImagesAndRedraw() must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("n.data('image')"),
                "preloadSvgImagesAndRedraw() must guard on n.data('image') being present");
    }

    @Test
    void cytoscapeMiniBundleContainsRoundRectangleShape() throws Exception {
        // Sanity check that the bundled cytoscape.min.js actually exposes
        // the round-rectangle shape our imageNodeStyle() targets. If a
        // future bundle upgrade drops this shape the style silently
        // degrades to ellipse (Cytoscape falls back gracefully but the
        // Rounded-Rect badge loses its rounded corners).
        Path cyMin = Paths.get("src/main/resources/static/cytoscape/cytoscape.min.js");
        if (!Files.exists(cyMin)) {
            cyMin = Paths.get("target/classes/static/cytoscape/cytoscape.min.js");
        }
        assertNotNull(cyMin, "cytoscape.min.js must be present in static/cytoscape/");
        String src = new String(Files.readAllBytes(cyMin), StandardCharsets.UTF_8);
        assertTrue(src.contains("round-rectangle") || src.contains("roundrectangle"),
                "cytoscape.min.js must register the round-rectangle shape");
    }

    @Test
    void cgvApplyNodeImagesHandlerExistsAndPreloadsBeforeRedraw() throws Exception {
        // The Java bridge ships `cgv_applyNodeImages([{id, image}, ...])`
        // whenever a NodeConfig color override re-renders an SVG-badge
        // node. The handler must exist, must preload every incoming URI
        // through `new Image()` (so the texture cache doesn't repaint
        // with the cached empty placeholder), and must emit 'background'
        // on each touched node once all loads have fired.
        String src = readViewerJs();

        assertTrue(src.contains("window.cgv_applyNodeImages"),
                "viewer must register window.cgv_applyNodeImages");
        // The handler is defined as `window.cgv_applyNodeImages = function (...)`.
        assertTrue(src.contains("cgv_applyNodeImages = function"),
                "cgv_applyNodeImages must be a function expression");

        // The handler must read every update's image into an Image() so
        // the new sprite is parsed before we tell Cytoscape to repaint.
        // Find the function body — handle both `function name(...)` and
        // `name = function (...)` styles.
        Pattern body = Pattern.compile(
                "cgv_applyNodeImages\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "cgv_applyNodeImages must define a function body");
        String fn = m.group(1);
        assertTrue(fn.contains("new Image()"),
                "cgv_applyNodeImages must construct new Image() to preload each URI");
        assertTrue(fn.contains("onload"),
                "cgv_applyNodeImages must register an onload handler");
        assertTrue(fn.contains("emit('background')"),
                "cgv_applyNodeImages must emit 'background' on each touched node");
    }

    @Test
    void applyNodeConfigCallsApplyNodeImagesWhenRecolorHappens() throws Exception {
        // The pipeline contract: every call to the Java bridge's
        // applyNodeConfig must delegate to SvgBadgeColorUpdater so that
        // nodes whose effective color changed get re-shipped to the
        // iframe. Verify this in source by ensuring both the helper
        // class is referenced AND the cgv_applyNodeImages call site is
        // present in applyNodeConfig.
        Path bridgeSrc = Paths.get(
                "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/CytoscapeJsBridge.java");
        if (!Files.exists(bridgeSrc)) {
            bridgeSrc = Paths.get(
                    "target/classes/de/tk/dependencyanalyse/rapui/visgraph/internal/CytoscapeJsBridge.java");
        }
        assertNotNull(bridgeSrc, "CytoscapeJsBridge.java must be on the classpath");
        String src = new String(Files.readAllBytes(bridgeSrc), StandardCharsets.UTF_8);

        assertTrue(src.contains("SvgBadgeColorUpdater"),
                "CytoscapeJsBridge must delegate recoloring to SvgBadgeColorUpdater");
        assertTrue(src.contains("cgv_applyNodeImages"),
                "CytoscapeJsBridge must ship the recolored URIs via cgv_applyNodeImages");
    }

    @Test
    void fcoseNodeRepulsionDefaultIsHighEnoughToPreventOverlap() throws Exception {
        // fcose's repulsion force scales with nodeRepulsion; a value
        // that's too low lets the spring forces drag adjacent nodes
        // on top of each other. The viewer uses min-width: 50 on SVG
        // badges, so 12000 (the historical value) is no longer
        // sufficient — we bumped to 18000. Guard the default here so
        // a future regression that drops it back is caught at build time.
        String src = readViewerJs();
        // Match the assignment that DEFAULTS nodeRepulsion for fcose.
        // We anchor on the typeof-guard so we don't accidentally pick up
        // a numeric literal in a comment ("nodeRepulsion=50 collapsed…").
        Pattern fcoseDefault = Pattern.compile(
                "layoutOpts\\.nodeRepulsion\\s*=\\s*(\\d+)");
        Matcher m = fcoseDefault.matcher(src);
        assertTrue(m.find(),
                "fcose branch must default nodeRepulsion to a numeric literal; "
                        + "could not find `layoutOpts.nodeRepulsion = N`");
        int value = Integer.parseInt(m.group(1));
        assertTrue(value >= 15000,
                "fcose nodeRepulsion default must be >= 15000 to keep SVG badges "
                        + "(min-width: 50) non-overlapping; got " + value);
    }

    @Test
    void runLayoutDoesNotForceUserOverrideOfNodeRepulsion() throws Exception {
        // The fcose branch only fills in nodeRepulsion when the caller
        // did NOT already specify one (`typeof !== 'number'`). A future
        // refactor that drops the guard would silently overwrite the
        // user's chosen value — this test guards the contract.
        String src = readViewerJs();
        assertTrue(src.contains("typeof layoutOpts.nodeRepulsion !== 'number'"),
                "fcose branch must guard the nodeRepulsion default with a typeof check");
        assertTrue(src.contains("layoutOpts.nodeRepulsion = 18000"),
                "fcose branch must default nodeRepulsion to 18000");
    }
}
