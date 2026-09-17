package de.tk.dependencyanalyse.rapui.visgraph.data;

/**
 * Layout algorithms supported by the viewer.
 *
 * <p>The enum is shared by all three rendering engines. Each value maps to an
 * engine-specific layout:</p>
 *
 * <table>
 *   <caption>Layout value vs engine</caption>
 *   <tr><th>Enum value</th><th>vis-network (GraphViewer)</th><th>Cytoscape (CytoscapeViewer)</th><th>sigma.js (SigmaViewer)</th></tr>
 *   <tr><td>FORCE_ATLAS_2D</td><td>physics.solver=forceAtlas2Based</td><td>n/a</td><td>n/a</td></tr>
 *   <tr><td>BARNES_HUT</td><td>physics.solver=barnesHut</td><td>fcose (closest equivalent)</td><td>n/a</td></tr>
 *   <tr><td>REPULSION</td><td>physics.solver=repulsion</td><td>n/a</td><td>n/a</td></tr>
 *   <tr><td>HIERARCHICAL_REPULSION</td><td>physics.solver=hierarchicalRepulsion + hierarchical</td><td>n/a</td><td>n/a</td></tr>
 *   <tr><td>HIERARCHICAL</td><td>layout.hierarchical.enabled=true (UD)</td><td>breadthfirst (closest)</td><td>n/a</td></tr>
 *   <tr><td>GRID</td><td>n/a (vis-network has no grid)</td><td>grid</td><td>n/a</td></tr>
 *   <tr><td>CIRCULAR</td><td>n/a</td><td>circle</td><td>graphology-layout.circular (RANDOM_SIGMA-equivalent name overlap; here CIRCULAR=Cytoscape circle)</td></tr>
 *   <tr><td>COSE</td><td>n/a</td><td>cose</td><td>n/a</td></tr>
 *   <tr><td>FCOSE</td><td>n/a</td><td>fcose</td><td>n/a</td></tr>
 *   <tr><td>DAGRE</td><td>n/a</td><td>dagre</td><td>n/a</td></tr>
 *   <tr><td>BREADTHFIRST</td><td>n/a</td><td>breadthfirst</td><td>n/a</td></tr>
 *   <tr><td>CONCENTRIC</td><td>n/a</td><td>concentric</td><td>n/a</td></tr>
 *   <tr><td>COLA</td><td>n/a</td><td>cola (cytoscape.js-cola)</td><td>n/a</td></tr>
 *   <tr><td>NULL</td><td>n/a</td><td>null (preset positions preserved)</td><td>n/a</td></tr>
 *   <tr><td>NONE</td><td>disable physics (positions frozen)</td><td>null</td><td>n/a</td></tr>
 *   <tr><td>LEIDEN_GRID</td><td>n/a</td><td>preset over Leiden communities</td><td>n/a</td></tr>
 *   <tr><td>FORCE_ATLAS_SIGMA</td><td>n/a</td><td>n/a</td><td>graphology-layout-forceatlas2.assign (default settings)</td></tr>
 *   <tr><td>FORCE_DIRECTED_2_SIGMA</td><td>n/a</td><td>n/a</td><td>FA2 with log10(weight+1)-driven edgeWeight so high-weight edges pull nodes closer; followed by graphology-layout-noverlap for non-overlapping node placement</td></tr>
 *   <tr><td>NOVERLAP_SIGMA</td><td>n/a</td><td>n/a</td><td>graphology-layout-noverlap on current positions</td></tr>
 *   <tr><td>CIRCULAR_SIGMA</td><td>n/a</td><td>n/a</td><td>graphology-layout.circular (nodes on a circle)</td></tr>
 *   <tr><td>RANDOM_SIGMA</td><td>n/a</td><td>n/a</td><td>graphology-layout.random (uniform random positions, used as a preseed for NoOverlap)</td></tr>
 * </table>
 *
 * <p>{@link #isSupportedByVisNetwork()}, {@link #isSupportedByCytoscape()} and
 * {@link #isSupportedBySigma()} let the UI filter the combo so each engine
 * shows only the layouts it actually understands.</p>
 */
public enum LayoutAlgorithm {
    BARNES_HUT      (true,  false, false, true),
    FORCE_ATLAS_2D  (true,  false, false, true),
    REPULSION       (true,  false, false, true),
    HIERARCHICAL_REPULSION(true, false, false, true),
    HIERARCHICAL    (true,  false, false, true),
    GRID            (false, true,  false, true),
    CIRCULAR        (false, true,  false, true),
    CONCENTRIC      (false, true,  false, false),
    COSE            (false, true,  false, false),
    //COSE_BILKENT    (false, true,  false, false),
    FCOSE           (false, true,  false, false),
    // DAGRE           (false, true,  false, false),
    BREADTHFIRST    (false, true,  false, false),
    //COLA            (false, true,  false, false),
    NULL            (false, true,  false, false),
    NONE            (true,  true,  false, true),
    LEIDEN_GRID     (false, true,  false, false),
    FORCE_ATLAS_SIGMA         (false, false, true, false),
    FORCE_DIRECTED_2_SIGMA    (false, false, true, false),
    NOVERLAP_SIGMA            (false, false, true, false),
    CIRCULAR_SIGMA            (false, false, true, false),
    RANDOM_SIGMA              (false, false, true, false);

    private final boolean supportedByVisNetwork;
    private final boolean supportedByCytoscape;
    private final boolean supportedBySigma;
    private final boolean supportedByNvl;

    LayoutAlgorithm(boolean supportedByVisNetwork, boolean supportedByCytoscape, boolean supportedBySigma, boolean supportedByNvl) {
        this.supportedByVisNetwork = supportedByVisNetwork;
        this.supportedByCytoscape = supportedByCytoscape;
        this.supportedBySigma = supportedBySigma;
        this.supportedByNvl = supportedByNvl;
    }

    public boolean isSupportedByVisNetwork() { return supportedByVisNetwork; }
    public boolean isSupportedByCytoscape() { return supportedByCytoscape; }
    public boolean isSupportedBySigma() { return supportedBySigma; }
    public boolean isSupportedByNvl() { return supportedByNvl; }

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
