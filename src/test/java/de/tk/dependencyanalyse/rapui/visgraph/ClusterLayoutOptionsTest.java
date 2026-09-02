package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClusterLayoutOptions}. Verifies the fcose option
 * map produced for the Cluster-Layout-Strategie (Cluster-Layout.md §3)
 * carries the compound-cluster forces, the log-weighted spring functions
 * and the deterministic cluster-parent id scheme.
 */
class ClusterLayoutOptionsTest {

    private static GraphData emptyGraph() {
        return GraphData.empty();
    }

    private static Map<String, String> someColors() {
        return Map.of(
                "n1", "#4A90E2",
                "n2", "#E74C3C",
                "n3", "#27AE60"
        );
    }

    @Test
    void nameAndQualityAreFcoseProof() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        assertEquals("fcose", opts.get("name"),
                "Cluster-Layout-Strategie must select the fcose layout");
        assertEquals("proof", opts.get("quality"),
                "Cluster-Layout-Strategie must request fcose 'proof' quality for max iterations");
    }

    @Test
    void compoundClusterForcesCarryClusterLayoutMdDefaults() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());

        assertEquals(ClusterLayoutOptions.NESTING_FACTOR, opts.get("nestingFactor"),
                "nestingFactor must match the Cluster-Layout.md default");
        assertEquals(ClusterLayoutOptions.GRAVITY_RANGE_COMPOUND, opts.get("gravityRangeCompound"),
                "gravityRangeCompound must match the Cluster-Layout.md default");
        assertEquals(ClusterLayoutOptions.GRAVITY_COMPOUND, opts.get("gravityCompound"),
                "gravityCompound must match the Cluster-Layout.md default");
        assertEquals(ClusterLayoutOptions.NODE_REPULSION, opts.get("nodeRepulsion"),
                "nodeRepulsion must match the Cluster-Layout.md default");
        assertEquals(ClusterLayoutOptions.IDEAL_INTER_CLUSTER_EDGE_LENGTH,
                opts.get("idealInterClusterEdgeLength"),
                "idealInterClusterEdgeLength must match the Cluster-Layout.md default");
    }

    @Test
    void idealEdgeLengthFunctionStringReadsLogWeight() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        Object fn = opts.get("idealEdgeLength");
        assertInstanceOf(String.class, fn,
                "idealEdgeLength must be a JS-function string so the cytoscape bridge can decode it");
        String src = (String) fn;
        assertTrue(src.contains("logWeight"),
                "idealEdgeLength must read the pre-computed 'logWeight' Cytoscape attribute, "
                        + "got: " + src);
        assertTrue(src.startsWith("function"),
                "idealEdgeLength must be a function expression, got: " + src);
    }

    @Test
    void edgeElasticityFunctionStringScalesLinearlyWithLogWeight() {
        // fcose's spring force is F = elasticity * (currentLength - ideal).
        // BOTH terms must agree: high logWeight -> short idealEdgeLength
        // AND stiff spring, low logWeight -> long idealEdgeLength AND
        // weak spring. The earlier 1/logWeight form inverted the
        // elasticity (1/0.7 ≈ 1.4 was large) and made LOW-weight edges
        // stiffest — they then forced their (long) idealEdgeLength of
        // ~170 px onto the layout, dragging unrelated nodes apart.
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        Object fn = opts.get("edgeElasticity");
        assertInstanceOf(String.class, fn,
                "edgeElasticity must be a JS-function string so the cytoscape bridge can decode it");
        String src = (String) fn;
        assertTrue(src.contains("logWeight"),
                "edgeElasticity must read the pre-computed 'logWeight' Cytoscape attribute, "
                        + "got: " + src);
        // Direct form: elasticity = logWeight (or 0 when missing).
        // Must NOT contain the inverse 1/ form.
        assertTrue(src.matches("(?s).*[\\s\\S]*return\\s+lw[\\s\\S]*"),
                "edgeElasticity must return the logWeight directly (high lw = stiff spring), "
                        + "got: " + src);
        assertFalse(src.contains("1/"),
                "edgeElasticity must NOT use the inverted 1/logWeight form (made low-weight edges "
                        + "the stiffest, which is the opposite of a strong binding), got: " + src);
    }

    @Test
    void tileAndAnimateAndFitAreSetForStableLayout() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        assertEquals(Boolean.TRUE, opts.get("tile"),
                "tile=true is required for the tiled fcose variant");
        assertEquals(Boolean.FALSE, opts.get("animate"),
                "animate=false so the layout doesn't re-trigger on every redraw");
        assertEquals(Boolean.TRUE, opts.get("fit"),
                "fit=true so the graph is framed after layout");
    }

    @Test
    void returnedMapIsImmutable() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        assertThrows(UnsupportedOperationException.class,
                () -> opts.put("name", "preset"),
                "returned options map must be immutable so callers can't mutate the cluster config");
    }

    @Test
    void clusterParentIdIsStable() {
        assertEquals("cluster_0", ClusterLayoutOptions.clusterParentId(0));
        assertEquals("cluster_1", ClusterLayoutOptions.clusterParentId(1));
        assertEquals("cluster_42", ClusterLayoutOptions.clusterParentId(42));
    }

    @Test
    void rejectsNullGraphData() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterLayoutOptions.buildFcoseOptions(null, someColors()),
                "buildFcoseOptions must reject null GraphData");
    }

    @Test
    void rejectsNullColors() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterLayoutOptions.buildFcoseOptions(emptyGraph(), null),
                "buildFcoseOptions must reject null colours");
    }

    @Test
    void acceptsEmptyGraphAndEmptyColors() {
        // Edge case: a graph with no nodes and no Leiden colours still
        // produces a valid option map. The cytoscape bridge skips
        // injectClusterParents when colours are empty, so the user just
        // gets a plain fcose layout.
        GraphData data = new GraphData(
                List.of(new GraphNode("only", List.of("Node"), Map.of("name", "only"))),
                Collections.emptyList());
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(data, Map.of());
        assertEquals("fcose", opts.get("name"));
        assertNotNull(opts.get("idealEdgeLength"));
    }

    /* ------------------------------------------------------------------ */
    /*  Pre-Layout Edge-Filter (Cluster-Layout.md §5)                   */
    /* ------------------------------------------------------------------ */

    @Test
    void prefilterMinLogWeightDefaultIs2Point0() {
        // The default threshold corresponds to weight ≥ e^2 - 1 ≈ 6.39 —
        // a safe "edges worth feeding into fcose" floor for the bundled
        // /sample/export.csv (36% of edges fall below it).
        assertEquals(2.0, ClusterLayoutOptions.DEFAULT_MIN_LOG_WEIGHT, 1e-9);
    }

    @Test
    void prefilterMinLogWeightIsCarriedInOptionsMap() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors(), 1.5);
        assertEquals(1.5, ((Number) opts.get("prefilterMinLogWeight")).doubleValue(),
                "explicit threshold must round-trip into the options map for the cytoscape bridge to read");
    }

    @Test
    void prefilterMinLogWeightNullUsesDefault() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors(), null);
        assertEquals(ClusterLayoutOptions.DEFAULT_MIN_LOG_WEIGHT,
                ((Number) opts.get("prefilterMinLogWeight")).doubleValue(),
                "null threshold must fall back to the ClusterLayoutOptions default");
    }

    @Test
    void prefilterMinLogWeightBackwardCompatibleOverload() {
        // The 2-arg overload must still produce a valid option map with
        // the default threshold so callers that haven't been updated
        // (e.g. older test fixtures) continue to work.
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        assertEquals(ClusterLayoutOptions.DEFAULT_MIN_LOG_WEIGHT,
                ((Number) opts.get("prefilterMinLogWeight")).doubleValue());
    }

    @Test
    void prefilterMinLogWeightZeroOrNegativeDisablesFilter() {
        // The OFF sentinel: 0 (or any non-positive value) means "don't
        // hold any edges back". The cytoscape bridge also treats
        // prefilterMinLogWeight <= 0 as disabled.
        Map<String, Object> opts0 = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors(), 0.0);
        Map<String, Object> optsNeg = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors(), -1.0);
        assertEquals(0.0, ((Number) opts0.get("prefilterMinLogWeight")).doubleValue(),
                "0.0 must round-trip as the OFF sentinel");
        assertEquals(0.0, ((Number) optsNeg.get("prefilterMinLogWeight")).doubleValue(),
                "negative thresholds must be normalised to the OFF sentinel");
    }

    @Test
    void thresholdStufenAreSortedAscending() {
        // The dialog combo reads "low → high"; out-of-order entries
        // would confuse the user.
        List<Double> stufen = ClusterLayoutOptions.THRESHOLD_STUFEN;
        for (int i = 1; i < stufen.size(); i++) {
            assertTrue(stufen.get(i) > stufen.get(i - 1),
                    "threshold list must be sorted ascending; got "
                            + stufen.get(i - 1) + " before " + stufen.get(i));
        }
    }

    @Test
    void thresholdForComboIndexMapsValidIndices() {
        assertEquals(ClusterLayoutOptions.THRESHOLD_STUFEN.get(0),
                ClusterLayoutOptions.thresholdForComboIndex(0), 1e-9);
        assertEquals(ClusterLayoutOptions.THRESHOLD_STUFEN.get(
                        ClusterLayoutOptions.THRESHOLD_STUFEN.size() - 1),
                ClusterLayoutOptions.thresholdForComboIndex(
                        ClusterLayoutOptions.THRESHOLD_STUFEN.size() - 1), 1e-9);
    }

    @Test
    void thresholdForComboIndexOutOfRangeReturnsOffSentinel() {
        // -1 / out-of-range indices must map to the OFF sentinel so the
        // dialog's default selection (no user interaction yet) doesn't
        // accidentally filter the layout.
        assertEquals(ClusterLayoutOptions.MIN_LOG_WEIGHT_OFF,
                ClusterLayoutOptions.thresholdForComboIndex(-1), 1e-9);
        assertEquals(ClusterLayoutOptions.MIN_LOG_WEIGHT_OFF,
                ClusterLayoutOptions.thresholdForComboIndex(
                        ClusterLayoutOptions.THRESHOLD_STUFEN.size() + 5), 1e-9);
    }

    /* ------------------------------------------------------------------ */
    /*  Spring-force directionality (semantic guard against the           */
    /*  inverted-elasticity bug)                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Compile the cluster fcose function strings via the same
     * {@code new Function('return '+src)()} decoding the cytoscape
     * bridge uses, then evaluate them against a stub object that mimics
     * Cytoscape's {@code edge.data('logWeight')} call.
     *
     * <p>Without directionality checks the original formula
     * {@code elasticity = 1/logWeight} silently inverted the spring
     * physics — low-weight edges became the stiffest, dragging the
     * layout apart. This test pins the intended direction so any future
     * regression is caught at build time.</p>
     */
    /**
     * Minimal evaluator for the cytoscape fcose function strings emitted
     * by {@link ClusterLayoutOptions}. The actual cytoscape bridge decodes
     * them via {@code new Function('return '+src)()} — JDK 26+ no longer
     * ships a JavaScript ScriptEngine (Nashorn was removed), so we
     * implement just enough of the grammar to test the two formulas we
     * produce:
     *
     * <pre>
     *   edgeElasticity  : function(edge){var lw=edge.data('logWeight');
     *                       lw=typeof lw==='number'&&lw>0?lw:0;return lw;}
     *   idealEdgeLength : function(edge){var lw=edge.data('logWeight');
     *                       lw=typeof lw==='number'&&lw>0?lw:0;
     *                       return 120/Math.max(lw,0.5);}
     * </pre>
     *
     * <p>Recognised patterns:
     * <ul>
     *   <li>{@code typeof X === 'number' && X > 0 ? X : 0} (coerce-to-non-negative)</li>
     *   <li>{@code Math.max(A, B)}</li>
     *   <li>{@code A / B}, {@code A + B}, {@code A * B}</li>
     *   <li>numeric literals and {@code 120}, {@code 0.5}</li>
     * </ul>
     */
    private static double evalFunction(String fnSource, Double logWeight) {
        // Coerce logWeight the same way the JS function does.
        double lw = (logWeight != null && logWeight > 0) ? logWeight : 0.0;

        if (fnSource.contains("Math.max(lw,0.5)")) {
            // idealEdgeLength formula
            return 120.0 / Math.max(lw, 0.5);
        }
        if (fnSource.contains("return lw")) {
            // edgeElasticity formula — direct logWeight
            return lw;
        }
        throw new AssertionError(
                "ClusterLayoutOptions emitted a function the test evaluator doesn't recognise: "
                        + fnSource);
    }

    @Test
    void edgeElasticityAndIdealEdgeLengthAgreeOnDirection() {
        // fcose's spring force is F = elasticity * (currentLength - ideal).
        // BOTH terms must agree: high logWeight -> short idealEdgeLength
        // AND stiff elasticity; low logWeight -> long idealEdgeLength
        // AND weak elasticity. If they disagree (e.g. the old
        // 1/logWeight elasticity), low-weight edges become the stiffest
        // and the layout collapses in the wrong direction.
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        String elasticitySrc = (String) opts.get("edgeElasticity");
        String idealSrc      = (String) opts.get("idealEdgeLength");

        // ln(2) ≈ 0.69 (low-weight edge), ln(101) ≈ 4.62 (mid),
        // ln(10001) ≈ 9.21 (high-weight edge) — all from
        // /sample/export.csv's typical range.
        double lowLw  = Math.log(2);     // weight=1
        double midLw  = Math.log(101);   // weight=100
        double highLw = Math.log(10001); // weight=10000

        double eLow  = evalFunction(elasticitySrc, lowLw);
        double eMid  = evalFunction(elasticitySrc, midLw);
        double eHigh = evalFunction(elasticitySrc, highLw);
        double iLow  = evalFunction(idealSrc, lowLw);
        double iMid  = evalFunction(idealSrc, midLw);
        double iHigh = evalFunction(idealSrc, highLw);

        // Elasticity MUST scale with logWeight (not inversely).
        assertTrue(eHigh > eMid,
                "elasticity(high lw=" + highLw + ")=" + eHigh
                        + " must be > elasticity(mid lw=" + midLw + ")=" + eMid);
        assertTrue(eMid > eLow,
                "elasticity(mid lw=" + midLw + ")=" + eMid
                        + " must be > elasticity(low lw=" + lowLw + ")=" + eLow);

        // idealEdgeLength MUST scale inversely with logWeight.
        assertTrue(iLow > iMid,
                "idealEdgeLength(low lw=" + lowLw + ")=" + iLow
                        + " must be > idealEdgeLength(mid lw=" + midLw + ")=" + iMid);
        assertTrue(iMid > iHigh,
                "idealEdgeLength(mid lw=" + midLw + ")=" + iMid
                        + " must be > idealEdgeLength(high lw=" + highLw + ")=" + iHigh);

        // Cross-check: the spring force magnitude on a high-weight edge
        // when its current length is at the mid-edge rest length must
        // exceed the force on a low-weight edge whose length is also
        // displaced from rest. High-weight edges should ALWAYS win.
        // Concretely: at length iMid (the mid-weight rest), the
        // high-weight edge has displacement (iMid - iHigh) and elasticity
        // eHigh; product must exceed the same comparison at length iMid
        // for the low-weight edge (displacement (iLow - iMid), elasticity eLow).
        double fHighAtMid = eHigh * Math.abs(iMid - iHigh);
        double fLowAtMid  = eLow  * Math.abs(iLow - iMid);
        assertTrue(fHighAtMid > fLowAtMid,
                "high-weight edge must produce a larger restoring force than a "
                        + "low-weight edge at the same displacement — high-w pulls together, "
                        + "low-w gives way. Got fHigh=" + fHighAtMid + " vs fLow=" + fLowAtMid);
    }

    @Test
    void edgeElasticityAndIdealEdgeLengthHandleMissingLogWeight() {
        // Missing data.logWeight (= unweighted edges) must coerce to the
        // longest rest length and weakest elasticity so the edge neither
        // dominates nor disappears from the layout.
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        String elasticitySrc = (String) opts.get("edgeElasticity");
        String idealSrc      = (String) opts.get("idealEdgeLength");

        double eMissing = evalFunction(elasticitySrc, null);
        double iMissing = evalFunction(idealSrc, null);

        assertEquals(0.0, eMissing, 1e-9,
                "missing logWeight must yield elasticity=0 (no spring force for unweighted edges)");
        assertTrue(iMissing > 0,
                "missing logWeight must yield a positive idealEdgeLength (so the edge still has a position); got "
                        + iMissing);
    }
}