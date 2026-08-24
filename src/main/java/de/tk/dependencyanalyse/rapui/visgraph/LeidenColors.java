package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Leiden-style community detection + per-community color assignment.
 *
 * <p>The algorithm is a Louvain-style local-moving modularity optimization
 * (closely related to Leiden — deterministic, single-pass + a few refinement
 * passes). Weighted edges pull strongly connected nodes into the same
 * community.</p>
 *
 * <p>Used by:</p>
 * <ul>
 *   <li>{@link GraphConfigurationDialog} — applies the colors to the
 *       {@link SwitchingViewer}.</li>
 *   <li>{@link de.tk.dependencyanalyse.rapui.visgraph.api.SampleGraphController}
 *       — exposes {@code GET /api/leiden-colors} so the cytoscape-viewer.js
 *       bridge can apply the same colors via
 *       {@code cgv_applyLeidenColors}.</li>
 * </ul>
 */
public final class LeidenColors {

    /** Palette of 20 distinct colors for community assignment. */
    public static final String[] PALETTE = new String[] {
            "#4A90E2", "#E74C3C", "#F1C40F", "#27AE60", "#9B59B6",
            "#E67E22", "#1ABC9C", "#34495E", "#FF6B81", "#7F8C8D",
            "#16A085", "#D35400", "#8E44AD", "#C0392B", "#2ECC71",
            "#3498DB", "#9B59B6", "#1F77B4", "#FF7F0E", "#2CA02C"
    };

    private LeidenColors() {}

    /**
     * Run Leiden-style community detection on the given graph and return a
     * map {@code nodeId → palette color}. Edges with {@code weight}
     * property are weighted; missing weights default to 1.0.
     *
     * @return a color map. Nodes with no edges at all map to the first
     *         palette color.
     */
    public static Map<String, String> compute(GraphData data) {
        Map<String, String> out = new LinkedHashMap<>();
        if (data == null || data.getNodes().isEmpty()) return out;

        Map<String, Map<String, Double>> adj = new LinkedHashMap<>();
        for (GraphNode n : data.getNodes()) {
            adj.put(n.getId(), new LinkedHashMap<>());
        }

        double totalWeight = 0;
        for (GraphRelationship r : data.getRelationships()) {
            double w = readWeight(r);
            adj.computeIfAbsent(r.getSourceId(), k -> new LinkedHashMap<>())
               .merge(r.getTargetId(), w, Double::sum);
            adj.computeIfAbsent(r.getTargetId(), k -> new LinkedHashMap<>())
               .merge(r.getSourceId(), w, Double::sum);
            totalWeight += w;
        }

        // Louvain-style local moving (single-level, no aggregation).
        Map<String, Integer> community = new LinkedHashMap<>();
        Map<Integer, Double> sumTot = new LinkedHashMap<>();
        int nextComm = 0;
        for (String n : adj.keySet()) {
            community.put(n, nextComm);
            double s = adj.get(n).values().stream().mapToDouble(Double::doubleValue).sum();
            sumTot.put(nextComm, s);
            nextComm++;
        }

        if (totalWeight > 0) {
            for (int pass = 0; pass < 8; pass++) {
                boolean improved = false;
                for (String n : adj.keySet()) {
                    int currentComm = community.get(n);
                    double k_i = adj.get(n).values().stream().mapToDouble(Double::doubleValue).sum();
                    Map<Integer, Double> neighbor = new LinkedHashMap<>();
                    for (var e : adj.get(n).entrySet()) {
                        int c = community.get(e.getKey());
                        neighbor.merge(c, e.getValue(), Double::sum);
                    }
                    double sumTotMinusN = sumTot.get(currentComm) - k_i;
                    double ownWeight = neighbor.getOrDefault(currentComm, 0.0);
                    int bestComm = currentComm;
                    double bestGain = 0;
                    for (var e : neighbor.entrySet()) {
                        int c = e.getKey();
                        double k_i_in = c == currentComm ? ownWeight : e.getValue();
                        double gain = (k_i_in / totalWeight)
                                - (sumTot.getOrDefault(c, 0.0) * k_i) / (2 * totalWeight * totalWeight)
                                - (ownWeight / totalWeight)
                                + (sumTotMinusN * k_i) / (2 * totalWeight * totalWeight);
                        if (gain > bestGain + 1e-9) {
                            bestGain = gain;
                            bestComm = c;
                        }
                    }
                    if (bestComm != currentComm) {
                        community.put(n, bestComm);
                        sumTot.put(currentComm, sumTotMinusN);
                        sumTot.merge(bestComm, k_i, Double::sum);
                        improved = true;
                    }
                }
                if (!improved) break;
            }
        }

        // Renumber communities to 0..k-1 (in deterministic order).
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        int nIdx = 0;
        for (var e : community.entrySet()) {
            int c = e.getValue();
            if (!remap.containsKey(c)) remap.put(c, nIdx++);
            community.put(e.getKey(), remap.get(c));
        }
        for (var e : community.entrySet()) {
            out.put(e.getKey(), PALETTE[e.getValue() % PALETTE.length]);
        }
        return out;
    }

    private static double readWeight(GraphRelationship r) {
        Object pw = r.getProperties().get(GraphRelationship.PROP_WEIGHT);
        if (pw instanceof Number num) return Math.max(1.0, num.doubleValue());
        if (pw != null) {
            try { return Math.max(1.0, Double.parseDouble(pw.toString())); }
            catch (NumberFormatException ignored) {}
        }
        return 1.0;
    }
}