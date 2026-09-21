package de.tk.dependencyanalyse.rapui.visgraph.engine;

/**
 * Identifies the underlying rendering engine of a graph viewer.
 *
 * <p>Five engines are supported:</p>
 * <ul>
 *   <li>{@link #VIS_NETWORK} — vis-network (bundled as a WebJar / static asset),
 *       rendered by {@code de.tk.dependencyanalyse.rapui.visgraph.GraphViewer}.</li>
 *   <li>{@link #CYTOSCAPE} — Cytoscape.js + cytoscape-fcose (bundled as static assets),
 *       rendered by {@code de.tk.dependencyanalyse.rapui.visgraph.CytoscapeViewer}.</li>
 *   <li>{@link #SIGMA} — sigma.js + graphology (bundled as static assets under
 *       {@code /static/sigma/}). Renders via
 *       {@code de.tk.dependencyanalyse.rapui.visgraph.SigmaViewer}; the
 *       graph data is pushed from Java to the iframe via the Rap-JS
 *       bridge (gzip-compressed, base64-encoded payload — see
 *       {@code SigmaJsBridge.applyData}).</li>
 *   <li>{@link #NEO4J_NVL} — Neo4j NVL (bundled under {@code /static/nvl/}),
 *       rendered by {@code de.tk.dependencyanalyse.rapui.visgraph.Neo4jNvlViewer}.</li>
 *   <li>{@link #THREE_FORCE_GRAPH} — 3D Force-Directed Graph (Three.js +
 *       d3-force-3d + kapsule, bundled under {@code /static/three/}),
 *       rendered by
 *       {@code de.tk.dependencyanalyse.rapui.visgraph.ThreeForceGraphViewer}.
 *       Requires WebGL — falls back to an inline error message when the
 *       iframe's canvas context is not WebGL-capable.</li>
 * </ul>
 *
 * <p>Each viewer widget reports its engine via {@code getEngine()} so that
 * configuration UIs, logging, and metric collection can identify which
 * renderer is in use.</p>
 */
public enum GraphEngine {
    CYTOSCAPE,
    SIGMA,
    NEO4J_NVL,
    THREE_FORCE_GRAPH,
    VIS_NETWORK
}
