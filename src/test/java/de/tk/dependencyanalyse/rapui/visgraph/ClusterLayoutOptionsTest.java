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
    void edgeElasticityFunctionStringIsInvertedByLogWeight() {
        Map<String, Object> opts = ClusterLayoutOptions.buildFcoseOptions(
                emptyGraph(), someColors());
        Object fn = opts.get("edgeElasticity");
        assertInstanceOf(String.class, fn,
                "edgeElasticity must be a JS-function string so the cytoscape bridge can decode it");
        String src = (String) fn;
        assertTrue(src.contains("logWeight"),
                "edgeElasticity must read the pre-computed 'logWeight' Cytoscape attribute, "
                        + "got: " + src);
        // Inverted form: stronger weight ⇒ shorter spring ⇒ higher elasticity.
        // 1/(logWeight) is the canonical pattern from Cluster-Layout.md.
        assertTrue(src.contains("1/"),
                "edgeElasticity must use the inverted 1/logWeight form, got: " + src);
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
}