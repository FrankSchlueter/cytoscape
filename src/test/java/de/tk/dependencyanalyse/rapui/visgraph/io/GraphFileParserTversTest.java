package de.tk.dependencyanalyse.rapui.visgraph.io;

import de.tk.dependencyanalyse.rapui.visgraph.GraphConfigurationDialog;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-tests against the real TVERS-Usage.gml sample. This is the dataset
 * the user reported: it carries an explicit {@code _nodeType_} property on
 * every node that the dialog must surface as the Node-Type key.
 */
class GraphFileParserTversTest {

    @Test
    void tversUsageGmlParsesWithNodeTypeProperty() throws Exception {
        Path p = samplePath("TVERS-Usage.gml");
        if (p == null) {
            System.err.println("TVERS-Usage.gml not on classpath; skipping");
            return;
        }
        GraphData data;
        try (InputStream in = Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }
        assertTrue(data.getNodes().size() > 0, "expected > 0 nodes");
        // The _nodeType_ property must land on every GraphNode as a regular
        // property, NOT as a label. (Per the GML parser contract.)
        GraphNode first = data.getNodes().get(0);
        assertNotNull(first.getProperties().get("_nodeType_"),
                "_nodeType_ must be a property of the node, not a label");
    }

    @Test
    void discoveryFindsTversNodeTypesFromProperty() throws Exception {
        Path p = samplePath("TVERS-Usage.gml");
        if (p == null) {
            System.err.println("TVERS-Usage.gml not on classpath; skipping");
            return;
        }
        GraphData data;
        try (InputStream in = Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }
        System.out.println("[discoveryFindsTversNodeTypesFromProperty] nodes=" + data.getNodes().size()
                + " relationships=" + data.getRelationships().size());
        // Dump the first 5 nodes so we can see what made it through.
        for (int i = 0; i < Math.min(5, data.getNodes().size()); i++) {
            GraphNode n = data.getNodes().get(i);
            Object nt = n.getProperties().get("_nodeType_");
            System.out.println("    node[" + i + "] id=" + n.getId()
                    + " labels=" + n.getLabels()
                    + " _nodeType_=" + nt);
        }
        List<String> types = GraphConfigurationDialog.Discovery.publicNodeTypeValues(data);
        System.out.println("[discoveryFindsTversNodeTypesFromProperty] TVERS-Usage.gml discovered _nodeType_ values:");
        for (String t : types) System.out.println("    - " + t);
        // Per the user's example data set, the discovery must surface the
        // 7 distinct node-type values carried in _nodeType_ across the file.
        for (String required : new String[] {
                "Class", "BatchReader", "BatchWriter", "TableInfo",
                "ImplementationClass", "TKController", "TKEntity" }) {
            assertTrue(types.contains(required),
                    "discovery must surface " + required + ", got " + types);
        }
        assertEquals(7, types.size(),
                "TVERS-Usage.gml should produce exactly 7 distinct _nodeType_ values");
        assertEquals(List.copyOf(types).stream().sorted().toList(), types,
                "discovery result must be sorted");
    }

    @Test
    void discoveryFallsBackToLabelsWhenNodeTypePropertyAbsent() {
        // Hand-built graph without the _nodeType_ property: the discovery
        // must fall back to GraphNode.getLabels().get(0).
        GraphNode n1 = new GraphNode("a", List.of("Hub"),
                Map.of("name", "a"));
        GraphNode n2 = new GraphNode("b", List.of("Spoke"),
                Map.of("name", "b"));
        GraphNode n3 = new GraphNode("c", List.of("Hub"),
                Map.of("name", "c"));
        GraphData data = new GraphData(List.of(n1, n2, n3), List.of());

        List<String> types = GraphConfigurationDialog.Discovery.publicNodeTypeValues(data);
        assertEquals(List.of("Hub", "Spoke"), types);
    }

    @Test
    void nodeTypeIsPromotedIntoCytoscapeNodeTypeField() throws Exception {
        Path p = samplePath("TVERS-Usage.gml");
        if (p == null) {
            System.err.println("TVERS-Usage.gml not on classpath; skipping");
            return;
        }
        GraphData data;
        try (InputStream in = Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }
        // Every Cytoscape elements-data entry must have a `nodeType` field
        // matching the node's _nodeType_ property (NOT the GML label).
        for (GraphNode n : data.getNodes()) {
            Map<String, Object> ele = n.toCytoscapeNode();
            @SuppressWarnings("unchecked")
            Map<String, Object> eleData = (Map<String, Object>) ele.get("data");
            assertNotNull(eleData, "toCytoscapeNode must wrap a 'data' object");
            Object expected = n.getProperties().get("_nodeType_");
            assertNotNull(expected,
                    "_nodeType_ must be present in the node's properties");
            assertEquals(String.valueOf(expected), eleData.get("nodeType"),
                    "Cytoscape data.nodeType must mirror _nodeType_ so style "
                            + "selectors like node[nodeType='Class'] match");
        }
    }

    private static Path samplePath(String name) {
        String[] tries = {
                "target/classes/sample/" + name,
                "src/main/resources/sample/" + name,
        };
        for (String t : tries) {
            Path p = Paths.get(t);
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
