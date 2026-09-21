package de.tk.dependencyanalyse.rapui.visgraph.data;

/**
 * Layout algorithms supported by the viewer.
 *
 * <p>The enum is shared by all rendering engines. Each value maps to an
 * engine-specific layout.</p>
 *
 * <p>The 3D Force-Directed Graph engine (Three.js + d3-force-3d + kapsule)
 * supports exactly two layout values: {@link #FORCE_3D} (running animation,
 * the default) and {@link #NONE} (paused animation). The
 * {@link #isSupportedByThreeForceGraph()} flag exposes this so the control
 * bar's layout combo can filter accordingly.</p>
 *
 * <p>{@link #isSupportedByVisNetwork()}, {@link #isSupportedByCytoscape()},
 * {@link #isSupportedBySigma()}, {@link #isSupportedByNvl()} and
 * {@link #isSupportedByThreeForceGraph()} let the UI filter the combo so each
 * engine shows only the layouts it actually understands.</p>
 */
public enum LayoutAlgorithm {
    BARNES_HUT      (true,  false, false, true,  false),
    FORCE_ATLAS_2D  (true,  false, false, true,  false),
    REPULSION       (true,  false, false, true,  false),
    HIERARCHICAL_REPULSION(true, false, false, true, false),
    HIERARCHICAL    (true,  false, false, true,  false),
    GRID            (false, true,  false, true,  false),
    CIRCULAR        (false, true,  false, true,  false),
    CONCENTRIC      (false, true,  false, false, false),
    COSE            (false, true,  false, false, false),
    FCOSE           (false, true,  false, false, false),
    BREADTHFIRST    (false, true,  false, false, false),
    NULL            (false, true,  false, false, false),
    NONE            (true,  true,  false, true,  true),
    LEIDEN_GRID     (false, true,  false, false, false),
    FORCE_ATLAS_SIGMA         (false, false, true, false, false),
    FORCE_DIRECTED_2_SIGMA    (false, false, true, false, false),
    NOVERLAP_SIGMA            (false, false, true, false, false),
    CIRCULAR_SIGMA            (false, false, true, false, false),
    RANDOM_SIGMA              (false, false, true, false, false),
    FORCE_3D                  (false, false, false, false, true);

    private final boolean supportedByVisNetwork;
    private final boolean supportedByCytoscape;
    private final boolean supportedBySigma;
    private final boolean supportedByNvl;
    private final boolean supportedByThreeForceGraph;

    LayoutAlgorithm(boolean supportedByVisNetwork, boolean supportedByCytoscape, boolean supportedBySigma, boolean supportedByNvl, boolean supportedByThreeForceGraph) {
        this.supportedByVisNetwork = supportedByVisNetwork;
        this.supportedByCytoscape = supportedByCytoscape;
        this.supportedBySigma = supportedBySigma;
        this.supportedByNvl = supportedByNvl;
        this.supportedByThreeForceGraph = supportedByThreeForceGraph;
    }

    public boolean isSupportedByVisNetwork() { return supportedByVisNetwork; }
    public boolean isSupportedByCytoscape() { return supportedByCytoscape; }
    public boolean isSupportedBySigma() { return supportedBySigma; }
    public boolean isSupportedByNvl() { return supportedByNvl; }
    public boolean isSupportedByThreeForceGraph() { return supportedByThreeForceGraph; }

    /**
     * Returns the subset of values the given engine supports, preserving
     * declaration order so the UI combo entries are stable.
     */
    public static LayoutAlgorithm[] valuesForVisNetwork() {
        return filterByFlag(la -> la.supportedByVisNetwork);
    }

    public static LayoutAlgorithm[] valuesForCytoscape() {
        return filterByFlag(la -> la.supportedByCytoscape);
    }

    public static LayoutAlgorithm[] valuesForSigma() {
        return filterByFlag(la -> la.supportedBySigma);
    }

    public static LayoutAlgorithm[] valuesForNvl() {
        return filterByFlag(la -> la.supportedByNvl);
    }

    public static LayoutAlgorithm[] valuesForThreeForceGraph() {
        return filterByFlag(la -> la.supportedByThreeForceGraph);
    }

    private static LayoutAlgorithm[] filterByFlag(java.util.function.Predicate<LayoutAlgorithm> test) {
        LayoutAlgorithm[] all = values();
        LayoutAlgorithm[] out = new LayoutAlgorithm[all.length];
        int n = 0;
        for (LayoutAlgorithm a : all) {
            if (test.test(a)) out[n++] = a;
        }
        LayoutAlgorithm[] r = new LayoutAlgorithm[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }
}
