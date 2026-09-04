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
        // applyStyleForCommunityView() must call cy.style().fromJson(...)
        // so the node[?isCommunity] / edge[?isCommunityEdge] rules
        // actually reach the cytoscape renderer. Without this the
        // community-nodes fall back to the generic 'node' rule (blue
        // circle) and the community-edges fall back to grey.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function applyStyleForCommunityView\\(mode\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "applyStyleForCommunityView() must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("cy.style()"),
                "applyStyleForCommunityView must rebuild the stylesheet via cy.style()");
        assertTrue(fn.contains("fromJson"),
                "applyStyleForCommunityView must call fromJson to replace the stylesheet");
        assertTrue(fn.contains("communityStyleRules()"),
                "applyStyleForCommunityView must splice the community rules in");
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
        // layout free of overlaps.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityNodeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("Math.min(140"),
                "community node width/height must cap at 140 px (not 220)");
        assertFalse(fn.contains("Math.min(220"),
                "community node must NOT use the old 220 cap");
    }

    @Test
    void communityNodeHonoursDynamicSizeFlag() throws Exception {
        // Per user spec: 'Dynamic Clusternode Size' toggle (default off)
        // in the dialog controls whether community-node sizes scale
        // logarithmically with incomingWeightSum or stay at a fixed size.
        String src = readViewerJs();
        assertTrue(src.contains("var communityDynamicSize = false"),
                "communityDynamicSize must default to false (uniform fixed-size)");
        Pattern body = Pattern.compile(
                "function communityNodeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("communityDynamicSize"),
                "communityNodeStyle must consult the communityDynamicSize flag");
        // The width/height mapper must short-circuit to the fixed size
        // when the flag is off.
        assertTrue(fn.contains("FIXED_SIZE"),
                "communityNodeStyle must introduce a FIXED_SIZE constant for the dynamic-off path");
    }

    @Test
    void communityCircleIsHalfSizeWhenDynamicIsOff() throws Exception {
        // Per user spec: in dynamic-off mode the circle should be ~50%
        // smaller. The preseed radius must therefore derive from a
        // maxNodeSize that is 70 px (= 110 / 2 rounded down) rather than
        // the dynamic-mode 140 px.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function preseedCommunityCirclePositions\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "preseedCommunityCirclePositions must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("communityDynamicSize"),
                "preseedCommunityCirclePositions must consult communityDynamicSize");
        // The conditional must use 70 px when dynamic is off and 140 px
        // when dynamic is on — a regex against the conditional expression.
        assertTrue(fn.matches("(?s).*maxNodeSize\\s*=\\s*communityDynamicSize\\s*\\?\\s*140\\s*:\\s*70.*"),
                "preseedCommunityCirclePositions must halve maxNodeSize (140 -> 70) when "
                        + "communityDynamicSize is off so the ring is ~50% smaller");
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
    void communityEdgeWidthIsHalved() throws Exception {
        // Per user spec: "breite der Edges ... um die Hälfte reduziert".
        // New formula: 0.3 + 0.75 * log(w+1), cap 6.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function communityEdgeStyle\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find());
        String fn = m.group(1);
        assertTrue(fn.contains("0.75"),
                "community edge width must use the halved 0.75 coefficient");
        assertTrue(fn.contains("Math.min(6"),
                "community edge width must cap at 6 px (halved from 12)");
        assertFalse(fn.contains("Math.min(12"),
                "community edge must NOT use the old 12 cap");
        assertFalse(fn.contains("+ 1.5 * Math.log"),
                "community edge must NOT use the old 1.5 coefficient");
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
    void communityTableHasOneRowPerOriginalEdge() throws Exception {
        // Per user choice: a row per memberEdgeIds entry, not one row
        // per aggregated edge. The renderer must iterate memberEdgeIds.
        String src = readViewerJs();
        Pattern body = Pattern.compile(
                "function renderCommunityEdgesTable\\(node\\)\\s*\\{([\\s\\S]*?)\\n    \\}");
        Matcher m = body.matcher(src);
        assertTrue(m.find(), "renderCommunityEdgesTable must be defined");
        String fn = m.group(1);
        assertTrue(fn.contains("memberEdgeIds"),
                "renderCommunityEdgesTable must read data('memberEdgeIds')");
        assertTrue(fn.contains("forEach") && fn.contains("memberEdgeIds"),
                "renderCommunityEdgesTable must iterate the memberEdgeIds list");
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
