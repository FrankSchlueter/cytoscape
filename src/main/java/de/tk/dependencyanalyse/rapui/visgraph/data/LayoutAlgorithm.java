package de.tk.dependencyanalyse.rapui.visgraph.data;

/**
 * Layout algorithms supported by the viewer.
 *
 * <p>The enum is shared by both rendering engines. Each value maps to an
 * engine-specific layout:</p>
 *
 * <table>
 *   <caption>Layout value vs engine</caption>
 *   <tr><th>Enum value</th><th>vis-network (GraphViewer)</th><th>Cytoscape (CytoscapeViewer)</th></tr>
 *   <tr><td>FORCE_ATLAS_2D</td><td>physics.solver=forceAtlas2Based</td><td>n/a</td></tr>
 *   <tr><td>BARNES_HUT</td><td>physics.solver=barnesHut</td><td>fcose (closest equivalent)</td></tr>
 *   <tr><td>REPULSION</td><td>physics.solver=repulsion</td><td>n/a</td></tr>
 *   <tr><td>HIERARCHICAL_REPULSION</td><td>physics.solver=hierarchicalRepulsion + hierarchical</td><td>n/a</td></tr>
 *   <tr><td>HIERARCHICAL</td><td>layout.hierarchical.enabled=true (UD)</td><td>breadthfirst (closest)</td></tr>
 *   <tr><td>GRID</td><td>n/a (vis-network has no grid)</td><td>grid</td></tr>
 *   <tr><td>CIRCULAR</td><td>n/a</td><td>circle</td></tr>
 *   <tr><td>COSE</td><td>n/a</td><td>cose</td></tr>
 *   <tr><td>COSE_BILKENT</td><td>n/a</td><td>cose-bilkent</td></tr>
 *   <tr><td>FCOSE</td><td>n/a</td><td>fcose</td></tr>
 *   <tr><td>DAGRE</td><td>n/a</td><td>dagre</td></tr>
 *   <tr><td>BREADTHFIRST</td><td>n/a</td><td>breadthfirst</td></tr>
 *   <tr><td>CONCENTRIC</td><td>n/a</td><td>concentric</td></tr>
 *   <tr><td>COLA</td><td>n/a</td><td>cola (cytoscape.js-cola; constraint-based, see https://github.com/cytoscape/cytoscape.js-cola)</td></tr>
 *   <tr><td>NULL</td><td>n/a</td><td>null (preset positions preserved)</td></tr>
 *   <tr><td>NONE</td><td>disable physics (positions frozen)</td><td>null</td></tr>
 *   <tr><td>LEIDEN_GRID</td><td>n/a</td><td>preset over Leiden communities — places each community in its own cell of a grid; cytoscape-viewer.js implements the pre-seeding</td></tr>
 * </table>
 *
 * <p>{@link #isSupportedByVisNetwork()} and {@link #isSupportedByCytoscape()} let the
 * UI filter the combo so each engine shows only the layouts it actually
 * understands.</p>
 */
public enum LayoutAlgorithm {
    FORCE_ATLAS_2D  (true,  false),
    BARNES_HUT      (true,  false),
    REPULSION       (true,  false),
    HIERARCHICAL_REPULSION(true, false),
    HIERARCHICAL    (true,  false),
    GRID            (false, true),
    CIRCULAR        (false, true),
    CONCENTRIC      (false, true),
    COSE            (false, true),
    COSE_BILKENT    (false, true),
    FCOSE           (false, true),
    DAGRE           (false, true),
    BREADTHFIRST    (false, true),
    //COLA            (false, true),
    NULL            (false, true),
    NONE            (true,  true),
    LEIDEN_GRID     (false, true);

    private final boolean supportedByVisNetwork;
    private final boolean supportedByCytoscape;

    LayoutAlgorithm(boolean supportedByVisNetwork, boolean supportedByCytoscape) {
        this.supportedByVisNetwork = supportedByVisNetwork;
        this.supportedByCytoscape = supportedByCytoscape;
    }

    public boolean isSupportedByVisNetwork() { return supportedByVisNetwork; }
    public boolean isSupportedByCytoscape() { return supportedByCytoscape; }

    /**
     * Returns the subset of values the given engine supports, preserving
     * declaration order so the UI combo entries are stable.
     */
    public static LayoutAlgorithm[] valuesForVisNetwork() {
        LayoutAlgorithm[] all = values();
        LayoutAlgorithm[] out = new LayoutAlgorithm[all.length];
        int n = 0;
        for (LayoutAlgorithm a : all) {
            if (a.supportedByVisNetwork) out[n++] = a;
        }
        LayoutAlgorithm[] r = new LayoutAlgorithm[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    public static LayoutAlgorithm[] valuesForCytoscape() {
        LayoutAlgorithm[] all = values();
        LayoutAlgorithm[] out = new LayoutAlgorithm[all.length];
        int n = 0;
        for (LayoutAlgorithm a : all) {
            if (a.supportedByCytoscape) out[n++] = a;
        }
        LayoutAlgorithm[] r = new LayoutAlgorithm[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }
}
