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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static source-level checks against {@code cytoscape-viewer.js}.
 *
 * <p>The cytoscape bridge is JS-only (the Java side hands a JSON
 * payload over via the BrowserFunction queue, but the actual rendering
 * and event wiring happens in the iframe). Unit tests cannot exercise
 * the JS directly, so we verify the contract by inspecting the
 * JavaScript source.</p>
 *
 * <p>This guard class exists to catch the regression class where a
 * Cytoscape style-rule or layout helper is defined in the JS but
 * never wired into a caller — historically {@code ensureCommunityStyles}
 * and {@code preseedCommunityGridPositions} were both defined but
 * unused, leaving the community-aggregation view rendering with the
 * generic Cytoscape defaults. The tests below pin the call sites so
 * a refactor that drops the wiring fails at build time.</p>
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

    /* -------------------------------------------------------------- */
    /*  Community Aggregation view (root + detail)                    */
    /* -------------------------------------------------------------- */

    @Test
    void communityStyleRulesExportsArrayOfTwoRules() throws Exception {
        // communityStyleRules() must return an array containing the
        // node and edge rules so callers can splice it into the
        // stylesheet. Without this, the per-element style functions
        // communityNodeStyle/communityEdgeStyle are dead code.
        String src = readViewerJs();
        assertTrue(src.contains("function communityStyleRules()"),
                "communityStyleRules() factory must be defined");
        assertTrue(src.contains("communityNodeStyle()")
                && src.contains("communityEdgeStyle()"),
                "communityStyleRules must compose communityNodeStyle + communityEdgeStyle");
    }

    @Test
    void applyStyleForCommunityViewRebuildsStylesheet() throws Exception {
        // applyStyleForCommunityView() must rebuild the cytoscape
        // stylesheet so the node[?isCommunity] / edge[?isCommunityEdge]
        // rules actually reach the renderer. Without this the
        // community-nodes fall back to the generic 'node' rule (blue
        // circle) and the community-edges fall back to grey.
        //
        // We intentionally do NOT use cy.style().fromJson(...) here
        // because the community-node width/height are function mappers
        // (read data.incomingWeightSum / data.label) and fromJson
        // silently drops function values. The imperative
        // cy.style().selector().style().update() chain preserves them.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function applyStyleForCommunityView\\(mode\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "applyStyleForCommunityView() must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("cy.style()"),
                "applyStyleForCommunityView must rebuild the stylesheet via cy.style()");
        assertTrue(fn.contains("fromJson"),
                "applyStyleForCommunityView must use fromJson to replace the stylesheet "
                        + "(per-element _width / _height data fields make function mappers unnecessary)");
    }

    @Test
    void applyCommunityViewInvokesStyleRebuild() throws Exception {
        // The root+detail entry point must call the stylesheet rebuild
        // helper AFTER cy.add() so the new elements render with the
        // correct community-specific styles.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function applyCommunityView\\(mode, elements\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "applyCommunityView() must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("applyStyleForCommunityView(mode)"),
                "applyCommunityView must invoke the stylesheet rebuild with the active mode");
    }

    @Test
    void cgvClearCommunityViewRebuildsStylesheet() throws Exception {
        // Leaving the community view must restore the normal Cytoscape
        // stylesheet (defaults + cluster + image) so the freshly-pushed
        // original nodes render with their per-type colors instead of
        // dangling community rules.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "window\\.cgv_clearCommunityView = function\\s*\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\};");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "cgv_clearCommunityView must be defined");
        String fn = m.group(1);
        // The function delegates the stylesheet rebuild to
        // applyStyleWithoutCommunity() (defined next to
        // applyStyleForCommunityView). Either spelling is acceptable.
        assertTrue(fn.contains("applyStyleWithoutCommunity()")
                        || fn.contains("cy.style()"),
                "cgv_clearCommunityView must rebuild the stylesheet "
                        + "(via applyStyleWithoutCommunity or direct cy.style)");
    }

    @Test
    void cgvApplyNodeConfigReattachesCommunityStylesWhenActive() throws Exception {
        // While the user is in the community view, a NodeConfig update
        // (e.g. tag-color change) must NOT strip the community rules.
        // The function must check communityViewState and re-attach
        // communityStyleRules() in that branch.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "window\\.cgv_applyNodeConfig = function \\(config\\)\\s*\\{([\\s\\S]*?)\\n    \\};");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "cgv_applyNodeConfig must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("communityViewState"),
                "cgv_applyNodeConfig must check communityViewState to decide whether "
                        + "to re-attach community rules");
        assertTrue(fn.contains("communityStyleRules()"),
                "cgv_applyNodeConfig must re-attach community rules when in community view");
    }

    @Test
    void cgvApplyLeidenColorsReattachesCommunityStylesWhenActive() throws Exception {
        // Same reasoning as cgv_applyNodeConfig — a Leiden-color update
        // during the community view must preserve the community rules.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "window\\.cgv_applyLeidenColors = function \\(colors\\)\\s*\\{([\\s\\S]*?)\\n    \\};");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "cgv_applyLeidenColors must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("communityViewState"),
                "cgv_applyLeidenColors must check communityViewState");
        assertTrue(fn.contains("communityStyleRules()"),
                "cgv_applyLeidenColors must re-attach community rules when in community view");
    }

    @Test
    void ensureCommunityStylesIsRemoved() throws Exception {
        // The dead ensureCommunityStyles() helper was replaced by
        // applyStyleForCommunityView(). If it sneaks back in, it must
        // be wired up or the tests above would have already failed —
        // this test is a redundant guard against future regressions
        // where the dead helper gets re-introduced by accident.
        assertFalse(readViewerJs().contains("function ensureCommunityStyles"),
                "ensureCommunityStyles is dead code; use applyStyleForCommunityView instead");
    }

    /* -------------------------------------------------------------- */
    /*  Community Overview layout (Circle, polar coordinates)          */
    /* -------------------------------------------------------------- */

    @Test
    void communityOverviewUsesCircleLayout() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("function preseedCommunityCirclePositions"),
                "community overview must define preseedCommunityCirclePositions()");
        assertTrue(src.contains("preseedCommunityCirclePositions()"),
                "applyCommunityView must invoke the circle preseed");
    }

    @Test
    void circleLayoutUsesPolarCoordinates() throws Exception {
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function preseedCommunityCirclePositions\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "preseedCommunityCirclePositions() must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("Math.cos(angle)"),
                "circle layout must compute x = cos(angle) * radius");
        assertTrue(fn.contains("Math.sin(angle)"),
                "circle layout must compute y = sin(angle) * radius");
        assertTrue(fn.contains("Math.PI / 2"),
                "circle layout must start at 12 o'clock (-PI/2)");
    }

    @Test
    void circleLayoutSortsByMemberCountDescending() throws Exception {
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function preseedCommunityCirclePositions\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        // The sort comparator must put the larger memberCount first so
        // the largest community lands at 12 o'clock.
        assertTrue(fn.contains("memberCount"),
                "circle layout must read data('memberCount') for sorting");
        assertTrue(fn.contains("localeCompare"),
                "circle layout must use a deterministic id-based tiebreak for equal-size communities");
    }

    @Test
    void oldGridLayoutIsRemoved() throws Exception {
        // preseedCommunityGridPositions was the previous (rectangular)
        // implementation. The circle version replaces it; if both
        // exist we risk falling back to the grid via the wrong
        // identifier.
        assertFalse(readViewerJs().contains("preseedCommunityGridPositions"),
                "old preseedCommunityGridPositions must be removed in favour of "
                        + "preseedCommunityCirclePositions");
    }

    /* -------------------------------------------------------------- */
    /*  Edge styling (community edges)                                 */
    /* -------------------------------------------------------------- */

    @Test
    void communityEdgeUsesSourceCommunityColorAndToolTip() throws Exception {
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityEdgeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "communityEdgeStyle must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("data(sourceCommunityColor)"),
                "community edge line-color must come from the SOURCE community's colour");
        assertTrue(fn.contains("data(targetCommunityColor)"),
                "community edge arrow-color must come from the TARGET community's colour");
        // The tooltip can be wired either as a literal Cytoscape
        // mapping 'tooltip': 'data(tooltip)' or as a function mapper
        // 'tooltip': function (e) { return e.data('tooltip') ... }.
        // The mapper form is what the actual implementation uses so
        // the source-arrow side ('A->B tooltip') can fall back to ''.
        assertTrue(fn.contains("'tooltip'") && fn.contains("'tooltip')"),
                "community edge must read data('tooltip') for the hover label");
    }

    /* -------------------------------------------------------------- */
    /*  Community Overview visual design (R9)                         */
    /* -------------------------------------------------------------- */

    @Test
    void communityNodeUsesEllipseShape() throws Exception {
        // Per user spec: cluster-nodes must be CIRCLES, not rectangles.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityNodeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "communityNodeStyle must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("'shape': 'ellipse'"),
                "community node must use shape 'ellipse' (circle), not 'round-rectangle'");
        assertFalse(fn.contains("'shape': 'round-rectangle'"),
                "community node must NOT use 'round-rectangle'");
    }

    @Test
    void communityNodeUsesFullOpacityBackground() throws Exception {
        // Per user spec: "direkten Hintergrundfarben aus der Palette" — i.e.
        // the node background is the saturated palette colour, not a faint
        // tinted version.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityNodeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("'background-opacity': 1.0"),
                "community node background-opacity must be 1.0 (full opacity)");
        assertFalse(fn.contains("'background-opacity': 0.18"),
                "community node must NOT use 0.18 (the old faint-fill)");
    }

    @Test
    void communityNodeHasZeroPaddingForArrowArrival() throws Exception {
        // Per user spec: "Pfeile sollen immer von innerhalb des Kreises
        // an der Node ankommen". With padding=0 the arrow lands directly
        // on the circle's perimeter.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityNodeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("'padding': '0px'"),
                "community node padding must be 0px so arrows arrive at the circle edge");
        assertFalse(fn.contains("'padding': '24px'"),
                "community node must NOT use 24px padding");
    }

    @Test
    void communityNodeSizeCapIsReduced() throws Exception {
        // The size cap was reduced from 220 -> 140 to keep the circle
        // layout free of overlaps. The cap now lives in
        // computeCommunityNodeSize (the per-element size computer)
        // because communityNodeStyle reads size from data(...) string
        // mappers and no longer from a function mapper. Pin the cap
        // on the new owner so a future refactor can't regress it.
        String src = readViewerJs();
        assertTrue(src.contains("function computeCommunityNodeSize"),
                "computeCommunityNodeSize must be defined");
        Pattern body = Pattern.compile(
                "function computeCommunityNodeSize\\(communityNode\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "computeCommunityNodeSize must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("Math.min(140"),
                "computeCommunityNodeSize must cap the dynamic-size path at 140 px (not 220)");
        assertFalse(fn.contains("Math.min(220"),
                "computeCommunityNodeSize must NOT use the old 220 cap");
    }

    @Test
    void communityNodeHonoursDynamicSizeFlag() throws Exception {
        // Per user spec: 'Dynamic Clusternode Size' toggle (default off)
        // controls whether community-node sizes scale logarithmically
        // with incomingWeightSum or stay compact. The size is computed
        // by computeCommunityNodeSize and written to data._width /
        // data._height — the stylesheet reads these via the
        // string-mappers 'data(_width)' / 'data(_height)' which
        // survive fromJson round-trips.
        String src = readViewerJs();
        assertTrue(src.contains("var communityDynamicSize = false"),
                "communityDynamicSize must default to false (compact uniform-size)");
        assertTrue(src.contains("function computeCommunityNodeSize"),
                "computeCommunityNodeSize() must be defined so the per-element "
                        + "_width / _height fields can be stamped before the stylesheet rebuild");
    }

    @Test
    void communityNodeCompactSizeIsLabelDriven() throws Exception {
        // Per user spec: in dynamic-off mode the community-nodes must be
        // only as big as needed to fit the "C<N>" label so they don't
        // hog screen real estate. computeCommunityNodeSize reads the
        // label and writes a width = label.length * CHAR_PX + padding
        // (with a small floor), and a height = FLOOR_PX.
        String src = readViewerJs();
        assertTrue(src.contains("function computeCommunityNodeSize"),
                "computeCommunityNodeSize() must be defined");
        // Find the function body and check that the label-driven branch
        // is present.
        Pattern body = Pattern.compile(
                "function computeCommunityNodeSize\\(communityNode\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("label.length"),
                "computeCommunityNodeSize must read label.length to drive width");
        assertTrue(fn.contains("communityNode.data('label')"),
                "computeCommunityNodeSize must read data('label') to derive width");
        assertTrue(fn.contains("'_width'") && fn.contains("'_height'"),
                "computeCommunityNodeSize must stamp _width / _height on the element");
    }

    @Test
    void communityNodeStyleUsesDataMappersWithFunctionFallback() throws Exception {
        // The community-node stylesheet reads width/height primarily
        // from the data(_width) / data(_height) string-mappers (those
        // survive fromJson round-trips). Phase 2 hardening wraps them
        // in a small function mapper that falls back to a
        // mode-appropriate default (24x14 compact, 70x70 dynamic)
        // whenever data._width / data._height is missing or non-
        // numeric — without this fallback the renderer was resolving
        // undefined to a large generic default (~140 px) which made
        // the cluster nodes dwarf their labels.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityNodeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        // The primary path still consults data._width / data._height.
        assertTrue(fn.contains("ele.data('_width')"),
                "communityNodeStyle must read width from data._width (function fallback)");
        assertTrue(fn.contains("ele.data('_height')"),
                "communityNodeStyle must read height from data._height (function fallback)");
        // The defensive compact-mode fallback value must be present so
        // the symptom (~140 px circles when dynamic is off) can't come
        // back if data._width is ever missing.
        assertTrue(fn.contains("fallbackW"),
                "communityNodeStyle must define a compact fallback width constant");
        assertTrue(fn.contains("fallbackH"),
                "communityNodeStyle must define a compact fallback height constant");
        assertTrue(fn.contains("'14'") || fn.contains("14,") || fn.matches("(?s).*\\bfallbackH\\s*=\\s*14\\b.*"),
                "compact fallback height must be 14 px to match the FLOOR_PX used in computeCommunityNodeSize");
        // Width/height entries must still be mappers (function,
        // variable holding a function, or 'data(...)') — not raw
        // numeric literals — otherwise we'd lose per-element sizing
        // entirely. Accept the named-var form (widthMapper /
        // heightMapper) used by the Phase 2 hardening as well as the
        // bare 'function' / 'data(...)' forms.
        assertTrue(fn.matches("(?s).*'width'\\s*:\\s*(function|widthMapper|'data\\(_width\\)').*"),
                "communityNodeStyle width must be a mapper (function, widthMapper, or data(_width))");
        assertTrue(fn.matches("(?s).*'height'\\s*:\\s*(function|heightMapper|'data\\(_height\\)').*"),
                "communityNodeStyle height must be a mapper (function, heightMapper, or data(_height))");
    }

    @Test
    void applyCommunityViewComputesNodeSizesBeforeStyleRebuild() throws Exception {
        // The order in applyCommunityView matters: cy.add() first, then
        // recomputeAllCommunityNodeSizes() so each community-node gets
        // its _width / _height data fields, THEN applyStyleForCommunityView()
        // so the stylesheet rebuild reads those fields. Reversing this
        // order would silently drop the per-element sizes.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function applyCommunityView\\(mode, elements\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "applyCommunityView must be defined");
        String fn = m.group(1);
        int addAt = fn.indexOf("cy.add(elements || [])");
        int sizeAt = fn.indexOf("recomputeAllCommunityNodeSizes()");
        int styleAt = fn.indexOf("applyStyleForCommunityView(mode)");
        assertTrue(addAt > 0 && sizeAt > 0 && styleAt > 0,
                "applyCommunityView must call cy.add, recomputeAllCommunityNodeSizes, and applyStyleForCommunityView");
        assertTrue(addAt < sizeAt,
                "cy.add must run BEFORE recomputeAllCommunityNodeSizes so each element has its data set");
        assertTrue(sizeAt < styleAt,
                "recomputeAllCommunityNodeSizes must run BEFORE applyStyleForCommunityView so the stylesheet rebuild reads the new data fields");
    }

    @Test
    void communityCircleUsesTighterMaxNodeSizeWhenDynamicIsOff() throws Exception {
        // Per user spec: in dynamic-off mode the overall ring is much
        // smaller than in dynamic mode. The preseed radius derives
        // from a smaller maxNodeSize — pinning the (140 : 24) ternary
        // is enough to catch a future drift.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function preseedCommunityCirclePositions\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "preseedCommunityCirclePositions must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("communityDynamicSize"),
                "preseedCommunityCirclePositions must consult communityDynamicSize");
        // The conditional must use a smaller value when dynamic is off.
        assertTrue(fn.matches("(?s).*maxNodeSize\\s*=\\s*communityDynamicSize\\s*\\?\\s*140\\s*:\\s*\\d+.*"),
                "preseedCommunityCirclePositions must use a smaller maxNodeSize when "
                        + "communityDynamicSize is off so the ring is much smaller than in dynamic mode");
    }

    @Test
    void cgvApplyCommunityViewAcceptsDynamicSizeFlag() throws Exception {
        // cgv_applyCommunityView is the JS entry point called by the
        // Java bridge. It must accept and forward the dynamicSize arg.
        String src = readViewerJs();
        assertTrue(src.contains("window.cgv_applyCommunityView = function (mode, elements, dynamicSize)")
                        || src.contains("window.cgv_applyCommunityView = function(mode, elements, dynamicSize)"),
                "cgv_applyCommunityView must accept a third 'dynamicSize' argument");
        assertTrue(src.contains("communityDynamicSize = (dynamicSize === true)"),
                "cgv_applyCommunityView must store the dynamicSize flag in communityDynamicSize");
    }

    @Test
    void cgvClearCommunityViewResetsDynamicSizeFlag() throws Exception {
        // Leaving the community view resets the flag so a subsequent
        // re-entry without an explicit flag falls back to the default.
        String src = readViewerJs();
        assertTrue(src.contains("window.cgv_clearCommunityView = function"),
                "cgv_clearCommunityView must be defined");
        // The reset must be inside the function body, not just defined.
        // We assert via the var-name + assignment co-occurrence.
        assertTrue(src.matches("(?s).*cgv_clearCommunityView[\\s\\S]{0,800}communityDynamicSize\\s*=\\s*false[\\s\\S]*?\\n    \\};.*"),
                "cgv_clearCommunityView body must reset communityDynamicSize to false");
    }

    @Test
    void communityEdgeWidthIsFixedAtTwoPixels() throws Exception {
        // Per user spec: the dynamic edge-width scaling (0.3 + 0.75 *
        // log(w+1), cap 6) was dropped because the on-canvas label and
        // the tooltip already convey the weight — a flat 2 px line keeps
        // the parallel bezier cables (A->B + B->A) visually distinct
        // without overwhelming the canvas.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityEdgeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.matches("(?s).*'width'\\s*:\\s*2\\s*,\\s*.*"),
                "community edge width must be the fixed numeric value 2");
        // The width must NOT be a function mapper — width is a flat
        // 2 px, no per-element computation. (The previous dynamic
        // formula is documented in a JSDoc comment that still mentions
        // 0.75 for historical context, so we do NOT assert "no 0.75 in
        // the body" — only "no function mapper on width".)
        assertFalse(fn.matches("(?s).*'width'\\s*:\\s*function.*"),
                "community edge width must NOT be a function mapper (dynamic sizing is gone)");
        assertFalse(fn.contains("Math.min(6"),
                "community edge must NOT use the old 6 px cap");
        assertFalse(fn.contains("Math.min(12"),
                "community edge must NOT use the old 12 px cap");
    }

    @Test
    void communityEdgeLabelIsAggregatedWeight() throws Exception {
        // The on-canvas cytoscape label for a community-edge shows the
        // summed weight for that direction, formatted identically to
        // the tooltip's ": <weight>" suffix. The cytoscape stylesheet
        // must read it via the string-mapper 'data(label)' so the value
        // survives fromJson round-trips — the value itself is stamped
        // server-side in CommunityAggregator.buildRootElements via
        // formatWeight().
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityEdgeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("'label': 'data(label)'"),
                "community edge label must read from data(label) so server-side "
                        + "formatWeight(totalWeight) survives the fromJson round-trip");
        assertFalse(fn.matches("(?s).*'label'\\s*:\\s*function.*"),
                "community edge must NOT use a function-mapper for the label — "
                        + "they are silently dropped by cytoscape.js's fromJson");
        assertFalse(fn.contains("+ ' Edges'"),
                "community edge must NOT use the old 'N Edges' label format");
        assertFalse(fn.contains("+ 'x'") && fn.contains("edgeCount"),
                "community edge must NOT use the old 'Nx' / edgeCount label format");
    }

    @Test
    void circleLayoutEnforcesNoOverlapRadius() throws Exception {
        // Per user spec: "Nodes sollen nicht so groß werden dass sie sich
        // überlappen". The radius must scale with k so that 2π·r/k >=
        // maxNodeSize.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function preseedCommunityCirclePositions\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("maxNodeSize"),
                "circle layout must reference a maxNodeSize constant");
        assertTrue(fn.contains("2 * Math.PI"),
                "circle layout must compute the no-overlap radius via 2π·r/k ≥ maxNodeSize");
        assertTrue(fn.contains("noOverlapRadius"),
                "circle layout must compute a noOverlapRadius and use it as a floor");
    }

    /* -------------------------------------------------------------- */
    /*  Community selection + edges-of-selected-community table        */
    /* -------------------------------------------------------------- */

    @Test
    void communityEdgesTableDefined() throws Exception {
        // The "#cgv-community-edges" panel must be populated by a
        // function that creates a From/Weight/To table.
        String src = readViewerJs();
        assertTrue(src.contains("function renderCommunityEdgesTable"),
                "renderCommunityEdgesTable() must be defined");
        assertTrue(src.contains("function hideCommunityEdgesTable"),
                "hideCommunityEdgesTable() must be defined");
        assertTrue(src.contains("'cgv-community-edges'")
                        || src.contains("\"cgv-community-edges\""),
                "source must reference the cgv-community-edges panel id");
    }

    @Test
    void communityEdgeRowClickFiresJavaListener() throws Exception {
        // Per user spec: row-click in the table must fire the
        // relationship listener event.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function onCommunityEdgeRowClick\\(edgeId, evt\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "onCommunityEdgeRowClick must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("cgv_notifyRelationshipSelected"),
                "row-click must call cgv_notifyRelationshipSelected(edgeId)");
        assertTrue(fn.contains("edgeId"),
                "row-click must pass the original edge id to the listener");
    }

    @Test
    void communityViewSwitchesTapHandlerSet() throws Exception {
        // Entering the community view must install the community
        // selection handlers; leaving must restore the generic ones.
        // We assert the presence of the helper that does the swap and
        // that the generic cgv_applyCommunityView path calls it.
        String src = readViewerJs();
        assertTrue(src.contains("function setCommunitySelectionEnabled"),
                "setCommunitySelectionEnabled helper must be defined");
        assertTrue(src.contains("setCommunitySelectionEnabled(true)")
                        || src.contains("setCommunitySelectionEnabled(true );")
                        || src.contains("setCommunitySelectionEnabled(true)\""),
                "applyCommunityView must activate community selection handlers");
        assertTrue(src.contains("setCommunitySelectionEnabled(false)")
                        || src.contains("setCommunitySelectionEnabled(false );")
                        || src.contains("setCommunitySelectionEnabled(false)\""),
                "cgv_clearCommunityView must restore the generic selection handlers");
        // The implementation uses cy.removeAllListeners('tap') to swap
        // the handler sets without leaving stale listeners behind.
        assertTrue(src.contains("removeAllListeners('tap')"),
                "setCommunitySelectionEnabled must clear stale tap listeners before swap");
    }

    @Test
    void communityTableHasOneRowPerAggregatedEdge() throws Exception {
        // Per user choice: the table shows ONE row per Cluster-pair
        // (i.e. per aggregated inter-community edge) with the SUM of
        // the original GraphRelationship weights in the Weight column.
        // The renderer iterates node.connectedEdges() — NOT
        // data('memberEdgeIds') which would re-expand each aggregated
        // edge into multiple rows.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function renderCommunityEdgesTable\\(node\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "renderCommunityEdgesTable must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("connectedEdges"),
                "renderCommunityEdgesTable must iterate node.connectedEdges()");
        // The Weight cell must use the Java-format mirror helper so
        // the table column matches the on-canvas data.label and the
        // tooltip suffix exactly.
        assertTrue(fn.contains("formatAggregatedWeight"),
                "renderCommunityEdgesTable must format the weight column via "
                        + "formatAggregatedWeight (mirror of Java's formatWeight)");
        // Regression guard: the previous behaviour iterated
        // memberEdgeIds and emitted one row per ORIGINAL relationship.
        // The new contract is the opposite — one row per aggregated
        // edge — so the inner forEach over memberEdgeIds is gone.
        assertFalse(fn.matches("(?s).*\\.memberEdgeIds\\.forEach\\s*\\(.*"),
                "renderCommunityEdgesTable must NOT iterate .memberEdgeIds — "
                        + "one row per aggregated edge, not per original relationship");
    }

    /* -------------------------------------------------------------- */
    /*  Regressions: tooltips + dblclick drill stopped working        */
    /* -------------------------------------------------------------- */

    @Test
    void dblclickDrillIsInstalledPerCommunityViewActivation() throws Exception {
        // Regression: dblclick on a community-node was lost because the
        // initial implementation only registered it once at boot. When
        // the community-view was re-activated, the dblclick listener
        // was still attached, but the issue was the brittle tap-handler
        // swap. Now the dblclick is registered inside
        // wireCommunitySelectionEvents so it travels with the handler
        // set.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function wireCommunitySelectionEvents\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "wireCommunitySelectionEvents must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("'dblclick'") && fn.contains("'node[?isCommunity]'"),
                "dblclick handler for community nodes must be inside wireCommunitySelectionEvents");
        assertTrue(fn.contains("cgv_notifyCommunityDrillDown"),
                "dblclick must fire cgv_notifyCommunityDrillDown to the Java bridge");
    }

    @Test
    void communityViewClearsDblclickOnSwap() throws Exception {
        // When the user leaves the community view we must strip the
        // dblclick listener too (otherwise a stray dblclick on a
        // member-node would still try to drill into a community).
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function setCommunitySelectionEnabled\\(enabled\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "setCommunitySelectionEnabled must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("removeAllListeners('tap')"),
                "setCommunitySelectionEnabled must clear tap listeners");
        assertTrue(fn.contains("removeAllListeners('dblclick')"),
                "setCommunitySelectionEnabled must clear dblclick listeners too "
                        + "(so a stale community-dblclick can't fire after back-out)");
    }

    @Test
    void communityViewReattachesTooltipSystem() throws Exception {
        // Regression: edge tooltips stopped working after
        // removeAllListeners('tap') because attachTooltips is also
        // wired on 'tap'. The fix: re-invoke attachTooltips from inside
        // wireCommunitySelectionEvents so the tooltip handlers survive
        // the swap.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function wireCommunitySelectionEvents\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("attachTooltips(cy)"),
                "wireCommunitySelectionEvents must re-attach attachTooltips so the "
                        + "floating #cgv-tooltip survives the removeAllListeners('tap') swap");
    }

    @Test
    void communityNodeTooltipListsMemberNodes() throws Exception {
        // Per user spec: "Füge einen Tooltip auf den Cluster Nodes hinzu
        // mit einer Liste aller Nodes des Clusters".
        String src = readViewerJs();
        assertTrue(src.contains("function buildCommunityNodeTooltip"),
                "buildCommunityNodeTooltip() must be defined");
        Pattern body = Pattern.compile(
                "function buildCommunityNodeTooltip\\(communityNode\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("memberIds"),
                "buildCommunityNodeTooltip must read data('memberIds')");
        assertTrue(fn.contains("cgv-tt-member"),
                "buildCommunityNodeTooltip must render each member as a .cgv-tt-member div");
        assertTrue(fn.contains("Mitglieder"),
                "buildCommunityNodeTooltip must show a 'Mitglieder' section title");
    }

    @Test
    void buildTooltipHtmlRoutesCommunityNodesToMemberList() throws Exception {
        // The buildTooltipHtml dispatcher must hand aggregated community
        // nodes (data.isCommunity === true, no data.tooltip) to the
        // member-list builder so they get a tooltip.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function buildTooltipHtml\\(ele\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("isCommunity") && fn.contains("buildCommunityNodeTooltip"),
                "buildTooltipHtml must call buildCommunityNodeTooltip for community nodes");
    }
}
