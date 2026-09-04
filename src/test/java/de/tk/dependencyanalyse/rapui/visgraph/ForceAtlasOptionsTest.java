package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ForceAtlasOptions} — the vis-network counterpart of
 * {@link ClusterLayoutOptions}. Verifies:
 * <ul>
 *   <li>per-edge length interpolation covers the full {LMIN_PX, LMAX_PX}
 *       span and is monotonic in {@code logWeight},</li>
 *   <li>graphs without a {@code weight} attribute fall back to a uniform
 *       length,</li>
 *   <li>uniform-weight graphs avoid division by zero,</li>
 *   <li>cluster-anchor computation picks the highest-weighted-degree
 *       node per Leiden community and lays anchors out on a grid,</li>
 *   <li>pre-layout filter threshold round-trips into the option map.</li>
 * </ul>
 */
class ForceAtlasOptionsTest {

    private static GraphNode node(String id) {
        return new GraphNode(id, List.of("Class"), Map.of("name", id));
    }

    private static GraphRelationship rel(String id, GraphNode a, GraphNode b, Double w) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (w != null) props.put(GraphRelationship.PROP_WEIGHT, w);
        return new GraphRelationship(id, "REL", a, b, props);
    }

    private static GraphData graph(List<GraphNode> nodes, List<GraphRelationship> rels) {
        return new GraphData(new ArrayList<>(nodes), new ArrayList<>(rels));
    }

    /* ------------------------------------------------------------------ */
    /*  Per-edge length interpolation                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void edgeLengthsSpanMinAndMaxAccordingToLogWeight() {
        // 5 nodes, 6 weighted edges spanning weights 1 → 10000.
        GraphNode n1 = node("n1"), n2 = node("n2"), n3 = node("n3"),
                  n4 = node("n4"), n5 = node("n5");
        GraphData data = graph(
                List.of(n1, n2, n3, n4, n5),
                List.of(
                        rel("e12", n1, n2, 1.0),     // lwMin
                        rel("e23", n2, n3, 10.0),
                        rel("e34", n3, n4, 100.0),
                        rel("e45", n4, n5, 1000.0),
                        rel("e15", n1, n5, 10000.0), // lwMax
                        rel("e13", n1, n3, 5.0)
                ));

        Map<String, Object> opts = ForceAtlasOptions.buildOptions(data, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Double> lengths = (Map<String, Double>) opts.get("edgeLengths");

        assertEquals(6, lengths.size(), "every relationship must have a length entry");
        // Strongest edge (weight=10000) → LMIN_PX, weakest (weight=1) → LMAX_PX.
        assertEquals(ForceAtlasOptions.LMIN_PX, lengths.get("e15"), 1e-6,
                "strongest edge must be at LMIN_PX");
        assertEquals(ForceAtlasOptions.LMAX_PX, lengths.get("e12"), 1e-6,
                "weakest edge must be at LMAX_PX");
        // Monotone: lw monotonically decreasing ⇒ length monotonically increasing.
        double lMax = lengths.get("e15");
        double lMin = lengths.get("e12");
        for (String id : Arrays.asList("e23", "e34", "e45", "e13")) {
            double l = lengths.get(id);
            assertTrue(l >= lMax && l <= lMin,
                    "edge " + id + " length=" + l
                            + " must be in [" + lMax + ", " + lMin + "]");
        }
    }

    @Test
    void edgeLengthsMonotonicallyDecreaseWithLogWeight() {
        GraphNode a = node("a"), b = node("b"), c = node("c");
        GraphData data = graph(List.of(a, b, c), List.of(
                rel("weak",   a, b, 1.0),
                rel("medium", b, c, 100.0),
                rel("strong", a, c, 10000.0)
        ));
        @SuppressWarnings("unchecked")
        Map<String, Double> lengths = (Map<String, Double>)
                ForceAtlasOptions.buildOptions(data, Map.of()).get("edgeLengths");
        assertTrue(lengths.get("strong") < lengths.get("medium"),
                "strong edge must be shorter than medium edge");
        assertTrue(lengths.get("medium") < lengths.get("weak"),
                "medium edge must be shorter than weak edge");
    }

    @Test
    void edgeLengthsUniformWhenAllWeightsAreEqual() {
        GraphNode a = node("a"), b = node("b"), c = node("c");
        GraphData data = graph(List.of(a, b, c), List.of(
                rel("e1", a, b, 50.0),
                rel("e2", b, c, 50.0),
                rel("e3", a, c, 50.0)
        ));
        @SuppressWarnings("unchecked")
        Map<String, Double> lengths = (Map<String, Double>)
                ForceAtlasOptions.buildOptions(data, Map.of()).get("edgeLengths");
        // lwMax == lwMin → all edges use the default length.
        for (double l : lengths.values()) {
            assertEquals(ForceAtlasOptions.SPRING_LENGTH_DEFAULT, l, 1e-6,
                    "uniform-weight graph must give every edge the default length");
        }
    }

    @Test
    void edgeLengthsFallbackWhenNoWeightAttribute() {
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of(
                rel("e1", a, b, null)
        ));
        @SuppressWarnings("unchecked")
        Map<String, Double> lengths = (Map<String, Double>)
                ForceAtlasOptions.buildOptions(data, Map.of()).get("edgeLengths");
        assertEquals(ForceAtlasOptions.LMAX_PX, lengths.get("e1"), 1e-6,
                "edges without a weight attribute must use LMAX_PX (weakest spring)");
    }

    @Test
    void edgeLengthsFallbackForZeroOrNegativeWeight() {
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of(
                rel("zero", a, b, 0.0),
                rel("neg",  a, b, -7.0)
        ));
        @SuppressWarnings("unchecked")
        Map<String, Double> lengths = (Map<String, Double>)
                ForceAtlasOptions.buildOptions(data, Map.of()).get("edgeLengths");
        assertEquals(ForceAtlasOptions.LMAX_PX, lengths.get("zero"), 1e-6);
        assertEquals(ForceAtlasOptions.LMAX_PX, lengths.get("neg"), 1e-6);
    }

    @Test
    void emptyGraphProducesEmptyEdgeLengths() {
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Double> lengths = (Map<String, Double>) opts.get("edgeLengths");
        assertTrue(lengths.isEmpty(), "no edges ⇒ empty edgeLengths map");
    }

    /* ------------------------------------------------------------------ */
    /*  Physics block — vis-network FA2 contract                          */
    /* ------------------------------------------------------------------ */

    @Test
    void physicsBlockSelectsForceAtlas2BasedWithStabilization() {
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> physics = (Map<String, Object>) opts.get("physics");
        assertEquals(Boolean.TRUE, physics.get("enabled"));
        assertEquals("forceAtlas2Based", physics.get("solver"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fa2 = (Map<String, Object>) physics.get("forceAtlas2Based");
        assertEquals(ForceAtlasOptions.GRAVITATIONAL_CONSTANT,
                ((Number) fa2.get("gravitationalConstant")).doubleValue(), 1e-9);
        assertEquals(ForceAtlasOptions.CENTRAL_GRAVITY,
                ((Number) fa2.get("centralGravity")).doubleValue(), 1e-9);
        assertEquals(ForceAtlasOptions.SPRING_CONSTANT,
                ((Number) fa2.get("springConstant")).doubleValue(), 1e-9);
        assertEquals(ForceAtlasOptions.SPRING_LENGTH_DEFAULT,
                ((Number) fa2.get("springLength")).doubleValue(), 1e-9);
        assertEquals(ForceAtlasOptions.DAMPING,
                ((Number) fa2.get("damping")).doubleValue(), 1e-9);
        assertEquals(ForceAtlasOptions.AVOID_OVERLAP,
                ((Number) fa2.get("avoidOverlap")).doubleValue(), 1e-9);

        @SuppressWarnings("unchecked")
        Map<String, Object> stab = (Map<String, Object>) physics.get("stabilization");
        assertEquals(Boolean.TRUE, stab.get("enabled"));
        assertEquals(Boolean.TRUE, stab.get("fit"));
        assertEquals(ForceAtlasOptions.STABILIZATION_ITERATIONS,
                ((Number) stab.get("iterations")).intValue());
    }

    /* ------------------------------------------------------------------ */
    /*  Cluster-anchor computation                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void clusterAnchorsPickHighestWeightedDegreePerCommunity() {
        // Two communities, two nodes each. a1 carries an extra bridge to c,
        // b2 carries an extra bridge to c — so a1 and b2 are strictly the
        // highest-weighted-degree nodes in their communities.
        GraphNode a1 = node("a1"), a2 = node("a2"),
                  b1 = node("b1"), b2 = node("b2"),
                  c  = node("c");
        GraphData data = graph(
                List.of(a1, a2, b1, b2, c),
                List.of(
                        rel("eAA",     a1, a2, 1000.0),  // a1+a2 both +1000
                        rel("eAa1c",   a1, c,   500.0),  // a1: +500, c: +500
                        rel("eBB",     b2, b1, 500.0),   // b2+b1 both +500
                        rel("eBb2c",   b2, c,   300.0)   // b2: +300, c: +300
                ));
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a1", "#4A90E2");
        colors.put("a2", "#4A90E2");
        colors.put("b1", "#E74C3C");
        colors.put("b2", "#E74C3C");
        colors.put("c",  "#4A90E2");  // c belongs to A so it doesn't add a new community

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>)
                        ForceAtlasOptions.buildOptions(data, colors).get("clusterCentroids");
        assertEquals(2, centroids.size(), "two communities ⇒ two anchors");
        assertTrue(centroids.containsKey("a1"),
                "community A's anchor must be a1 (weightedDegree=1500 vs a2=1000)");
        assertTrue(centroids.containsKey("b2"),
                "community B's anchor must be b2 (weightedDegree=800 vs b1=500)");
        assertFalse(centroids.containsKey("a2"));
        assertFalse(centroids.containsKey("b1"));
        // Each anchor has an {x, y} position.
        for (Map<String, Number> pos : centroids.values()) {
            assertNotNull(pos.get("x"));
            assertNotNull(pos.get("y"));
        }
    }

    @Test
    void clusterAnchorsAreOnDeterministicGrid() {
        GraphNode a = node("a"), b = node("b"), c = node("c"), d = node("d");
        GraphData data = graph(List.of(a, b, c, d), List.of());
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#4A90E2");
        colors.put("b", "#E74C3C");
        colors.put("c", "#27AE60");
        colors.put("d", "#F1C40F");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>)
                        ForceAtlasOptions.buildOptions(data, colors).get("clusterCentroids");
        // Four anchors on a 2×2 grid centred on origin ⇒ x,y ∈ {-175, 175}.
        for (Map<String, Number> pos : centroids.values()) {
            double x = pos.get("x").doubleValue();
            double y = pos.get("y").doubleValue();
            assertTrue(Math.abs(x) <= ForceAtlasOptions.ANCHOR_GRID_STEP_PX / 2.0 + 1e-6,
                    "anchor x=" + x + " must be within one grid step of origin");
            assertTrue(Math.abs(y) <= ForceAtlasOptions.ANCHOR_GRID_STEP_PX / 2.0 + 1e-6,
                    "anchor y=" + y + " must be within one grid step of origin");
        }
    }

    @Test
    void clusterAnchorsEmptyWhenNoLeidenColors() {
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of(
                rel("e", a, b, 1.0)));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>)
                        ForceAtlasOptions.buildOptions(data, Map.of()).get("clusterCentroids");
        assertTrue(centroids.isEmpty(),
                "no Leiden colors ⇒ no anchors (per-edge lengths still applied)");
    }

    /* ------------------------------------------------------------------ */
    /*  Pre-Layout Edge-Filter                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void prefilterMinLogWeightDefaultIs2Point0() {
        assertEquals(2.0, ForceAtlasOptions.DEFAULT_MIN_LOG_WEIGHT, 1e-9);
        assertEquals(3, ForceAtlasOptions.DEFAULT_THRESHOLD_INDEX);
        assertEquals(2.0, ForceAtlasOptions.THRESHOLD_STUFEN.get(3), 1e-9);
    }

    @Test
    void prefilterMinLogWeightRoundTripsIntoOptionsMap() {
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of(), 1.5);
        assertEquals(1.5, ((Number) opts.get("prefilterMinLogWeight")).doubleValue(), 1e-9);
    }

    @Test
    void prefilterMinLogWeightNullUsesDefault() {
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of(), null);
        assertEquals(ForceAtlasOptions.DEFAULT_MIN_LOG_WEIGHT,
                ((Number) opts.get("prefilterMinLogWeight")).doubleValue(), 1e-9);
    }

    @Test
    void prefilterMinLogWeightZeroOrNegativeDisablesFilter() {
        Map<String, Object> opts0 = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of(), 0.0);
        Map<String, Object> optsNeg = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of(), -2.0);
        assertEquals(0.0,
                ((Number) opts0.get("prefilterMinLogWeight")).doubleValue(), 1e-9);
        assertEquals(0.0,
                ((Number) optsNeg.get("prefilterMinLogWeight")).doubleValue(), 1e-9);
    }

    @Test
    void thresholdStufenAreSortedAscending() {
        List<Double> s = ForceAtlasOptions.THRESHOLD_STUFEN;
        for (int i = 1; i < s.size(); i++) {
            assertTrue(s.get(i) > s.get(i - 1),
                    "threshold list must be ascending: " + s);
        }
    }

    @Test
    void thresholdForComboIndexOutOfRangeReturnsOffSentinel() {
        assertEquals(ForceAtlasOptions.MIN_LOG_WEIGHT_OFF,
                ForceAtlasOptions.thresholdForComboIndex(-1), 1e-9);
        assertEquals(ForceAtlasOptions.MIN_LOG_WEIGHT_OFF,
                ForceAtlasOptions.thresholdForComboIndex(999), 1e-9);
    }

    /* ------------------------------------------------------------------ */
    /*  Misc contracts                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void returnedMapIsImmutable() {
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> opts.put("physics", null),
                "returned options map must be immutable");
    }

    @Test
    void rejectsNullDataAndNullColors() {
        assertThrows(IllegalArgumentException.class,
                () -> ForceAtlasOptions.buildOptions(null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ForceAtlasOptions.buildOptions(GraphData.empty(), null));
    }

    @Test
    void metaBlockReflectsGraphStats() {
        GraphNode a = node("a"), b = node("b"), c = node("c");
        GraphData data = graph(List.of(a, b, c), List.of(
                rel("e", a, b, 42.0)));
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(data, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) opts.get("meta");
        assertEquals(3, ((Number) meta.get("totalNodes")).intValue());
        assertEquals(1, ((Number) meta.get("totalEdges")).intValue());
        assertEquals(1, ((Number) meta.get("edgesWithWeight")).intValue());
        assertEquals(0, ((Number) meta.get("communityCount")).intValue());
        // lw = log10(43) ≈ 1.633
        assertEquals(Math.log10(43.0),
                ((Number) meta.get("lwMin")).doubleValue(), 1e-6);
        assertEquals(Math.log10(43.0),
                ((Number) meta.get("lwMax")).doubleValue(), 1e-6);
    }

    @Test
    void backwardCompatibleOverloadUsesDefaultThreshold() {
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                GraphData.empty(), Map.of());
        assertEquals(ForceAtlasOptions.DEFAULT_MIN_LOG_WEIGHT,
                ((Number) opts.get("prefilterMinLogWeight")).doubleValue(), 1e-9);
    }

    /* ------------------------------------------------------------------ */
    /*  Viewport-adaptive anchor grid                                     */
    /* ------------------------------------------------------------------ */

    /** Compute the X/Y spread of the supplied centroids. */
    private static double[] anchorSpread(
            Map<String, Map<String, Number>> centroids) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Map<String, Number> pos : centroids.values()) {
            double x = pos.get("x").doubleValue();
            double y = pos.get("y").doubleValue();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return new double[]{maxX - minX, maxY - minY};
    }

    @Test
    void anchorsAdaptToWideViewport() {
        // 1920×1080 (aspect 1.78), 6 communities
        // sqrt(6 * 1.78) ≈ 3.27 ⇒ cols=4, rows=2 (ceil(6/4))
        // stepX = (1920 - 80) / 3 ≈ 613.33
        // stepY = (1080 - 80) / 1  = 1000
        GraphNode a = node("a"), b = node("b"), c = node("c"),
                  d = node("d"), e = node("e"), f = node("f");
        GraphData data = graph(List.of(a, b, c, d, e, f),
                List.of(rel("e", a, b, 1.0)));
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#1"); colors.put("b", "#2"); colors.put("c", "#3");
        colors.put("d", "#4"); colors.put("e", "#5"); colors.put("f", "#6");

        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, colors, null, 1920.0, 1080.0);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>) opts.get("clusterCentroids");
        assertEquals(6, centroids.size());
        double[] spread = anchorSpread(centroids);
        assertEquals(1840.0, spread[0], 60.0,
                "X-spread must match 4-col grid on 1920 viewport");
        assertEquals(1000.0, spread[1], 60.0,
                "Y-spread must match 2-row grid on 1080 viewport");

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) opts.get("meta");
        assertEquals(4, ((Number) meta.get("gridCols")).intValue());
        assertEquals(2, ((Number) meta.get("gridRows")).intValue());
        assertEquals(1920.0,
                ((Number) meta.get("viewportWidth")).doubleValue(), 1e-6);
        assertEquals(1080.0,
                ((Number) meta.get("viewportHeight")).doubleValue(), 1e-6);
    }

    @Test
    void anchorsAdaptToNarrowViewport() {
        // 800×600 (aspect 1.33), 4 communities
        // sqrt(4 * 1.33) ≈ 2.31 ⇒ cols=3, rows=2 (ceil(4/3))
        // stepX = (800 - 80) / 2 = 360, stepY = (600 - 80) / 1 = 520
        GraphNode a = node("a"), b = node("b"), c = node("c"), d = node("d");
        GraphData data = graph(List.of(a, b, c, d), List.of());
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("a", "#1"); colors.put("b", "#2");
        colors.put("c", "#3"); colors.put("d", "#4");

        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, colors, null, 800.0, 600.0);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>) opts.get("clusterCentroids");
        assertEquals(4, centroids.size());
        double[] spread = anchorSpread(centroids);
        assertEquals(720.0, spread[0], 60.0,
                "X-spread must match 3-col grid on 800 viewport");
        assertEquals(520.0, spread[1], 60.0,
                "Y-spread must match 2-row grid on 600 viewport");
    }

    @Test
    void anchorsUseDefaultGridStepWhenViewportNull() {
        // Backwards compatibility: viewport-null uses the old default.
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of(rel("e", a, b, 1.0)));
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of("a", "#1", "b", "#2"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>) opts.get("clusterCentroids");
        double spreadX = anchorSpread(centroids)[0];
        assertEquals(ForceAtlasOptions.ANCHOR_GRID_STEP_PX, spreadX, 1e-6,
                "viewport=null must use default anchor step");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) opts.get("meta");
        assertEquals(0.0,
                ((Number) meta.get("viewportWidth")).doubleValue(), 1e-6);
        assertEquals(0.0,
                ((Number) meta.get("viewportHeight")).doubleValue(), 1e-6);
    }

    @Test
    void anchorsUseDefaultGridStepWhenViewportTooSmall() {
        // Viewport below MIN_VIEWPORT_PX ⇒ fallback to default step.
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of(rel("e", a, b, 1.0)));
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of("a", "#1", "b", "#2"), null, 50.0, 50.0);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>) opts.get("clusterCentroids");
        double spreadX = anchorSpread(centroids)[0];
        assertEquals(ForceAtlasOptions.ANCHOR_GRID_STEP_PX, spreadX, 1e-6,
                "viewport below MIN_VIEWPORT_PX must fall back to default step");
    }

    @Test
    void gridLayoutProducesAspectRatioForWideScreens() {
        // 21:9 viewport, 7 communities ⇒ more columns than rows
        // sqrt(7 * 2.33) ≈ 4.04 ⇒ cols=5, rows=2
        double[] grid = ForceAtlasOptions.computeGridLayout(
                7, 2520.0, 1080.0);
        assertEquals(5.0, grid[0], 1e-9, "wide aspect ⇒ 5 cols");
        assertEquals(2.0, grid[1], 1e-9, "wide aspect ⇒ 2 rows");
        // stepX = (2520 - 80) / 4 = 610, stepY = (1080 - 80) / 1 = 1000
        assertEquals(610.0, grid[2], 1e-6);
        assertEquals(1000.0, grid[3], 1e-6);
    }

    @Test
    void gridLayoutProducesAspectRatioForTallScreens() {
        // 9:16 viewport (portrait), 7 communities ⇒ more rows than columns
        // aspect = 0.5625, sqrt(7 * 0.5625) ≈ 1.98 ⇒ cols=2, rows=4
        double[] grid = ForceAtlasOptions.computeGridLayout(
                7, 600.0, 1080.0);
        assertEquals(2.0, grid[0], 1e-9, "tall aspect ⇒ 2 cols");
        assertEquals(4.0, grid[1], 1e-9, "tall aspect ⇒ 4 rows");
    }

    @Test
    void gridLayoutSingleCommunityCentresOnOrigin() {
        // Edge case: 1 community → cols=1, rows=1, anchor at (0,0).
        double[] grid = ForceAtlasOptions.computeGridLayout(1, 1000.0, 1000.0);
        assertEquals(1.0, grid[0], 1e-9);
        assertEquals(1.0, grid[1], 1e-9);
        // stepX/stepY for single-column/single-row grids are
        // (width - padding) / max(1, cols-1). With cols=1 ⇒ 1, so
        // stepX = (1000 - 80) / 1 = 920. The anchor itself sits at
        // col=0, row=0 ⇒ x = (0 - 0) * stepX = 0. Same for y.
        // Verify via a buildOptions call:
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of());
        // Both nodes share one community colour ⇒ one anchor.
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of("a", "#1", "b", "#1"), null, 1000.0, 1000.0);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> centroids =
                (Map<String, Map<String, Number>>) opts.get("clusterCentroids");
        assertEquals(1, centroids.size(),
                "two nodes sharing one colour ⇒ one anchor");
        assertEquals(0.0, centroids.values().iterator().next().get("x").doubleValue(), 1e-6);
        assertEquals(0.0, centroids.values().iterator().next().get("y").doubleValue(), 1e-6);
    }

    /* ------------------------------------------------------------------ */
    /*  Isolated-node handling (mass-reduction for orphans)               */
    /* ------------------------------------------------------------------ */

    @Test
    void isolatedNodeIdsContainsDegreeZeroNodes() {
        // a-b-c chain + d-e pair + f (orphan)
        GraphNode a = node("a"), b = node("b"), c = node("c"),
                  d = node("d"), e = node("e"), f = node("f");
        GraphData data = graph(List.of(a, b, c, d, e, f), List.of(
                rel("ab", a, b, 1.0),
                rel("bc", b, c, 1.0),
                rel("de", d, e, 1.0)));
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null, true);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) opts.get("isolatedNodeIds");
        assertEquals(List.of("f"), ids,
                "only f has no incident edges → it's the sole orphan");
    }

    @Test
    void isolatedNodeMassDefaultsTo0_3WhenOrphansExist() {
        // Two nodes, no edges ⇒ both are orphans.
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of());
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null, true);
        assertEquals(ForceAtlasOptions.ISOLATED_NODE_MASS,
                ((Number) opts.get("isolatedNodeMass")).doubleValue(), 1e-9);
        assertEquals(0.3, ForceAtlasOptions.ISOLATED_NODE_MASS, 1e-9,
                "ISOLATED_NODE_MASS must be 0.3 for the FA2 trick");
    }

    @Test
    void isolatedNodeMassIs1_0WhenNoOrphans() {
        // Fully connected pair: no orphans.
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of(rel("e", a, b, 1.0)));
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null, true);
        assertEquals(1.0,
                ((Number) opts.get("isolatedNodeMass")).doubleValue(), 1e-9);
    }

    @Test
    void isolatedNodeMassRespectsIsolateOrphansFlag() {
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of());  // beide isoliert
        Map<String, Object> optsOn = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null, true);
        Map<String, Object> optsOff = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null, false);
        assertEquals(0.3,
                ((Number) optsOn.get("isolatedNodeMass")).doubleValue(), 1e-9,
                "isolateOrphans=true ⇒ mass=0.3 for the two orphans");
        assertEquals(1.0,
                ((Number) optsOff.get("isolatedNodeMass")).doubleValue(), 1e-9,
                "isolateOrphans=false ⇒ every node stays at vis-network default mass");
    }

    @Test
    void metaBlockReportsIsolatedCount() {
        GraphNode a = node("a"), b = node("b"),
                  c = node("c"), d = node("d");
        GraphData data = graph(List.of(a, b, c, d), List.of(
                rel("ab", a, b, 1.0)));  // c, d isolated
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) opts.get("meta");
        assertEquals(2, ((Number) meta.get("isolatedNodeCount")).intValue(),
                "c and d have no incident edges ⇒ 2 orphans");
        assertEquals(Boolean.TRUE, meta.get("isolateOrphansEnabled"));
    }

    @Test
    void isolatedNodeIdsIsEmptyWhenGraphIsFullyConnected() {
        GraphNode a = node("a"), b = node("b"), c = node("c");
        GraphData data = graph(List.of(a, b, c), List.of(
                rel("ab", a, b, 1.0),
                rel("bc", b, c, 1.0)));
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null, true);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) opts.get("isolatedNodeIds");
        assertTrue(ids.isEmpty());
    }

    @Test
    void fiveArgOverloadEnablesOrphanIsolationByDefault() {
        // Backward-compat: the 5-arg overload (no isolateOrphans flag)
        // must still produce isolatedNodeMass=0.3 when orphans exist,
        // because we want the previous behaviour to opt-in by default.
        GraphNode a = node("a"), b = node("b");
        GraphData data = graph(List.of(a, b), List.of());
        Map<String, Object> opts = ForceAtlasOptions.buildOptions(
                data, Map.of(), null, null, null);
        assertEquals(0.3,
                ((Number) opts.get("isolatedNodeMass")).doubleValue(), 1e-9);
    }
}
