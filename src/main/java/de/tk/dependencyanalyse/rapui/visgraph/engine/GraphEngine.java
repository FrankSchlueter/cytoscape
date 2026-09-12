package de.tk.dependencyanalyse.rapui.visgraph.engine;

/**
 * Identifies the underlying rendering engine of a graph viewer.
 *
 * <p>Three engines are supported:</p>
 * <ul>
 *   <li>{@link #VIS_NETWORK} — vis-network (bundled as a WebJar / static asset),
 *       rendered by {@code de.tk.dependencyanalyse.rapui.visgraph.GraphViewer}.</li>
 *   <li>{@link #CYTOSCAPE} — Cytoscape.js + cytoscape-fcose (bundled as static assets),
 *       rendered by {@code de.tk.dependencyanalyse.rapui.visgraph.CytoscapeViewer}.</li>
 *   <li>{@link #SIGMA} — sigma.js + graphology (bundled as static assets under
 *       {@code /static/sigma/}). Renders via
 *       {@code de.tk.dependencyanalyse.rapui.visgraph.SigmaViewer}; the actual
 *       graph data is fetched from the {@code /api/sigma/nodes} and
 *       {@code /api/sigma/edges} REST endpoints instead of being pushed via
 *       {@code BrowserFunction} so large payloads can be compressed and
 *       streamed efficiently.</li>
 * </ul>
 *
 * <p>Each viewer widget reports its engine via {@code getEngine()} so that
 * configuration UIs, logging, and metric collection can identify which
 * renderer is in use.</p>
 */
public enum GraphEngine {
    VIS_NETWORK,
    CYTOSCAPE,
    SIGMA
}
