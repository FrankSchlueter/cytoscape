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

    private static final String[] POSSIBLE_HTML_PATHS = {
            "src/main/resources/static/cytoscape-viewer.html",
            "target/classes/static/cytoscape-viewer.html",
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

    private static String readViewerHtml() throws IOException {
        for (String p : POSSIBLE_HTML_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("cytoscape-viewer.html not found in any known location");
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

    /* ------------------------------------------------------------------ */
    /*  Cluster-Layout-Strategie (Cluster-Layout.md) guards              */
    /* ------------------------------------------------------------------ */

    @Test
    void injectClusterParentsFunctionExists() throws Exception {
        // The cytoscape bridge must inject one compound-parent node per
        // Leiden community into the elements array before cy.add() so
        // fcose sees them as physical barriers (Cluster-Layout.md §1,
        // step 2). The function must exist with that exact name.
        String src = readViewerJs();
        assertTrue(src.contains("function injectClusterParents"),
                "cytoscape-viewer.js must define injectClusterParents() to add compound-parent nodes per Leiden community");
    }

    @Test
    void applyElementsCallsInjectClusterParentsWhenLeidenAndClusterOptionsPresent() throws Exception {
        // The injection must run inside applyElements() — BEFORE cy.add() —
        // and only when both leidenColors AND the cluster-layout options
        // are present (otherwise we'd pollute plain fcose runs with
        // phantom compound parents).
        String src = readViewerJs();

        // 1. applyElements must reference injectClusterParents in its body.
        Pattern applyBody = Pattern.compile(
                "function applyElements\\(elements\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}\\s*\\n", Pattern.MULTILINE);
        Matcher m = applyBody.matcher(src);
        assertTrue(m.find(), "applyElements() must be defined");
        String body = m.group(1);
        assertTrue(body.contains("injectClusterParents"),
                "applyElements() must call injectClusterParents() so compound parents are added before cy.add()");
        // 2. The guard must mention both leidenColors and the cluster
        //    layout predicate — otherwise plain graphs would get
        //    compound parents too.
        assertTrue(body.contains("leidenColors"),
                "injectClusterParents() must be guarded by the presence of leidenColors");
        assertTrue(body.contains("isClusterLayoutActive"),
                "injectClusterParents() must be guarded by isClusterLayoutActive() so plain runs are unaffected");

        // 3. injectClusterParents must run BEFORE cy.add() in the body.
        int injectIdx = body.indexOf("injectClusterParents");
        int addIdx = body.indexOf("cy.add(elements)");
        assertTrue(injectIdx >= 0 && addIdx >= 0 && injectIdx < addIdx,
                "injectClusterParents() must run before cy.add(elements); injectIdx=" + injectIdx
                        + " addIdx=" + addIdx);
    }

    @Test
    void isClusterLayoutActiveDetectsClusterOptions() throws Exception {
        // The helper that decides whether to preseed cluster centers and
        // merge the compound-parent stylesheet must inspect the pending
        // layout options for the Cluster-Layout-Strategie signature.
        String src = readViewerJs();
        assertTrue(src.contains("function isClusterLayoutActive"),
                "cytoscape-viewer.js must define isClusterLayoutActive()");
        Pattern body = Pattern.compile(
                "function isClusterLayoutActive\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "isClusterLayoutActive must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("gravityCompound"),
                "isClusterLayoutActive must check gravityCompound (ClusterLayoutOptions.GRAVITY_COMPOUND)");
        assertTrue(fn.contains("gravityRangeCompound"),
                "isClusterLayoutActive must check gravityRangeCompound (ClusterLayoutOptions.GRAVITY_RANGE_COMPOUND)");
        assertTrue(fn.contains("idealInterClusterEdgeLength"),
                "isClusterLayoutActive must check idealInterClusterEdgeLength (ClusterLayoutOptions.IDEAL_INTER_CLUSTER_EDGE_LENGTH)");
    }

    @Test
    void clusterCompoundStyleTargetsIsClusterNodes() throws Exception {
        // The compound-parent style must use the node[?isCluster] selector
        // and apply the Cluster-Layout.md CSS (dashed border, padding,
        // faint background, suppressed label).
        String src = readViewerJs();
        assertTrue(src.contains("function clusterCompoundStyle"),
                "cytoscape-viewer.js must define clusterCompoundStyle()");

        Pattern body = Pattern.compile(
                "function clusterCompoundStyle\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "clusterCompoundStyle must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("'node[?isCluster]'"),
                "clusterCompoundStyle must target the node[?isCluster] selector so compound parents get the dashed-border look");
        assertTrue(fn.contains("'border-style': 'dashed'"),
                "clusterCompoundStyle must set border-style: dashed per Cluster-Layout.md §4");
        assertTrue(fn.contains("'padding'"),
                "clusterCompoundStyle must set padding so fcose leaves room between member nodes and the box edge");
        assertTrue(fn.contains("'background-opacity'"),
                "clusterCompoundStyle must set a faint background-opacity per Cluster-Layout.md §4");
        assertTrue(fn.contains("data(_color)"),
                "clusterCompoundStyle must read the cluster colour from data(_color) so the border matches the community");
    }

    @Test
    void clusterEdgeStyleUsesBezierWithControlPointStepSize() throws Exception {
        // Cluster-Layout.md §4 mandates curve-style: bezier +
        // control-point-step-size for the bidirectional-edge "entanglement"
        // fix.
        String src = readViewerJs();
        assertTrue(src.contains("function clusterEdgeStyle"),
                "cytoscape-viewer.js must define clusterEdgeStyle()");

        Pattern body = Pattern.compile(
                "function clusterEdgeStyle\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "clusterEdgeStyle must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("'curve-style': 'bezier'"),
                "clusterEdgeStyle must set curve-style: bezier so parallel/bidirectional edges spread");
        assertTrue(fn.contains("'control-point-step-size': 45"),
                "clusterEdgeStyle must set control-point-step-size: 45 per Cluster-Layout.md §4");
        // Sqrt-based sub-linear width scaling: range 0.6..2.4 px for
        // logWeight in [0, 4] (clamped). Replaces the old mapData(...)
        // which clamped real values to the floor because the real logWeight
        // range is ~0.69..9.21, not 1..10.
        assertTrue(fn.contains("Math.sqrt"),
                "clusterEdgeStyle must use sqrt-based sub-linear width scaling");
        assertTrue(fn.contains("logWeight"),
                "clusterEdgeStyle must read the logWeight Cytoscape attribute");
    }

    @Test
    void clusterStyleRulesMergedIntoAllThreeStylePushSites() throws Exception {
        // Three places rebuild the stylesheet dynamically (they all use
        // defaults.concat(styles).concat([imageNodeStyle()]) or the
        // style.push(imageNodeStyle()) form). Each must inject the
        // clusterStyleRules() between the styles and imageNodeStyle() so
        // the compound-parent rule wins over per-node Leiden colours but
        // loses to imageNodeStyle() (so SVG badges still render).
        String src = readViewerJs();
        // Look for every line that mentions clusterStyleRules(). The
        // minimum is three: the three fromJson sites.
        Pattern uses = Pattern.compile("clusterStyleRules\\(\\)");
        Matcher m = uses.matcher(src);
        int hits = 0;
        while (m.find()) hits++;
        assertTrue(hits >= 4,
                "clusterStyleRules() must be invoked from at least 4 places "
                        + "(3 stylesheet rebuilds + the runPostLoadLayout override); found " + hits);
    }

    @Test
    void runPostLoadLayoutForcesRandomizeFalseWhenClusterLayoutActive() throws Exception {
        // The cytoscape bridge must keep the preseeded cluster centres
        // AND the compound-parent barriers by overriding the cluster
        // option's randomize=true to randomize=false inside the
        // runPostLoadLayout path. Without this override, fcose discards
        // the preseed and the user sees a flat blob.
        String src = readViewerJs();
        assertTrue(src.contains("runPostLoadLayout"),
                "runPostLoadLayout must be defined");
        Pattern body = Pattern.compile(
                "function runPostLoadLayout\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "runPostLoadLayout must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("isClusterLayoutActive"),
                "runPostLoadLayout must call isClusterLayoutActive() to detect the cluster-layout path");
        assertTrue(fn.contains("randomize: false"),
                "runPostLoadLayout must override randomize to false when cluster layout is active so the preseed positions survive fcose");
    }

    @Test
    void clusterParentIdFormatMatchesJavaHelper() throws Exception {
        // The Java helper ClusterLayoutOptions.clusterParentId(idx) returns
        // 'cluster_<idx>'. The JS-side injectClusterParents must use the
        // SAME format or cytoscape would create the wrong parent nodes.
        String src = readViewerJs();
        assertTrue(src.contains("'cluster_' + idx"),
                "injectClusterParents must build the cluster_<idx> id using the same format as ClusterLayoutOptions.clusterParentId");
    }

    /* ------------------------------------------------------------------ */
    /*  Pre-Layout Edge-Filter (Cluster-Layout.md §5)                   */
    /* ------------------------------------------------------------------ */

    @Test
    void partitionEdgesForLayoutExistsAndIsCalledFromApplyElements() throws Exception {
        // Cluster-Layout.md §5: "Berechnen Sie das fCoSE-Layout
        // ausschließlich mit Kanten, die ein logarithmisches Gewicht
        // von über z.B. 4.0 haben." The cytoscape bridge must define
        // partitionEdgesForLayout() and call it inside applyElements
        // BEFORE cy.add().
        String src = readViewerJs();
        assertTrue(src.contains("function partitionEdgesForLayout"),
                "cytoscape-viewer.js must define partitionEdgesForLayout() for the Cluster-Layout.md §5 pre-layout filter");

        // applyElements must reference it before cy.add().
        Pattern applyBody = Pattern.compile(
                "function applyElements\\(elements\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}\\s*\\n", Pattern.MULTILINE);
        Matcher m = applyBody.matcher(src);
        assertTrue(m.find(), "applyElements() must be defined");
        String body = m.group(1);
        assertTrue(body.contains("partitionEdgesForLayout"),
                "applyElements() must call partitionEdgesForLayout() so weak edges are held back from fcose");
        assertTrue(body.contains("prefilterMinLogWeight"),
                "applyElements() must read the user-selected threshold via prefilterMinLogWeight()");
        int partitionIdx = body.indexOf("partitionEdgesForLayout");
        int addIdx = body.indexOf("cy.add(elements)");
        assertTrue(partitionIdx >= 0 && addIdx >= 0 && partitionIdx < addIdx,
                "partitionEdgesForLayout() must run before cy.add(elements); "
                        + "partitionIdx=" + partitionIdx + " addIdx=" + addIdx);
    }

    @Test
    void restoreHeldBackEdgesAddedOnLayoutstop() throws Exception {
        // The pre-layout filter holds back weak edges so fcose sees a
        // leaner graph. They must be re-added to the canvas once the
        // layout has settled (Cluster-Layout.md §5: "...erst visuell
        // hinzu, nachdem das Layout fertig berechnet ist (layout.run()).").
        String src = readViewerJs();
        assertTrue(src.contains("function restoreHeldBackEdges"),
                "cytoscape-viewer.js must define restoreHeldBackEdges()");
        assertTrue(src.contains("pendingHeldBackEdges"),
                "cytoscape-viewer.js must reference pendingHeldBackEdges to track the held-back queue");

        // Must be invoked from BOTH layoutstop handlers (the main
        // runLayout fcose path and the fallback preset path).
        Pattern layoutstop = Pattern.compile(
                "layout\\.on\\(\\s*'layoutstop'\\s*,\\s*function");
        Matcher m = layoutstop.matcher(src);
        int handlers = 0;
        while (m.find()) handlers++;
        assertTrue(handlers >= 1,
                "expected at least one layout.on('layoutstop', ...) handler; found " + handlers);

        // The function body's first layoutstop handler must call
        // restoreHeldBackEdges(). Match any indentation level so the
        // test stays robust against refactors that re-indent the body.
        Pattern firstHandler = Pattern.compile(
                "layout\\.on\\(\\s*'layoutstop'\\s*,\\s*function\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n[ \\t]+\\}\\s*\\)");
        Matcher fm = firstHandler.matcher(src);
        assertTrue(fm.find(), "could not extract the first layoutstop handler");
        String handlerBody = fm.group(1);
        assertTrue(handlerBody.contains("restoreHeldBackEdges"),
                "the main runLayout fcose layoutstop handler must call restoreHeldBackEdges()");
    }

    @Test
    void prefilterMinLogWeightReadsPendingOptions() throws Exception {
        // The threshold comes from pendingLayoutOptions.prefilterMinLogWeight
        // — the Java helper ClusterLayoutOptions.buildFcoseOptions serialises
        // this key. The JS-side accessor must guard against missing /
        // non-numeric / non-positive values (the "filter disabled" sentinel).
        String src = readViewerJs();
        assertTrue(src.contains("function prefilterMinLogWeight"),
                "cytoscape-viewer.js must define prefilterMinLogWeight()");
        Pattern body = Pattern.compile(
                "function prefilterMinLogWeight\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "prefilterMinLogWeight must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("prefilterMinLogWeight"),
                "prefilterMinLogWeight must read the pendingLayoutOptions.prefilterMinLogWeight key");
        assertTrue(fn.contains("<=") && fn.contains("0"),
                "prefilterMinLogWeight must treat non-positive values as 'filter disabled' (OFF = 0)");
    }

    @Test
    void runPostLoadLayoutRestoresHeldBackEdgesOnPresetDirectPath() throws Exception {
        // When the mapped layout is 'preset' (e.g. the LEIDEN_GRID path),
        // there is no fcose 'layoutstop' to trigger restoreHeldBackEdges().
        // The preset-direct branch in runPostLoadLayout must call it
        // itself so the held-back edges aren't stranded off-canvas.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function runPostLoadLayout\\s*\\(\\s*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "runPostLoadLayout must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("restoreHeldBackEdges"),
                "runPostLoadLayout must call restoreHeldBackEdges() so the preset-direct path also restores weak edges");
    }

    /* ------------------------------------------------------------------ */
    /*  Cluster-Edges-Tabelle (Legend-Detail)                            */
    /* ------------------------------------------------------------------ */

    @Test
    void cgvSidePanelAndEdgesTableExistInHtml() throws Exception {
        // The Legend panel must now live inside a flex-column wrapper
        // (#cgv-side-panel) that also hosts the new edges table. The
        // table panel (#cgv-edges) must exist with the three columns
        // (From, Weight, To) so the renderer can build a matching
        // <thead> on demand.
        String html = readViewerHtml();
        assertTrue(html.contains("id=\"cgv-side-panel\""),
                "cytoscape-viewer.html must define the #cgv-side-panel wrapper that hosts legend + edges table");
        assertTrue(html.contains("id=\"cgv-edges\""),
                "cytoscape-viewer.html must define the #cgv-edges panel");
        assertTrue(html.contains("id=\"cgv-legend\""),
                "cytoscape-viewer.html must still define the #cgv-legend panel (relocated inside the wrapper)");
        assertTrue(html.contains("cgv-edges-table"),
                "cytoscape-viewer.html must style the .cgv-edges-table class for the edges table");
    }

    @Test
    void cgvSidePanelUsesFlexColumnLayout() throws Exception {
        // The wrapper must stack legend + edges table vertically with
        // flex column direction so adding/removing the edges panel does
        // not disturb the legend's vertical position.
        String html = readViewerHtml();
        Pattern sidePanelCss = Pattern.compile(
                "#cgv-side-panel\\s*\\{[^}]*?display:\\s*flex[^}]*?flex-direction:\\s*column",
                Pattern.DOTALL);
        assertTrue(sidePanelCss.matcher(html).find(),
                "#cgv-side-panel must use display:flex and flex-direction:column so legend + edges stack vertically");
    }

    @Test
    void renderEdgesTableFunctionExistsAndIsWiredIntoApplyLegendHighlight() throws Exception {
        // renderEdgesTable() must exist and be invoked at the END of
        // applyLegendHighlight() — after the cy.batch() call that sets
        // the inline border/opacity styles, because renderEdgesTable
        // reads n.style('background-color') and depends on the styles
        // being committed.
        String src = readViewerJs();
        assertTrue(src.contains("function renderEdgesTable"),
                "cytoscape-viewer.js must define renderEdgesTable() so the edges table rebuilds on every legend click");

        Pattern applyBody = Pattern.compile(
                "function applyLegendHighlight\\(hex\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}\\s*\\n",
                Pattern.MULTILINE);
        Matcher m = applyBody.matcher(src);
        assertTrue(m.find(), "applyLegendHighlight must be defined");
        String body = m.group(1);
        assertTrue(body.contains("renderEdgesTable(hex)"),
                "applyLegendHighlight must call renderEdgesTable(hex) so the table updates when a legend entry is clicked");
    }

    @Test
    void hideEdgesTableFunctionExistsAndIsCalledFromClearLegendHighlight() throws Exception {
        // hideEdgesTable() must exist and be called at the TOP of
        // clearLegendHighlight() so the panel disappears the moment the
        // user clicks the same legend entry twice, taps the background,
        // or disables the legend.
        String src = readViewerJs();
        assertTrue(src.contains("function hideEdgesTable"),
                "cytoscape-viewer.js must define hideEdgesTable() to remove the edges panel when no cluster is highlighted");

        Pattern clearBody = Pattern.compile(
                "function clearLegendHighlight\\(\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}\\s*\\n",
                Pattern.MULTILINE);
        Matcher m = clearBody.matcher(src);
        assertTrue(m.find(), "clearLegendHighlight must be defined");
        String body = m.group(1);
        assertTrue(body.contains("hideEdgesTable"),
                "clearLegendHighlight must call hideEdgesTable() so the table disappears when the highlight is cleared");
    }

    @Test
    void onEdgeRowClickCallsJavaCallbackDirectly() throws Exception {
        // The table row click must invoke cgv_notifyRelationshipSelected
        // DIRECTLY (not indirectly via the 'tap edge' listener). The
        // tap-edge listener only fires for mouse events INSIDE the
        // canvas; since the table row is rendered outside the canvas
        // (#cgv-edges is a sibling of #cy), that listener never runs.
        // Without the direct javaCall the Java relListeners callback
        // would never fire — see cytoscape-viewer.js onEdgeRowClick.
        String src = readViewerJs();
        assertTrue(src.contains("function onEdgeRowClick"),
                "cytoscape-viewer.js must define onEdgeRowClick()");
        Pattern body = Pattern.compile(
                "function onEdgeRowClick\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "onEdgeRowClick must have a body");
        String fn = m.group(1);
        // 1) DIRECT javaCall — must come BEFORE edge.select() so the
        //    Java side is notified even if edge.select() fails or the
        //    selection event never reaches the listener.
        assertTrue(fn.contains("javaCall('cgv_notifyRelationshipSelected'"),
                "onEdgeRowClick must call javaCall('cgv_notifyRelationshipSelected', …) "
                        + "DIRECTLY — the 'tap edge' listener only fires for canvas-internal taps, "
                        + "and the table row lives outside the canvas. Without this direct call "
                        + "the Java relListeners callback is never triggered.");
        // 2) edge.select() must still happen so the Cytoscape selection
        //    visuals (red border) reflect the row click.
        assertTrue(fn.contains("edge.select(") || fn.contains(".select()"),
                "onEdgeRowClick must still call edge.select() so the Cytoscape selection visuals match the table row");
        assertTrue(fn.contains("unselect"),
                "onEdgeRowClick must clear the current Cytoscape selection before selecting the clicked edge");
    }

    @Test
    void displayNameForPrefersLabelThenNameThenId() throws Exception {
        // The table column 'From'/'To' must show human-readable labels.
        // Priority: data.label (set by GraphNode.toCytoscapeNode from
        // visualAttrs/caption) > data.name > node id. The fallback
        // ladder must be in that order.
        String src = readViewerJs();
        assertTrue(src.contains("function displayNameFor"),
                "cytoscape-viewer.js must define displayNameFor()");
        Pattern body = Pattern.compile(
                "function displayNameFor\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "displayNameFor must have a body");
        String fn = m.group(1);
        // label must be queried BEFORE name.
        int lblIdx = fn.indexOf("n.data('label')");
        int nameIdx = fn.indexOf("n.data('name')");
        assertTrue(lblIdx >= 0, "displayNameFor must read n.data('label') first");
        assertTrue(nameIdx >= 0, "displayNameFor must fall back to n.data('name')");
        assertTrue(lblIdx < nameIdx,
                "displayNameFor must query data('label') before data('name') — got label@"
                        + lblIdx + " name@" + nameIdx);
    }

    @Test
    void formatWeightRoundsLargeValuesAndHandlesMissing() throws Exception {
        // Edge weights from GraphRelationship.toCytoscapeEdge are
        // numbers but may be absent for unweighted relationships. The
        // Weight column must render an empty cell when missing and use
        // Math.round for values >= 100 so the column stays compact.
        String src = readViewerJs();
        assertTrue(src.contains("function formatWeight"),
                "cytoscape-viewer.js must define formatWeight()");
        Pattern body = Pattern.compile(
                "function formatWeight\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "formatWeight must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("Math.round"),
                "formatWeight must round large weights with Math.round so the column stays compact");
        assertTrue(fn.contains("return ''") || fn.contains("\"\""),
                "formatWeight must return an empty string for missing weights");
    }

    @Test
    void edgesTableSortIntraClusterFirstThenWeightDesc() throws Exception {
        // The sort key for the edges table: intra-cluster first, then
        // weight desc. Without this, the most informative rows would
        // be buried under weaker bridge edges.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function renderEdgesTable\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "renderEdgesTable must have a body");
        String fn = m.group(1);
        // Look for the sort lambda.
        assertTrue(fn.contains("intraCluster"),
                "renderEdgesTable must key the sort on intraCluster");
        assertTrue(fn.contains(".sort("),
                "renderEdgesTable must call .sort() on the rows array");
        // The comparator pattern: a.intraCluster !== b.intraCluster ? a.intraCluster ? -1 : 1 : ...
        assertTrue(fn.matches("(?s).*[\\s\\S]*\\?\\s*-1\\s*:\\s*1[\\s\\S]*"),
                "renderEdgesTable comparator must put intra-cluster edges first");
    }

    @Test
    void edgesTableRowsCarryIntraOrBridgeCssClass() throws Exception {
        // Visual distinction: intra-cluster rows get .cgv-edge-intra,
        // bridge rows get .cgv-edge-bridge (italic via CSS). The class
        // name is what the stylesheet uses to colour the row.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function renderEdgesTable\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "renderEdgesTable must have a body");
        String fn = m.group(1);
        assertTrue(fn.contains("'cgv-edge-intra'"),
                "renderEdgesTable must apply cgv-edge-intra className on intra-cluster rows");
        assertTrue(fn.contains("'cgv-edge-bridge'"),
                "renderEdgesTable must apply cgv-edge-bridge className on bridge rows");
    }
}
