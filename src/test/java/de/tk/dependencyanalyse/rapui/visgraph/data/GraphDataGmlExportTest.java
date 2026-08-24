package de.tk.dependencyanalyse.rapui.visgraph.data;

import de.tk.dependencyanalyse.rapui.visgraph.io.GraphFileParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphDataGmlExportTest {

    @Test
    void emptyGraphProducesMinimalGml() {
        GraphData data = GraphData.empty();
        String gml = data.exportToGml();
        assertTrue(gml.startsWith("Creator"));
        assertTrue(gml.contains("graph"));
        assertTrue(gml.contains("["));
        assertTrue(gml.contains("]"));
    }

    @Test
    void exportedGmlContainsNodeAndEdgeHeaders() {
        GraphNode a = new GraphNode("n1", List.of("Entity"), Map.of("name", "n1", "color", "red"));
        GraphNode b = new GraphNode("n2", List.of("Entity"), Map.of("name", "n2"));
        GraphRelationship e1 = new GraphRelationship("e1", "REL", "n1", "n2",
                Map.of(GraphRelationship.PROP_WEIGHT, 4.0));
        GraphData data = new GraphData(List.of(a, b), List.of(e1));

        String gml = data.exportToGml();
        assertTrue(gml.contains("node"));
        assertTrue(gml.contains("edge"));
        // Export quotes all string values (defensive against whitespace /
        // special characters in node ids); the GML parser accepts both
        // bare idents and "..."-quoted strings.
        assertTrue(gml.contains("source \"n1\""), gml);
        assertTrue(gml.contains("target \"n2\""), gml);
        // Numeric values are emitted bare (no quotes)
        assertTrue(gml.contains("value 4.0"), gml);
    }

    @Test
    void exportRoundtripsThroughGraphFileParser() throws Exception {
        // Build a graph by hand, export it to GML, and re-parse the result.
        // The re-parsed graph must contain the same nodes and edges.
        GraphNode n1 = new GraphNode("alpha", List.of("Hub"),
                Map.of("name", "Alpha", "country", "DE", "population", 8000));
        GraphNode n2 = new GraphNode("beta", List.of("Spoke"),
                Map.of("name", "Beta", "country", "FR", "population", 4000));
        GraphRelationship r1 = new GraphRelationship("r1", "REL", "alpha", "beta",
                Map.of(GraphRelationship.PROP_WEIGHT, 2.5, "label", "trade"));
        GraphData original = new GraphData(List.of(n1, n2), List.of(r1));

        String gml = original.exportToGml();

        GraphData reparsed = GraphFileParser.parse(
                new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.GML);

        assertEquals(2, reparsed.getNodes().size());
        assertEquals(1, reparsed.getRelationships().size());
        GraphNode reparsedAlpha = reparsed.findNode("alpha").orElseThrow();
        assertEquals("Hub", reparsedAlpha.getLabels().get(0));
        assertEquals("Alpha",  reparsedAlpha.getProperties().get("name"));
        assertEquals("DE",     reparsedAlpha.getProperties().get("country"));
        assertEquals(8000.0,   reparsedAlpha.getProperties().get("population"));

        GraphRelationship rel = reparsed.getRelationships().get(0);
        assertEquals("alpha", rel.getSourceId());
        assertEquals("beta",  rel.getTargetId());
        assertEquals(2.5, rel.getProperties().get(GraphRelationship.PROP_WEIGHT));
        assertEquals("trade", rel.getProperties().get("label"));
    }

    @Test
    void numericPropertiesAreEmittedBare() {
        GraphNode n = new GraphNode("n", List.of("X"), Map.of("score", 3.14, "flag", true));
        GraphData d = new GraphData(List.of(n), List.of());
        String gml = d.exportToGml();
        // Number → unquoted numeric
        assertTrue(gml.contains("score 3.14"));
        // Boolean → bare ident
        assertTrue(gml.contains("flag true"));
    }

    @Test
    void stringsWithQuotesAreEscaped() {
        GraphNode n = new GraphNode("n", List.of("X"), Map.of("desc", "she said \"hi\""));
        GraphData d = new GraphData(List.of(n), List.of());
        String gml = d.exportToGml();
        assertTrue(gml.contains("desc \"she said \\\"hi\\\"\""), gml);
    }

    @Test
    void exportIsReparseableForTheRealSampleGraph() throws Exception {
        // The project's bundled export.csv is loaded via the CSV path; build a
        // GraphData and verify the GML round-trip is lossless on node + edge
        // counts. Property values may lose precision in the String<->Double
        // path so we don't assert exact weight equality.
        java.nio.file.Path p = java.nio.file.Paths.get("target/classes/sample/export.csv");
        if (!java.nio.file.Files.exists(p)) {
            System.err.println("export.csv not on classpath; skipping");
            return;
        }
        GraphData data;
        try (var in = java.nio.file.Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.CSV);
        }
        String gml = data.exportToGml();
        GraphData back;
        try (var in = new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8))) {
            back = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }
        assertEquals(data.getNodes().size(), back.getNodes().size());
        assertEquals(data.getRelationships().size(), back.getRelationships().size());
    }
}
