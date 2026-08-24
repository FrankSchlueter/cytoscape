package de.tk.dependencyanalyse.rapui.visgraph.data;

/**
 * Node shapes supported by the two rendering engines.
 *
 * <p>Each value carries:</p>
 * <ul>
 *   <li>{@link #visNetworkName} — the exact token vis-network expects, when
 *       supported by vis-network ({@link #isSupportedByVisNetwork()}).</li>
 *   <li>{@link #cytoscapeName} — the exact token Cytoscape.js expects, when
 *       supported by Cytoscape ({@link #isSupportedByCytoscape()}).</li>
 * </ul>
 *
 * <p>Constructor takes the two engine booleans exactly like
 * {@link LayoutAlgorithm}, and the rest of the configuration UI uses the
 * resulting {@code Shape.valuesForXxx()} filter just like layouts.</p>
 *
 * <h2>Cytoscape.js — reference</h2>
 * <p>Source: <a href="https://js.cytoscape.org/#style/node-body">
 * js.cytoscape.org/#style/node-body</a>.</p>
 *
 * <table>
 *   <caption>Shape values vs supported engine</caption>
 *   <tr><th>Enum value</th><th>vis-network</th><th>Cytoscape</th><th>Cytoscape name</th></tr>
 *   <tr><td>ELLIPSE</td>          <td>yes</td><td>yes</td><td>ellipse</td></tr>
 *   <tr><td>CIRCLE</td>           <td>yes</td><td>no</td><td>—</td></tr>
 *   <tr><td>BOX</td>             <td>yes</td><td>yes (rectangle)</td><td>rectangle</td></tr>
 *   <tr><td>SQUARE</td>          <td>yes</td><td>no</td><td>—</td></tr>
 *   <tr><td>DATABASE</td>        <td>yes</td><td>no</td><td>—</td></tr>
 *   <tr><td>DOT</td>             <td>yes</td><td>no</td><td>—</td></tr>
 *   <tr><td>DIAMOND</td>         <td>yes</td><td>yes</td><td>diamond</td></tr>
 *   <tr><td>TRIANGLE</td>        <td>yes</td><td>yes</td><td>triangle</td></tr>
 *   <tr><td>STAR</td>            <td>yes</td><td>yes</td><td>star</td></tr>
 *   <tr><td>TEXT</td>            <td>yes</td><td>no</td><td>—</td></tr>
 *   <tr><td>ICON</td>            <td>yes</td><td>no</td><td>—</td></tr>
 *   <tr><td>IMAGE</td>           <td>yes</td><td>no</td><td>—</td></tr>
 *   <tr><td>ROUND_TRIANGLE</td>  <td>no</td><td>yes</td><td>round-triangle</td></tr>
 *   <tr><td>RECTANGLE</td>       <td>no</td><td>yes</td><td>rectangle</td></tr>
 *   <tr><td>ROUND_RECTANGLE</td> <td>no</td><td>yes</td><td>round-rectangle</td></tr>
 *   <tr><td>BOTTOM_ROUND_RECTANGLE</td><td>no</td><td>yes</td><td>bottom-round-rectangle</td></tr>
 *   <tr><td>CUT_RECTANGLE</td>   <td>no</td><td>yes</td><td>cut-rectangle</td></tr>
 *   <tr><td>BARREL</td>          <td>no</td><td>yes</td><td>barrel</td></tr>
 *   <tr><td>RHOMBOID</td>        <td>no</td><td>yes</td><td>rhomboid</td></tr>
 *   <tr><td>RIGHT_RHOMBOID</td>  <td>no</td><td>yes</td><td>right-rhomboid</td></tr>
 *   <tr><td>ROUND_DIAMOND</td>   <td>no</td><td>yes</td><td>round-diamond</td></tr>
 *   <tr><td>PENTAGON</td>        <td>no</td><td>yes</td><td>pentagon</td></tr>
 *   <tr><td>ROUND_PENTAGON</td>  <td>no</td><td>yes</td><td>round-pentagon</td></tr>
 *   <tr><td>HEXAGON</td>         <td>no</td><td>yes</td><td>hexagon</td></tr>
 *   <tr><td>ROUND_HEXAGON</td>   <td>no</td><td>yes</td><td>round-hexagon</td></tr>
 *   <tr><td>CONCAVE_HEXAGON</td> <td>no</td><td>yes</td><td>concave-hexagon</td></tr>
 *   <tr><td>HEPTAGON</td>        <td>no</td><td>yes</td><td>heptagon</td></tr>
 *   <tr><td>ROUND_HEPTAGON</td>  <td>no</td><td>yes</td><td>round-heptagon</td></tr>
 *   <tr><td>OCTAGON</td>         <td>no</td><td>yes</td><td>octagon</td></tr>
 *   <tr><td>ROUND_OCTAGON</td>   <td>no</td><td>yes</td><td>round-octagon</td></tr>
 *   <tr><td>TAG</td>             <td>no</td><td>yes</td><td>tag</td></tr>
 *   <tr><td>ROUND_TAG</td>       <td>no</td><td>yes</td><td>round-tag</td></tr>
 *   <tr><td>VEE</td>             <td>no</td><td>yes</td><td>vee</td></tr>
 *   <tr><td>POLYGON</td>         <td>no</td><td>yes (requires shape-polygon-points)</td><td>polygon</td></tr>
 * </table>
 *
 * <p>{@link #isSupportedByVisNetwork()} and {@link #isSupportedByCytoscape()}
 * let UIs (dialogs, combo boxes) filter the set so each engine only offers
 * the shapes it actually understands.</p>
 */
public enum Shape {

    /* ---- shapes supported by both engines (alphabetical) ---- */
    BOX                       (true,  true,  "box"),
    CIRCLE                    (true,  false, null),
    DATABASE                  (true,  false, null),
    DIAMOND                   (true,  true,  "diamond"),
    DOT                       (true,  false, null),
    ELLIPSE                   (true,  true,  "ellipse"),
    ICON                      (true,  false, null),
    IMAGE                     (true,  false, null),
    SQUARE                    (true,  false, null),
    STAR                      (true,  true,  "star"),
    TEXT                      (true,  false, null),
    TRIANGLE                  (true,  true,  "triangle"),

    /* ---- Cytoscape-only shapes (alphabetical) ---- */
    BARREL                    (false, true,  "barrel"),
    BOTTOM_ROUND_RECTANGLE    (false, true,  "bottom-round-rectangle"),
    CONCAVE_HEXAGON           (false, true,  "concave-hexagon"),
    CUT_RECTANGLE             (false, true,  "cut-rectangle"),
    HEPTAGON                  (false, true,  "heptagon"),
    HEXAGON                   (false, true,  "hexagon"),
    OCTAGON                   (false, true,  "octagon"),
    PENTAGON                  (false, true,  "pentagon"),
    POLYGON                   (false, true,  "polygon"),
    RECTANGLE                 (false, true,  "rectangle"),
    RHOMBOID                  (false, true,  "rhomboid"),
    RIGHT_RHOMBOID            (false, true,  "right-rhomboid"),
    ROUND_DIAMOND             (false, true,  "round-diamond"),
    ROUND_HEPTAGON            (false, true,  "round-heptagon"),
    ROUND_HEXAGON             (false, true,  "round-hexagon"),
    ROUND_OCTAGON             (false, true,  "round-octagon"),
    ROUND_PENTAGON            (false, true,  "round-pentagon"),
    ROUND_RECTANGLE           (false, true,  "round-rectangle"),
    ROUND_TAG                 (false, true,  "round-tag"),
    ROUND_TRIANGLE            (false, true,  "round-triangle"),
    TAG                       (false, true,  "tag"),
    VEE                       (false, true,  "vee");

    private final boolean supportedByVisNetwork;
    private final boolean supportedByCytoscape;
    /** Token vis-network expects. Null for Cytoscape-only shapes. */
    private final String visNetworkName;
    /** Token Cytoscape.js expects. Null for vis-network-only shapes. */
    private final String cytoscapeName;

    Shape(boolean supportedByVisNetwork, boolean supportedByCytoscape, String cytoscapeName) {
        this.supportedByVisNetwork = supportedByVisNetwork;
        this.supportedByCytoscape = supportedByCytoscape;
        this.visNetworkName = supportedByVisNetwork ? name().toLowerCase() : null;
        this.cytoscapeName = cytoscapeName;
    }

    public boolean isSupportedByVisNetwork() { return supportedByVisNetwork; }
    public boolean isSupportedByCytoscape() { return supportedByCytoscape; }

    /** Token expected by vis-network, or {@code null} if not supported. */
    public String visNetworkName() { return visNetworkName; }

    /** Token expected by Cytoscape.js, or {@code null} if not supported. */
    public String cytoscapeName() { return cytoscapeName; }

    /**
     * Returns the subset of values the given engine supports, preserving
     * declaration order so the UI combo entries are stable.
     */
    public static Shape[] valuesForVisNetwork() {
        Shape[] all = values();
        Shape[] out = new Shape[all.length];
        int n = 0;
        for (Shape s : all) {
            if (s.supportedByVisNetwork) out[n++] = s;
        }
        Shape[] r = new Shape[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    public static Shape[] valuesForCytoscape() {
        Shape[] all = values();
        Shape[] out = new Shape[all.length];
        int n = 0;
        for (Shape s : all) {
            if (s.supportedByCytoscape) out[n++] = s;
        }
        Shape[] r = new Shape[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }
}
