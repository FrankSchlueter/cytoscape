package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * vis-network counterpart of {@link ClusterLayoutOptions}. Realises the
 * Cluster-Layout-Strategie (Cluster-Layout.md) for the vis-network engine:
 *
 * <ol>
 *   <li><b>Edge-Logarithmierung</b> — edge lengths are linearly interpolated
 *       from the pre-computed {@code logWeight = log10(weight + 1)} of each
 *       relationship. High {@code logWeight} ⇒ short {@code length}, low
 *       {@code logWeight} ⇒ long {@code length}. vis-network's
 *       {@code forceAtlas2Based} reads each edge's {@code length} as the
 *       spring rest length, so this drives the spring physics directly.</li>
 *   <li><b>Cluster-Anking</b> — the Leiden-community color map is converted
 *       into per-community centroids. The node with the highest
 *       {@code weightedDegree} in each community is pinned to a deterministic
 *       grid position via {@code fixed: {x: true, y: true}} for the duration
 *       of the stabilization; once the layout has settled, the pin is
 *       released so the node can respond to subsequent physics events.</li>
 *   <li><b>Pre-Layout Edge-Filter</b> — edges with
 *       {@code logWeight < prefilterMinLogWeight} are removed from the
 *       DataSet before stabilization and re-added afterwards with their
 *       original {@code length}. Mirrors the Cytoscape behaviour.</li>
 * </ol>
 *
 * <p>The option map is consumed by {@code window.vgv_setLayoutOptions} in
 * {@code vis-graph-viewer.js}, which picks up {@code physics},
 * {@code edgeLengths}, {@code clusterCentroids} and
 * {@code prefilterMinLogWeight}.</p>
 */
public final class ForceAtlasOptions {

    /* ---- vis-network ForceAtlas2-based tuning (Cluster-Layout.md §3) ---- */

    /** Negative ⇒ repulsion. 6× stronger than vis-network default (-50) so
     *  clusters actively push each other apart instead of stacking on
     *  top of each other. */
    public static final double GRAVITATIONAL_CONSTANT = -300.0;
    /** Pulls nodes toward the canvas centre. 8× stronger than the previous
     *  0.005 so each cluster collapses into a tight compact group. */
    public static final double CENTRAL_GRAVITY = 0.04;
    /** Spring stiffness — higher ⇒ snappier equilibrium. 1.5× previous
     *  value to make the log-weight per-edge length differences visible
     *  on-screen. */
    public static final double SPRING_CONSTANT = 0.12;
    /** Velocity damping — higher ⇒ quicker convergence, less oscillation. */
    public static final double DAMPING = 0.6;
    /** 0 disables overlap avoidance, 1 = full force. Bumped to keep
     *  cluster nodes from stacking on top of each other when the
     *  tighter spring lengths pull them in. */
    public static final double AVOID_OVERLAP = 0.7;

    /* ---- Per-edge length interpolation (wider span for visible contrast) ---- */

    /** Length (px) of the weakest / unweighted edge. */
    public static final double LMAX_PX = 320.0;
    /** Length (px) of the strongest edge in the graph. */
    public static final double LMIN_PX = 40.0;
    /** Fallback when {@code lwMax == lwMin} or no weight attribute at all. */
    public static final double SPRING_LENGTH_DEFAULT = 90.0;

    /* ---- Pre-Layout Edge-Filter (Cluster-Layout.md §5) ---- */

    /** Threshold in {@code log10(weight + 1)} units; edges below it are
     *  held back from the layout and re-added after stabilization. */
    public static final double DEFAULT_MIN_LOG_WEIGHT = 2.0;
    /** Sentinel — 0 (or any non-positive value) disables the filter. */
    public static final double MIN_LOG_WEIGHT_OFF = 0.0;

    /** Combo entries for the {@code Min. ln(w+1)} threshold combo. */
    public static final List<Double> THRESHOLD_STUFEN = Collections.unmodifiableList(
            Arrays.asList(0.5, 1.0, 1.5, 2.0, 2.5, 3.0));
    /** Index of the default threshold inside {@link #THRESHOLD_STUFEN}. */
    public static final int DEFAULT_THRESHOLD_INDEX = 3; // → 2.0

    /* ---- Cluster-anchor grid ---- */

    /** Horizontal/vertical distance (px) between two adjacent cluster anchors.
     *  Larger than vis-network's per-edge defaults so clusters stay visually
     *  separated even when the layout converges on a tight canvas. */
    public static final double ANCHOR_GRID_STEP_PX = 450.0;

    /** Per-node mass assigned to degree-0 nodes when isolation is enabled.
     *  {@code 0.3} makes them lighter so FA2's repulsion dominates over
     *  the cluster-anchor pull — they drift to the periphery instead of
     *  stacking up inside the nearest cluster's bounding box. */
    public static final double ISOLATED_NODE_MASS = 0.3;

    /** Edge padding (px) kept free on each side of the canvas when sizing
     *  the anchor grid from the viewport — gives vis-network room for
     *  node bodies and the implicit cluster spread. */
    public static final double EDGE_PADDING_PX = 40.0;

    /** Minimum viewport dimension (px) below which the helper falls back
     *  to {@link #ANCHOR_GRID_STEP_PX}. Avoids degenerate grids when the
     *  composite is not yet laid out or extremely small. */
    public static final double MIN_VIEWPORT_PX = 100.0;

    /* ---- Stabilization ---- */

    /** More iterations than the previous 1000 so the tighter physics
     *  (stronger central gravity + stronger repulsion) has time to
     *  settle into well-separated clusters instead of stopping mid-fall. */
    public static final int STABILIZATION_ITERATIONS = 2500;

    /**
     * Build the vis-network option map for the Cluster-Layout-Strategie.
     *
     * @param data         the current graph (used to compute edge log-weights
     *                     and per-community weighted-degree)
     * @param leidenColors {@code nodeId → hexColor} map as returned by
     *                     {@link LeidenColors#compute(GraphData)}. Must be
     *                     non-null but may be empty — an empty map produces
     *                     the option map without cluster centroids (the
     *                     per-edge length interpolation still runs).
     * @param minLogWeight pre-layout filter threshold in {@code log10(weight+1)}
     *                     units. Edges with {@code logWeight < minLogWeight}
     *                     are removed before stabilization and re-added after.
     *                     {@code 0} or any non-positive value disables the
     *                     filter. Defaults to {@link #DEFAULT_MIN_LOG_WEIGHT}
     *                     when {@code null}.
     * @return immutable map consumable by Gson and passable to
     *         {@code SwitchingViewer.setLayoutOptions(...)}
     */
    /**
     * Backward-compatible overload — uses {@link #DEFAULT_MIN_LOG_WEIGHT}
     * and the default anchor grid step (no viewport sizing).
     */
    public static Map<String, Object> buildOptions(GraphData data,
                                                    Map<String, String> leidenColors,
                                                    Double minLogWeight) {
        return buildOptions(data, leidenColors, minLogWeight, null, null);
    }

    /**
     * Build the vis-network option map for the Cluster-Layout-Strategie,
     * sizing the cluster anchor grid from the supplied viewport
     * dimensions (in pixels). When {@code viewportWidthPx} or
     * {@code viewportHeightPx} is {@code null} / non-positive / smaller
     * than {@link #MIN_VIEWPORT_PX}, the helper falls back to the
     * default {@link #ANCHOR_GRID_STEP_PX} square grid.
     *
     * @param viewportWidthPx  canvas width in pixels ({@code null} ⇒
     *                          fallback to default step)
     * @param viewportHeightPx canvas height in pixels ({@code null} ⇒
     *                          fallback to default step)
     */
    public static Map<String, Object> buildOptions(GraphData data,
                                                    Map<String, String> leidenColors,
                                                    Double minLogWeight,
                                                    Double viewportWidthPx,
                                                    Double viewportHeightPx) {
        return buildOptions(data, leidenColors, minLogWeight,
                viewportWidthPx, viewportHeightPx, true);
    }

    /**
     * Build the vis-network option map for the Cluster-Layout-Strategie,
     * sizing the cluster anchor grid from the supplied viewport
     * dimensions (in pixels) and optionally applying per-node mass
     * reduction to degree-0 nodes so they drift to the periphery instead
     * of stacking inside a cluster.
     *
     * @param viewportWidthPx  canvas width in pixels ({@code null} ⇒
     *                          fallback to default step)
     * @param viewportHeightPx canvas height in pixels ({@code null} ⇒
     *                          fallback to default step)
     * @param isolateOrphans   when {@code true}, degree-0 nodes get
     *                          {@link #ISOLATED_NODE_MASS} so FA2's
     *                          repulsion dominates over the cluster
     *                          pull. When {@code false}, every node
     *                          stays at vis-network's default mass.
     */
    public static Map<String, Object> buildOptions(GraphData data,
                                                    Map<String, String> leidenColors,
                                                    Double minLogWeight,
                                                    Double viewportWidthPx,
                                                    Double viewportHeightPx,
                                                    boolean isolateOrphans) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (leidenColors == null) {
            throw new IllegalArgumentException("leidenColors must not be null");
        }
        double threshold = (minLogWeight == null)
                ? DEFAULT_MIN_LOG_WEIGHT
                : Math.max(0.0, minLogWeight.doubleValue());

        Map<String, Object> out = new LinkedHashMap<>();

        // 1) FA2 physics block — fully spelled out so the JS bridge can
        //    apply it via a single network.setOptions(physics) call.
        Map<String, Object> fa2 = new LinkedHashMap<>();
        fa2.put("gravitationalConstant", GRAVITATIONAL_CONSTANT);
        fa2.put("centralGravity",        CENTRAL_GRAVITY);
        fa2.put("springConstant",        SPRING_CONSTANT);
        fa2.put("springLength",          SPRING_LENGTH_DEFAULT);
        fa2.put("damping",               DAMPING);
        fa2.put("avoidOverlap",          AVOID_OVERLAP);
        Map<String, Object> physics = new LinkedHashMap<>();
        physics.put("enabled", Boolean.TRUE);
        physics.put("solver", "forceAtlas2Based");
        Map<String, Object> stab = new LinkedHashMap<>();
        stab.put("enabled",    Boolean.TRUE);
        stab.put("iterations", STABILIZATION_ITERATIONS);
        stab.put("fit",        Boolean.TRUE);
        physics.put("stabilization", stab);
        physics.put("forceAtlas2Based", fa2);
        out.put("physics", physics);

        // 2) Per-edge length interpolation — empty when there are no edges.
        Map<String, Double> edgeLengths = new LinkedHashMap<>();
        double lwMin = 0.0;
        double lwMax = 0.0;
        int edgesWithWeight = 0;
        if (!data.getRelationships().isEmpty()) {
            double[] stats = computeLogWeightStats(data);
            lwMin = stats[0];
            lwMax = stats[1];
            boolean uniform = (lwMax - lwMin) < 1e-9;
            for (GraphRelationship r : data.getRelationships()) {
                Double w = r.getWeight();
                double length;
                if (w == null || w <= 0) {
                    // No weight attribute → longest spring rest length.
                    length = LMAX_PX;
                } else if (uniform) {
                    length = SPRING_LENGTH_DEFAULT;
                } else {
                    double lw = Math.log10(w + 1.0);
                    // Linearly interpolate: lwMin → LMAX_PX, lwMax → LMIN_PX.
                    double t = (lw - lwMin) / (lwMax - lwMin);
                    if (t < 0.0) t = 0.0;
                    if (t > 1.0) t = 1.0;
                    length = LMAX_PX + t * (LMIN_PX - LMAX_PX);
                }
                edgeLengths.put(r.getId(), length);
                edgesWithWeight++;
            }
        }
        out.put("edgeLengths", edgeLengths);

        // 3) Cluster centroids — pinned nodes per Leiden community.
        Map<String, Map<String, Number>> centroids = new LinkedHashMap<>();
        if (!leidenColors.isEmpty()) {
            centroids.putAll(computeClusterCentroids(
                    data, leidenColors, viewportWidthPx, viewportHeightPx));
        }
        out.put("clusterCentroids", centroids);

        // 4) Threshold & metadata for the JS bridge and the dialog.
        out.put("prefilterMinLogWeight", threshold);
        int communityCount = new java.util.HashSet<>(leidenColors.values()).size();
        double[] grid = computeGridLayout(
                communityCount, viewportWidthPx, viewportHeightPx);

        // 5) Isolated-node handling. We count degree over the FULL
        //    relationship list (not the filtered one) because the
        //    pre-layout filter only hides weak edges temporarily —
        //    the graph's topological isolation is determined by the
        //    raw adjacency and is independent of the threshold.
        List<String> isolatedIds = computeIsolatedNodeIds(data);
        double isolatedMass = (isolateOrphans && !isolatedIds.isEmpty())
                ? ISOLATED_NODE_MASS : 1.0;
        out.put("isolatedNodeIds", isolatedIds);
        out.put("isolatedNodeMass", isolatedMass);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lwMin", lwMin);
        meta.put("lwMax", lwMax);
        meta.put("edgesWithWeight", edgesWithWeight);
        meta.put("totalNodes", data.getNodes().size());
        meta.put("totalEdges", data.getRelationships().size());
        meta.put("communityCount", communityCount);
        meta.put("viewportWidth", viewportWidthPx != null ? viewportWidthPx : 0.0);
        meta.put("viewportHeight", viewportHeightPx != null ? viewportHeightPx : 0.0);
        meta.put("gridCols", (int) grid[0]);
        meta.put("gridRows", (int) grid[1]);
        meta.put("gridStepX", grid[2]);
        meta.put("gridStepY", grid[3]);
        meta.put("isolatedNodeCount", isolatedIds.size());
        meta.put("isolateOrphansEnabled", isolateOrphans);
        out.put("meta", meta);

        return Collections.unmodifiableMap(out);
    }

    /**
     * Backward-compatible overload — uses {@link #DEFAULT_MIN_LOG_WEIGHT}
     * and the default anchor grid step (no viewport sizing).
     */
    public static Map<String, Object> buildOptions(GraphData data,
                                                    Map<String, String> leidenColors) {
        return buildOptions(data, leidenColors, null, null, null);
    }

    /**
     * Return the list of node ids that have no incident relationships
     * in the raw graph. These are the "orphans" that the
     * Cluster-Layout-Strategie can push to the periphery by reducing
     * their per-node mass.
     */
    static List<String> computeIsolatedNodeIds(GraphData data) {
        Map<String, Integer> degree = new LinkedHashMap<>();
        for (GraphNode n : data.getNodes()) degree.put(n.getId(), 0);
        for (GraphRelationship r : data.getRelationships()) {
            degree.merge(r.getSourceId(), 1, Integer::sum);
            degree.merge(r.getTargetId(), 1, Integer::sum);
        }
        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, Integer> e : degree.entrySet()) {
            if (e.getValue() == 0) orphans.add(e.getKey());
        }
        return orphans;
    }

    /**
     * Compute {@code [lwMin, lwMax]} across all relationships. Edges without a
     * positive weight are ignored so they don't skew the span.
     */
    static double[] computeLogWeightStats(GraphData data) {
        double lwMin = Double.POSITIVE_INFINITY;
        double lwMax = 0.0;
        for (GraphRelationship r : data.getRelationships()) {
            Double w = r.getWeight();
            if (w == null || w <= 0) continue;
            double lw = Math.log10(w + 1.0);
            if (lw < lwMin) lwMin = lw;
            if (lw > lwMax) lwMax = lw;
        }
        if (lwMin == Double.POSITIVE_INFINITY) {
            // No weighted edges — return zeros so the caller can fall back.
            return new double[]{0.0, 0.0};
        }
        return new double[]{lwMin, lwMax};
    }

    /**
     * Compute a {@code nodeId → {x, y}} map of cluster anchors. Each Leiden
     * community is assigned one anchor node (highest weighted-degree). The
     * anchors are placed on a deterministic grid centred on the origin so
     * the layout starts from a balanced pre-seed. The grid's aspect ratio
     * and step size are derived from the supplied viewport dimensions
     * (when present); without a viewport a square grid with
     * {@link #ANCHOR_GRID_STEP_PX} spacing is used.
     */
    static Map<String, Map<String, Number>> computeClusterCentroids(
            GraphData data, Map<String, String> leidenColors,
            Double viewportWidthPx, Double viewportHeightPx) {

        // 1) communityOf(nodeId) — derived from the color map by collapsing
        //    distinct hex values to consecutive indices in iteration order.
        Map<String, Integer> communityOf = new LinkedHashMap<>();
        Map<String, Integer> colorToIdx = new LinkedHashMap<>();
        int next = 0;
        List<GraphNode> nodes = data.getNodes();
        for (GraphNode n : nodes) {
            String color = leidenColors.get(n.getId());
            if (color == null) {
                communityOf.put(n.getId(), -1); // not in any cluster
                continue;
            }
            Integer idx = colorToIdx.get(color);
            if (idx == null) {
                idx = next++;
                colorToIdx.put(color, idx);
            }
            communityOf.put(n.getId(), idx);
        }
        int numComms = next;
        if (numComms == 0) return Collections.emptyMap();

        // 2) weightedDegree(node) within its community.
        Map<String, Double> wdeg = new LinkedHashMap<>();
        for (GraphNode n : nodes) wdeg.put(n.getId(), 0.0);
        for (GraphRelationship r : data.getRelationships()) {
            Double w = r.getWeight();
            double eff = (w == null || w <= 0) ? 0.0 : w;
            String s = r.getSourceId();
            String t = r.getTargetId();
            wdeg.merge(s, eff, Double::sum);
            wdeg.merge(t, eff, Double::sum);
        }

        // 3) For each community, pick the node with the highest weighted
        //    degree (ties broken by deterministic node-id order).
        Map<Integer, String> anchorByComm = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : communityOf.entrySet()) {
            int c = e.getValue();
            if (c < 0) continue;
            String id = e.getKey();
            String cur = anchorByComm.get(c);
            if (cur == null
                    || wdeg.getOrDefault(id, 0.0) > wdeg.getOrDefault(cur, 0.0)
                    || (wdeg.getOrDefault(id, 0.0) == wdeg.getOrDefault(cur, 0.0)
                            && id.compareTo(cur) < 0)) {
                anchorByComm.put(c, id);
            }
        }

        // 4) Place anchors on a grid (cols × rows) centred on origin. When
        //    the viewport is supplied we use the aspect ratio to lay out
        //    more columns on wide screens and more rows on tall screens,
        //    and size the step so the grid (almost) fills the canvas.
        double[] grid = computeGridLayout(
                numComms, viewportWidthPx, viewportHeightPx);
        int cols = (int) grid[0];
        int rows = (int) grid[1];
        double stepX = grid[2];
        double stepY = grid[3];

        Map<String, Map<String, Number>> out = new LinkedHashMap<>();
        List<Map.Entry<Integer, String>> ordered = new ArrayList<>(anchorByComm.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        int idx = 0;
        for (Map.Entry<Integer, String> e : ordered) {
            int col = idx % cols;
            int row = idx / cols;
            double x = (col - (cols - 1) / 2.0) * stepX;
            double y = (row - (rows - 1) / 2.0) * stepY;
            Map<String, Number> pos = new LinkedHashMap<>();
            pos.put("x", x);
            pos.put("y", y);
            out.put(e.getValue(), pos);
            idx++;
        }
        return out;
    }

    /**
     * Compute the grid dimensions ({@code cols}, {@code rows}) and step
     * sizes ({@code stepX}, {@code stepY}) for a cluster anchor grid
     * sized to fit the supplied viewport.
     *
     * <p>Without a viewport (or one below {@link #MIN_VIEWPORT_PX}) the
     * helper falls back to a square grid with the default
     * {@link #ANCHOR_GRID_STEP_PX} on both axes.</p>
     *
     * <p>With a viewport, {@code cols = ceil(sqrt(N * aspect))} so a
     * 16:9 monitor with 8 communities yields 4 columns × 2 rows instead
     * of a 3×3 square grid. Steps are sized so the grid fills the
     * viewport minus {@link #EDGE_PADDING_PX} on each side.</p>
     *
     * @return {@code [cols, rows, stepX, stepY]}
     */
    static double[] computeGridLayout(int numComms,
                                        Double viewportWidthPx,
                                        Double viewportHeightPx) {
        if (numComms <= 0) return new double[]{0, 0, 0, 0};
        if (viewportWidthPx == null || viewportHeightPx == null
                || viewportWidthPx < MIN_VIEWPORT_PX
                || viewportHeightPx < MIN_VIEWPORT_PX) {
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(numComms)));
            int rows = Math.max(1, (int) Math.ceil((double) numComms / cols));
            return new double[]{cols, rows, ANCHOR_GRID_STEP_PX, ANCHOR_GRID_STEP_PX};
        }
        double aspect = viewportWidthPx / viewportHeightPx;
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(numComms * aspect)));
        int rows = Math.max(1, (int) Math.ceil((double) numComms / cols));
        double stepX = (viewportWidthPx - 2 * EDGE_PADDING_PX)
                / Math.max(1, cols - 1);
        double stepY = (viewportHeightPx - 2 * EDGE_PADDING_PX)
                / Math.max(1, rows - 1);
        return new double[]{cols, rows, stepX, stepY};
    }

    /**
     * Resolve a combo index (as selected in the {@code GraphConfigurationDialog})
     * into the corresponding threshold value. Sentinel {@code -1} or any
     * index outside the {@link #THRESHOLD_STUFEN} bounds returns
     * {@link #MIN_LOG_WEIGHT_OFF} (filter disabled).
     */
    public static double thresholdForComboIndex(int idx) {
        if (idx < 0 || idx >= THRESHOLD_STUFEN.size()) return MIN_LOG_WEIGHT_OFF;
        return THRESHOLD_STUFEN.get(idx);
    }

    private ForceAtlasOptions() {}
}
