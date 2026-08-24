package de.tk.dependencyanalyse.rapui.visgraph.examples;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.tk.dependencyanalyse.rapui.visgraph.GraphConfigurationDialog;
import de.tk.dependencyanalyse.rapui.visgraph.GraphViewerControlBar;
import de.tk.dependencyanalyse.rapui.visgraph.SwitchingViewer;
import de.tk.dependencyanalyse.rapui.visgraph.api.SampleGraphController;
import de.tk.dependencyanalyse.rapui.visgraph.callback.NodeSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.RelationshipSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import de.tk.dependencyanalyse.rapui.visgraph.data.LayoutAlgorithm;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.application.AbstractEntryPoint;
import org.eclipse.rap.rwt.service.UISession;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default entry point that loads the bundled {@code export.csv} via the
 * {@code /api/sample-graph} REST endpoint, displays it with Cytoscape +
 * fcose (log-weighted edges), and exposes engine switching, layout
 * configuration, and the {@link GraphConfigurationDialog}.
 *
 * <p>Accessed at <code>http://localhost:8085/graph</code>.</p>
 */
public class CsvExampleEntryPoint extends AbstractEntryPoint {

    private static final Gson GSON = new Gson();

    private SwitchingViewer viewer;
    private Label statusLabel;

    @Override
    protected void createContents(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setText("Lade Beispielgraph ...");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        viewer = new SwitchingViewer(parent, SWT.NONE);
        viewer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.addNodeSelectionListener(new NodeSelectionListener() {
            @Override
            public void nodeSelected(GraphNode node) {
                System.out.println("Node selected: " + node);
            }
        });
        viewer.addRelationshipSelectionListener(new RelationshipSelectionListener() {
            @Override
            public void relationshipSelected(GraphRelationship relationship) {
                System.out.println("Relationship selected: " + relationship);
            }
        });
        GraphViewerControlBar bar = new GraphViewerControlBar(parent, viewer,
                () -> openGraphConfigurationDialog());

        // Load data asynchronously (sync to keep example simple).
        GraphData data = loadData();
        if (data == null) {
            statusLabel.setText("Fehler: Beispielgraph konnte nicht geladen werden.");
            return;
        }
        statusLabel.setText("Beispielgraph geladen: "
                + data.getNodes().size() + " Knoten, "
                + data.getRelationships().size() + " Kanten.");

        viewer.switchTo(GraphEngine.CYTOSCAPE);
        viewer.setGraphData(data);
        // Default to the Leiden Grid layout so the demo immediately
        // shows distinct community clusters. fcose is still selectable
        // via the toolbar but it collapses the Leiden grid.
        viewer.setLayout(LayoutAlgorithm.LEIDEN_GRID);
    }

    private void openGraphConfigurationDialog() {
        GraphConfigurationDialog dlg = new GraphConfigurationDialog(
                viewer.getShell(), viewer, viewer.getGraphData());
        dlg.open();
    }

    /**
     * Fetch the sample graph JSON from {@code /api/sample-graph} and
     * build a {@link GraphData} from it. Falls back to a local classpath
     * parse if the HTTP call fails (e.g. when running the RAP UI without
     * the REST endpoint ready).
     */
    private GraphData loadData() {
        try {
            JsonObject root = fetchSampleGraph();
            return parseJson(root);
        } catch (Exception e) {
            try {
                return loadFromClasspath();
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }
    }

    private JsonObject fetchSampleGraph() throws Exception {
        // Build a path relative to the current UI session. The server
        // (Jetty) routes any URL ending in /api/sample-graph to the
        // REST controller, regardless of the entry-point mount point.
        UISession ui = RWT.getUISession();
        String uiId = ui == null ? null : ui.getId();
        StringBuilder url = new StringBuilder();
        url.append("api/sample-graph");
        if (uiId != null) {
            url.append(";jsessionid=").append(uiId);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try (InputStream in = conn.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return GSON.fromJson(sb.toString(), JsonObject.class);
        }
    }

    private GraphData parseJson(JsonObject root) {
        JsonArray elements = root.getAsJsonArray("elements");
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphRelationship> rels = new ArrayList<>();
        int edgeSeq = 0;
        for (JsonElement el : elements) {
            JsonObject data = el.getAsJsonObject().getAsJsonObject("data");
            if (data == null) continue;
            if (data.has("source")) {
                // Edge.
                String id = optString(data, "id", "e" + (++edgeSeq));
                String source = optString(data, "source", null);
                String target = optString(data, "target", null);
                String type = optString(data, "type", "REL");
                if (source == null || target == null) continue;
                Map<String, Object> props = jsonProps(data);
                rels.add(new GraphRelationship(id, type, source, target, props));
            } else {
                // Node.
                String id = optString(data, "id", null);
                if (id == null) continue;
                List<String> labels = new ArrayList<>();
                if (data.has("nodeType")) labels.add(data.get("nodeType").getAsString());
                else labels.add("Node");
                Map<String, Object> props = jsonProps(data);
                nodes.put(id, new GraphNode(id, labels, props));
            }
        }
        return new GraphData(new ArrayList<>(nodes.values()), rels);
    }

    private static String optString(JsonObject o, String key, String fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsString(); } catch (Exception e) { return fallback; }
    }

    private static Map<String, Object> jsonProps(JsonObject o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            if (k.equals("id") || k.equals("source") || k.equals("target") || k.equals("type") || k.equals("label") || k.equals("nodeType")) continue;
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            if (v.isJsonPrimitive()) {
                JsonPrimitive prim = v.getAsJsonPrimitive();
                if (prim.isNumber()) out.put(k, prim.getAsDouble());
                else if (prim.isBoolean()) out.put(k, prim.getAsBoolean());
                else out.put(k, prim.getAsString());
            } else {
                out.put(k, v.toString());
            }
        }
        return out;
    }

    /**
     * Synchronous classpath fallback. Reads {@code /sample/export.csv}
     * directly so the example works without the REST endpoint.
     */
    private GraphData loadFromClasspath() throws Exception {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphRelationship> rels = new ArrayList<>();
        int edgeSeq = 0;
        try (InputStream in = CsvExampleEntryPoint.class.getResourceAsStream(SampleGraphController.CSV_RESOURCE);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (first) {
                    first = false;
                    if (parts.length >= 1 && parts[0].trim().equalsIgnoreCase("Source")) continue;
                }
                if (parts.length < 2) continue;
                String src = parts[0].trim();
                String tgt = parts[1].trim();
                double weight = 1.0;
                if (parts.length >= 3) {
                    try { weight = Double.parseDouble(parts[2].trim()); }
                    catch (NumberFormatException ignored) {}
                }
                if (src.isEmpty() || tgt.isEmpty()) continue;
                nodes.computeIfAbsent(src, id -> new GraphNode(id, List.of("Node"),
                        Map.of("name", id, "nodeTag", "entity")));
                nodes.computeIfAbsent(tgt, id -> new GraphNode(id, List.of("Node"),
                        Map.of("name", id, "nodeTag", "entity")));
                edgeSeq++;
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("weight", weight);
                rels.add(new GraphRelationship("e" + edgeSeq, "REL", src, tgt, props));
            }
        }
        return new GraphData(new ArrayList<>(nodes.values()), rels);
    }
}
