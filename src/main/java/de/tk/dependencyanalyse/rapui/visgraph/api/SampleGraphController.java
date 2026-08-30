package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.LeidenColors;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller that exposes the bundled sample CSV graph
 * ({@code /static/sample/export.csv}) as a JSON document consumable by
 * the RAP entry points.
 *
 * <p>The endpoint returns a JSON document with the shape:</p>
 * <pre>{@code
 * {
 *   "elements": [ { "data": { "id": "...", "label": "...", ... } }, ... ],
 *   "cytoscapeLayoutOptions": { "name": "fcose", "idealEdgeLength": <function-as-string>, ... },
 *   "stats": { "nodes": <int>, "edges": <int>, "minWeight": <int>, "maxWeight": <int> }
 * }
 * }</pre>
 *
 * <p>The {@code idealEdgeLength} is computed as a JS function string
 * {@code function(e){ var w = e.data('weight') || 1; return 50 + 30 * Math.log(w); }}
 * which fcose accepts directly.</p>
 */
@RestController
@RequestMapping("/api")
public class SampleGraphController {

    public static final String CSV_RESOURCE = "/sample/export.csv";
    //public static final String CSV_RESOURCE = "/sample/cosetest1.csv";
    private static final Gson GSON = new Gson();

    @GetMapping(value = "/sample-graph", produces = MediaType.APPLICATION_JSON_VALUE)
    public String sampleGraph() throws Exception {
        GraphData data = loadSampleCsv();
        return GSON.toJson(toJson(data));
    }

    /**
     * Returns the Leiden-community color map for the current sample CSV.
     * Used by the cytoscape-viewer auto-load fallback (and by any client
     * that wants to apply community colors without a full RAP entry-point
     * dialog round-trip).
     */
    @GetMapping(value = "/leiden-colors", produces = MediaType.APPLICATION_JSON_VALUE)
    public String leidenColors() throws Exception {
        GraphData data = loadSampleCsv();
        Map<String, String> colors = LeidenColors.compute(data);
        JsonObject root = new JsonObject();
        JsonObject colorsObj = new JsonObject();
        for (var e : colors.entrySet()) {
            colorsObj.addProperty(e.getKey(), e.getValue());
        }
        root.add("colors", colorsObj);
        root.addProperty("communities", colors.values().stream().distinct().count());
        root.addProperty("nodes", colors.size());
        return GSON.toJson(root);
    }

    /**
     * Parse the bundled {@code export.csv} into a {@link GraphData} instance.
     * Lines with a missing or unparseable weight are skipped silently.
     *
     * <p>The raw weight read from the CSV is stored as-is in
     * {@code properties["weight"]}. The Cytoscape serialization additionally
     * surfaces {@code logWeight} on each edge so fcose's
     * {@code idealEdgeLength} can use a logarithmically-scaled edge length
     * without doing the math in the browser.</p>
     */
    private GraphData loadSampleCsv() throws Exception {
        Map<String, GraphNode> nodeIndex = new LinkedHashMap<>();
        List<GraphRelationship> rels = new ArrayList<>();
        int edgeSeq = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource(CSV_RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                // Strip UTF-8 BOM if present (the first line might start with ﻿).
                if (first) {
                    first = false;
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                    if (line.length() >= 1 && line.split(",", 2)[0].trim().equalsIgnoreCase("Source")) {
                        continue;
                    }
                }
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length < 2) continue;
                String src = parts[0].trim();
                String tgt = parts[1].trim();
                double weight = 1.0;
                boolean hasWeight = false;
                if (parts.length >= 3) {
                    try {
                        weight = Double.parseDouble(parts[2].trim());
                        hasWeight = true;
                    }
                    catch (NumberFormatException ignored) {}
                }
                if (src.isEmpty() || tgt.isEmpty()) continue;

                GraphNode s = nodeIndex.computeIfAbsent(src, id ->
                        new GraphNode(id, List.of("Node"),
                                Map.of("name", id, "nodeTag", "entity")));
                GraphNode t = nodeIndex.computeIfAbsent(tgt, id ->
                        new GraphNode(id, List.of("Node"),
                                Map.of("name", id, "nodeTag", "entity")));
                edgeSeq++;
                String relId = "e" + edgeSeq;
                Map<String, Object> props = new LinkedHashMap<>();
                if (hasWeight) {
                    // Store the raw weight; the Cytoscape serializer will
                    // surface a pre-computed logWeight next to it.
                    props.put(GraphRelationship.PROP_WEIGHT, weight);
                }
                rels.add(new GraphRelationship(relId, "REL", s, t, props));
            }
        }
        return new GraphData(new ArrayList<>(nodeIndex.values()), rels);
    }

private JsonObject toJson(GraphData data) {
        JsonObject root = new JsonObject();

        // Elements array (cytoscape shape).
        JsonArray elements = new JsonArray();
        double minWeight = Double.POSITIVE_INFINITY;
        double maxWeight = Double.NEGATIVE_INFINITY;
        for (GraphNode n : data.getNodes()) {
            elements.add(GSON.toJsonTree(n.toCytoscapeNode()));
        }
        for (GraphRelationship r : data.getRelationships()) {
            elements.add(GSON.toJsonTree(r.toCytoscapeEdge()));
            Object w = r.getProperties().get("weight");
            if (w instanceof Number num) {
                double v = num.doubleValue();
                if (v < minWeight) minWeight = v;
                if (v > maxWeight) maxWeight = v;
            }
        }
        if (minWeight == Double.POSITIVE_INFINITY) minWeight = 1;
        if (maxWeight == Double.NEGATIVE_INFINITY) maxWeight = 1;

        // Cytoscape fcose layout options.
//
// Semantics: {@code weight} is interpreted as the *attraction strength*
// between two connected nodes — higher weight ⇒ the two endpoints should
// be pulled closer together in the layout (shorter edge). The
// {@code idealEdgeLength} formula scales inversely with the per-edge
// {@code logWeight}: edges with strong weights get shorter ideal
//   lengths, edges with weak weights get longer ones.
//
//   weight   logWeight   idealEdgeLength
//   ------   ---------   ----------------
//   1        0.693       500 px
//   10       2.398       167 px
//   100      4.615        83 px
//   1000     6.909        50 px
//   10000    9.210        33 px
//
// Formula: 500 / (1 + 2 * logWeight)
//
// fcose parameters tuned so that the *weighted spring length* shapes the
// intra-community layout while gravity holds the Leiden-community grid
// together (the JS side pre-positions each community in a grid cell, so
// the canvas is already roughly organised by community):
//
//   • randomize = false — the JS bridge pre-positions nodes from the
//     Leiden community grid (community centers spread across the canvas,
//     nodes within a community clustered). fcose preserves those
//     positions and only fine-tunes them with the spring + repulsion
//     forces, so high-weight edges pull intra-community nodes closer
//     while the gravity keeps each community centered in its cell.
//   • nodeRepulsion = 50 — extremely low. The spring force dominates
//     intra-community layout.
//   • gravity = 0.4 — pulls each node back toward its initial position.
//     With gravity, fcose keeps the community grid intact even when
//     repulsion is low.
//   • edgeElasticity = 0.45 (fcose default).
//   • nestingFactor = 0.1 (fcose default for non-compound graphs).
// Cytoscape layout options.
//
// Two layouts are provided: the Leiden-community grid (default,
// `preset` + JS-side preseed) and a fcose-based force-directed view.
//
// `preset` is used by default. The JS bridge pre-positions each
// Leiden community in a 5×4 grid (see preseedCommunityPositions
// in cytoscape-viewer.js) so the canvas is already organised by
// community when `preset` runs. `fit=false` so the JS-side cy.fit()
// can use a nodes-only bounding box.
//
// `fcose` is selectable from the toolbar. It runs from the Leiden
// preseed positions with:
//   • nodeRepulsion = 12000 — keeps nodes well-separated so the
//     graph doesn't collapse into a single dense blob.
//   • idealEdgeLength = 350 / (1 + logWeight) — strong-weight edges
//     pull their endpoints closer; weak-weight edges let them spread.
//   • gravity = 0.05 — very weak, lets nodes spread by spring + repulsion.
//   • edgeElasticity = 0.45 (fcose default).
//   • numIter = 2500 (was 3000 — converges in ~1.5s on 150 nodes).
JsonObject layoutOpts = new JsonObject();
layoutOpts.addProperty("name", "preset");
layoutOpts.addProperty("fit", false);

// fcose variant is exposed under a separate key so the cytoscape
// viewer can switch to it via cgv_setLayout('FCOSE').
JsonObject fcoseOpts = new JsonObject();
fcoseOpts.addProperty("name", "fcose");
fcoseOpts.addProperty("randomize", false);
fcoseOpts.addProperty("nodeRepulsion", 12000);
fcoseOpts.addProperty("idealEdgeLength",
    "function(e){var lw=e.data('logWeight');return 350/(1+(typeof lw==='number'?lw:0));}");
fcoseOpts.addProperty("edgeElasticity", 0.45);
fcoseOpts.addProperty("nestingFactor", 0.1);
fcoseOpts.addProperty("gravity", 0.05);
fcoseOpts.addProperty("numIter", 2500);
fcoseOpts.addProperty("tile", true);
fcoseOpts.addProperty("animate", false);

// Cola (cytoscape.js-cola 1.6.0 + bundled WebCola from 2016) variant —
// constraint-based layout. We deliberately keep avoidOverlap +
// handleDisconnected OFF and cap maxSimulationTime at 2000 ms: the
// 2016-era WebCola build has known stack-overflow paths in its
// overlap-separation code on larger / densely-connected graphs, so
// the default is tuned to stay within the JS stack budget. Callers can
// dial the simulation up via cgv_setLayoutOptions() when they know
// the graph fits.
JsonObject colaOpts = new JsonObject();
colaOpts.addProperty("name", "cola");
colaOpts.addProperty("randomize", false);
colaOpts.addProperty("avoidOverlap", false);
colaOpts.addProperty("nodeSpacing", 10);
colaOpts.addProperty("edgeLength", 80);
colaOpts.addProperty("maxSimulationTime", 2000);
colaOpts.addProperty("handleDisconnected", false);
colaOpts.addProperty("ungrabifyWhileSimulating", false);
colaOpts.addProperty("refresh", 1);
colaOpts.addProperty("animate", false);

        root.add("elements", elements);
        root.add("cytoscapeLayoutOptions", layoutOpts);
        root.add("fcoseLayoutOptions", fcoseOpts);
        root.add("colaLayoutOptions", colaOpts);

        // Leiden community colors — applied automatically by the cytoscape
        // viewer when fetched together with the elements (so the demo
        // shows community structure out of the box).
        Map<String, String> communityColors = LeidenColors.compute(data);
        JsonObject colorsObj = new JsonObject();
        for (var e : communityColors.entrySet()) {
            colorsObj.addProperty(e.getKey(), e.getValue());
        }
        root.add("leidenColors", colorsObj);

        JsonObject stats = new JsonObject();
        stats.addProperty("nodes", data.getNodes().size());
        stats.addProperty("edges", data.getRelationships().size());
        stats.addProperty("minWeight", minWeight);
        stats.addProperty("maxWeight", maxWeight);
        stats.addProperty("communities", communityColors.values().stream().distinct().count());
        root.add("stats", stats);
        return root;
    }
}
