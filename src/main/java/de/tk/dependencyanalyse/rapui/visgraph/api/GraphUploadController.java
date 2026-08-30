package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import de.tk.dependencyanalyse.rapui.visgraph.io.GraphFileParser;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint that accepts a {@code csv} or {@code gml} file as
 * multipart upload and returns a cytoscape-shaped elements document.
 *
 * <p>Returns the same JSON shape as {@code /api/sample-graph} so the
 * existing client code (cytoscape-viewer.js) can consume the result
 * without further changes:</p>
 * <pre>{@code
 * {
 *   "elements": [...],
 *   "cytoscapeLayoutOptions": { "name": "preset", "fit": false },
 *   "fcoseLayoutOptions": {...},
 *   "leidenColors": {...},
 *   "stats": { "nodes": N, "edges": M }
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api")
public class GraphUploadController {

    private static final Gson GSON = new Gson();

    @PostMapping(value = "/load-graph", produces = MediaType.APPLICATION_JSON_VALUE)
    public String loadGraph(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required and must not be empty");
        }
        GraphFileParser.Format format = GraphFileParser.detectFormat(file.getOriginalFilename());
        GraphData data;
        try (InputStream in = file.getInputStream()) {
            data = GraphFileParser.parse(in, format);
        }
        return GSON.toJson(toJson(data, format.name()));
    }

    private JsonObject toJson(GraphData data, String formatLabel) {
        JsonObject root = new JsonObject();
        JsonArray elements = new JsonArray();
        double minWeight = Double.POSITIVE_INFINITY;
        double maxWeight = Double.NEGATIVE_INFINITY;

        for (GraphNode n : data.getNodes()) elements.add(GSON.toJsonTree(n.toCytoscapeNode()));
        for (GraphRelationship r : data.getRelationships()) {
            elements.add(GSON.toJsonTree(r.toCytoscapeEdge()));
            Object w = r.getProperties().get(GraphRelationship.PROP_WEIGHT);
            if (w instanceof Number num) {
                double v = num.doubleValue();
                if (v < minWeight) minWeight = v;
                if (v > maxWeight) maxWeight = v;
            }
        }
        if (minWeight == Double.POSITIVE_INFINITY) minWeight = 1;
        if (maxWeight == Double.NEGATIVE_INFINITY) maxWeight = 1;

        // Same preset + fcose options as the sample-graph endpoint so the JS
        // bridge picks them up uniformly.
        JsonObject preset = new JsonObject();
        preset.addProperty("name", "preset");
        preset.addProperty("fit", false);

        JsonObject fcose = new JsonObject();
        fcose.addProperty("name", "fcose");
        fcose.addProperty("randomize", false);
        fcose.addProperty("nodeRepulsion", 12000);
        fcose.addProperty("idealEdgeLength",
                "function(e){var lw=e.data('logWeight');return 350/(1+(typeof lw==='number'?lw:0));}");
        fcose.addProperty("edgeElasticity", 0.45);
        fcose.addProperty("nestingFactor", 0.1);
        fcose.addProperty("gravity", 0.05);
        fcose.addProperty("numIter", 2500);
        fcose.addProperty("tile", true);
        fcose.addProperty("animate", false);

        root.add("elements", elements);
        root.add("cytoscapeLayoutOptions", preset);
        root.add("fcoseLayoutOptions", fcose);

        // Community colors — best-effort Leiden clustering; if the graph is
        // too small the helper returns an empty map and we just don't add the key.
        try {
            Map<String, String> communityColors = de.tk.dependencyanalyse.rapui.visgraph.LeidenColors.compute(data);
            JsonObject colors = new JsonObject();
            for (var e : communityColors.entrySet()) colors.addProperty(e.getKey(), e.getValue());
            root.add("leidenColors", colors);
        } catch (Exception ignored) {
            // Not having community colors is non-fatal — the JS bridge handles the empty case.
        }

        JsonObject stats = new JsonObject();
        stats.addProperty("nodes", data.getNodes().size());
        stats.addProperty("edges", data.getRelationships().size());
        stats.addProperty("minWeight", minWeight);
        stats.addProperty("maxWeight", maxWeight);
        stats.addProperty("format", formatLabel);
        root.add("stats", stats);
        return root;
    }
}
