package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fcose layout options that realise the "3-Säulen-Strategie" described in
 * {@code Cluster-Layout.md}:
 *
 * <ol>
 *   <li><b>Edge-Logarithmierung</b> — {@code idealEdgeLength} and
 *       {@code edgeElasticity} read the pre-computed {@code logWeight} Cytoscape
 *       attribute (surfaced by {@code GraphRelationship.toCytoscapeEdge()} as
 *       {@code ln(weight+1)}). fcose's spring force is
 *       {@code elasticity × (currentLength − idealEdgeLength)}, so both
 *       terms must agree on direction: high {@code logWeight} ⇒ short
 *       idealEdgeLength AND stiff spring (Hooksches k). Low
 *       {@code logWeight} ⇒ long idealEdgeLength AND weak spring.
 *       Earlier versions used {@code 1/logWeight} for elasticity, which
 *       made LOW-weight edges the stiffest and pulled unrelated nodes
 *       apart — the opposite of a strong binding.</li>
 *   <li><b>Compound-Parents</b> — the Cytoscape bridge injects one
 *       {@code node[isCluster]} parent per Leiden community and sets
 *       {@code data.parent} on every member node. fcose's
 *       {@code gravityRangeCompound} + {@code gravityCompound} then actively
 *       push the cluster boxes apart.</li>
 *   <li><b>fcose (Fast Compound Spring Embedder)</b> — the only Cytoscape
 *       layout that natively understands compound parents and weighted
 *       spring lengths in one pass.</li>
 * </ol>
 *
 * <p>This class is consumed by
 * {@link GraphConfigurationDialog#applyLeidenClustering()} after
 * {@link LeidenColors#compute(GraphData)} has produced the
 * {@code nodeId → hexColor} map. The Cytoscape bridge then
 * {@code injectClusterParents()}s the elements array (so each colour
 * becomes a compound parent) and feeds the resulting options into fcose
 * via {@code setLayoutOptions}.</p>
 *
 * <p><b>Pre-Layout Edge-Filter (Cluster-Layout.md §5)</b>: a configurable
 * threshold {@link #DEFAULT_MIN_LOG_WEIGHT} ({@code ln(weight+1)} units,
 * default {@value #DEFAULT_MIN_LOG_WEIGHT}) hides weak-weight edges from
 * fcose so background noise doesn't distort the cluster layout. The
 * hidden edges are added back to the canvas <i>after</i> the layout has
 * settled (see {@code partitionEdgesForLayout} in {@code cytoscape-viewer.js}).
 * The threshold is exposed to the user via the {@code ThresholdStufen}
 * combo in the dialog.</p>
 *
 * <p>vis-network has no compound-parent semantics, so callers should
 * branch on the active engine before applying these options — see
 * {@code GraphConfigurationDialog} for the engine-aware status messaging.</p>
 */
public final class ClusterLayoutOptions {

    /** Smaller ⇒ nodes stay tighter inside their own compound parent. */
    public static final double NESTING_FACTOR = 0.1;

    /** Higher ⇒ stronger repulsion between different cluster boxes. */
    public static final double GRAVITY_RANGE_COMPOUND = 2.5;

    /** Pushes the compound-parent boxes apart on every iteration. */
    public static final double GRAVITY_COMPOUND = 3.0;

    /** General node-node repulsion (keeps siblings apart within a cluster). */
    public static final double NODE_REPULSION = 6500;

    /** Inter-cluster bridge edges are forced long → visually obvious. */
    public static final double IDEAL_INTER_CLUSTER_EDGE_LENGTH = 300.0;

    /** fcose quality level (proof = max iterations, slowest but best). */
    public static final String QUALITY = "proof";

    /** Number of fcose iterations (must be present for 'proof' to take effect). */
    public static final int NUM_ITER = 3000;

    /**
     * Default Pre-Layout-Filter threshold in {@code ln(weight+1)} units.
     *
     * <p>{@code 2.0} corresponds to {@code weight ≥ e^2 − 1 ≈ 6.39} — i.e.
     * edges that contribute a meaningful spring force. The CSV sample
     * ({@code /sample/export.csv}, 1010 edges) has only ~36% of edges
     * below this threshold, so fcose sees a leaner graph and the cluster
     * boxes stay properly separated.</p>
     *
     * <p>Sentinel: {@link #MIN_LOG_WEIGHT_OFF} ({@code 0.0} or negative)
     * disables the filter entirely.</p>
     */
    public static final double DEFAULT_MIN_LOG_WEIGHT = 2.0;

    /** Sentinel for "filter disabled" — matches any non-positive value. */
    public static final double MIN_LOG_WEIGHT_OFF = 0.0;

    /**
     * Combo entries for the {@code Min. ln(weight+1)} threshold combo in the
     * {@code GraphConfigurationDialog}. The trailing entry {@code "aus"}
     * disables the filter (→ {@link #MIN_LOG_WEIGHT_OFF}). Keep the list
     * sorted ascending so the combo reads low → high.
     */
    public static final List<Double> THRESHOLD_STUFEN = Collections.unmodifiableList(
            Arrays.asList(0.5, 1.0, 1.5, 2.0, 2.5, 3.0));

    /** Index of the default threshold inside {@link #THRESHOLD_STUFEN}. */
    public static final int DEFAULT_THRESHOLD_INDEX = 3; // → 2.0

    /**
     * Build the fcose options map for the Cluster-Layout-Strategie. The map
     * shape is consumable by Gson (the Cytoscape JS bridge turns function
     * strings into live JS functions via {@code new Function('return '+src)()}).
     *
     * @param data           the current graph (used so we can keep the signature
     *                       symmetric with future per-data tuning; currently unused)
     * @param colors         Leiden-community colour map (nodeId → hex). Must be
     *                       non-null but may be empty; an empty map yields the same
     *                       options without the cluster-parent side effects (those
     *                       live in the JS bridge, not here).
     * @param minLogWeight   pre-layout edge-filter threshold in {@code ln(weight+1)}
     *                       units. Edges with {@code data.logWeight < minLogWeight}
     *                       are held back from fcose and added back to the canvas
     *                       after the layout has settled. {@code 0} or any
     *                       non-positive value disables the filter (all edges go
     *                       into the layout). Defaults to
     *                       {@link #DEFAULT_MIN_LOG_WEIGHT} when {@code null}.
     * @return immutable map ready for {@code SwitchingViewer.setLayoutOptions(...)}
     */
    public static Map<String, Object> buildFcoseOptions(GraphData data,
                                                        Map<String, String> colors,
                                                        Double minLogWeight) {
        double effectiveThreshold = (minLogWeight == null)
                ? DEFAULT_MIN_LOG_WEIGHT
                : Math.max(0.0, minLogWeight.doubleValue());

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("name", "fcose");
        opts.put("quality", QUALITY);
        // randomize=true is required so fcose genuinely uses the compound
        // parents as physical barriers. The Java-side bridge pre-seeds
        // cluster CENTRES into a grid (cytoscape-viewer.js
        // preseedCommunityPositions), so users still get a recognisable
        // cluster skeleton even when fcose reshuffles per-node positions.
        opts.put("randomize", true);
        opts.put("animate", false);
        opts.put("fit", true);
        opts.put("padding", 30);
        opts.put("tile", true);

        // Compound-cluster forces from Cluster-Layout.md §3.
        opts.put("nestingFactor", NESTING_FACTOR);
        opts.put("gravityRangeCompound", GRAVITY_RANGE_COMPOUND);
        opts.put("gravityCompound", GRAVITY_COMPOUND);
        opts.put("nodeRepulsion", NODE_REPULSION);
        opts.put("idealInterClusterEdgeLength", IDEAL_INTER_CLUSTER_EDGE_LENGTH);

        // 'proof' quality requires an iteration budget; supply one.
        opts.put("numIter", NUM_ITER);

        // Weighted spring length via the pre-computed logWeight Cytoscape
        // attribute. Cytoscape attribute selectors + Cytoscape's
        // function-as-string support let us keep the formula in JS, the
        // browser-native language fcose already understands. Bridge decodes
        // the strings via new Function('return '+src)().
        //
        //   edgeElasticity  : high logWeight → stiffer spring (Hooke 'k')
        //                     formula: max(logWeight, 0)   (clamped at 0)
        //   idealEdgeLength : high logWeight → shorter rest length
        //                     formula: 120 / max(logWeight, 0.5)
        //
        // fcose computes the spring force as
        //   F = elasticity * (currentLength - idealEdgeLength)
        // so BOTH terms must agree on the direction:
        //   - high logWeight → short idealEdgeLength AND stiff spring:
        //     both pull the endpoints together with strong force.
        //   - low logWeight → long idealEdgeLength AND weak spring:
        //     the endpoints drift apart and the spring doesn't fight it.
        //
        // Earlier versions used 1/logWeight for both, which made LOW-weight
        // edges stiff (because 1/0.7 ≈ 1.4 is large) — they then forced their
        // (long) idealEdgeLength of ~170 px onto the layout, dragging
        // unrelated nodes apart. That's the opposite of what a strong
        // binding should look like.
        //
        // Cytoscape's data('logWeight') returns undefined when an edge has
        // no weight attribute; we coerce lw to 0 so unweighted edges get
        // the longest rest length and zero elasticity (no spring force).
        opts.put("edgeElasticity",
                "function(edge){var lw=edge.data('logWeight');lw=typeof lw==='number'&&lw>0?lw:0;return lw;}");
        opts.put("idealEdgeLength",
                "function(edge){var lw=edge.data('logWeight');lw=typeof lw==='number'&&lw>0?lw:0;return 500/Math.max(lw,0.5);}");

        // Pre-Layout Edge-Filter threshold (Cluster-Layout.md §5). The
        // Cytoscape bridge reads this via isClusterLayoutActive() / the
        // pendingLayoutOptions map and partitions the elements array
        // before adding them to cy. 0 (or omitted) disables the filter.
        opts.put("prefilterMinLogWeight", effectiveThreshold);

        // data is intentionally unused today (cluster-tuning lives in JS).
        // Keep the parameter so callers don't have to know whether we'll
        // need it tomorrow.
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (colors == null) {
            throw new IllegalArgumentException("colors must not be null");
        }

        return java.util.Collections.unmodifiableMap(opts);
    }

    /**
     * Backward-compatible overload — uses {@link #DEFAULT_MIN_LOG_WEIGHT}.
     *
     * @see #buildFcoseOptions(GraphData, Map, Double)
     */
    public static Map<String, Object> buildFcoseOptions(GraphData data,
                                                        Map<String, String> colors) {
        return buildFcoseOptions(data, colors, null);
    }

    /**
     * Resolve a combo index (as selected in the {@code GraphConfigurationDialog})
     * into the corresponding threshold value. Sentinel {@code -1} or any index
     * outside the {@link #THRESHOLD_STUFEN} bounds returns
     * {@link #MIN_LOG_WEIGHT_OFF} (filter disabled).
     */
    public static double thresholdForComboIndex(int idx) {
        if (idx < 0 || idx >= THRESHOLD_STUFEN.size()) return MIN_LOG_WEIGHT_OFF;
        return THRESHOLD_STUFEN.get(idx);
    }

    /**
     * Build the deterministic compound-parent id for a community colour.
     * The JS bridge uses the same convention so a Java-generated id and
     * a JS-injected id refer to the same Cytoscape element.
     *
     * <p>{@code idx} is the 0-based position of {@code hexColor} in the
     * {@code Object.keys(colors)} iteration order (the JS bridge injects
     * parents in that order so the index is stable).</p>
     */
    public static String clusterParentId(int idx) {
        return "cluster_" + idx;
    }

    private ClusterLayoutOptions() {}
}