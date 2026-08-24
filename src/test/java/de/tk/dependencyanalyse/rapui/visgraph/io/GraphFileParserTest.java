package de.tk.dependencyanalyse.rapui.visgraph.io;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GraphFileParserTest {

    @Test
    void parsesCsvWithHeaderAndWeights() throws Exception {
        String csv = "Source,Target,Weight\nA,B,2\nB,C,4\nC,A\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.CSV);
        assertEquals(3, data.getNodes().size());
        assertEquals(3, data.getRelationships().size());
        Optional<GraphRelationship> ab = data.findRelationship("e1");
        assertTrue(ab.isPresent());
        assertEquals("A", ab.get().getSourceId());
        assertEquals("B", ab.get().getTargetId());
        assertEquals(2.0, (Double) ab.get().getProperties().get(GraphRelationship.PROP_WEIGHT), 0.0001);
    }

    @Test
    void detectsFormatByExtension() {
        assertEquals(GraphFileParser.Format.GML, GraphFileParser.detectFormat("foo.gml"));
        assertEquals(GraphFileParser.Format.GML, GraphFileParser.detectFormat("foo.GML"));
        assertEquals(GraphFileParser.Format.CSV, GraphFileParser.detectFormat("a/b/c.csv"));
        assertEquals(GraphFileParser.Format.CSV, GraphFileParser.detectFormat(null));
    }

    @Test
    void parsesMinimalGml() throws Exception {
        String gml = ""
                + "graph [\n"
                + "  node [ id 1 label \"Alpha\" ]\n"
                + "  node [ id 2 label \"Beta\" ]\n"
                + "  edge [ source 1 target 2 weight 3.5 ]\n"
                + "]\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.GML);
        assertEquals(2, data.getNodes().size());
        assertEquals(1, data.getRelationships().size());
        GraphRelationship e = data.getRelationships().get(0);
        assertEquals("1", e.getSourceId());
        assertEquals("2", e.getTargetId());
        assertEquals(3.5, (Double) e.getProperties().get(GraphRelationship.PROP_WEIGHT), 0.0001);
    }

    @Test
    void skipsBlankAndShortCsvLines() throws Exception {
        String csv = "Source,Target\n\nA,B\nA\nB,C,3\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.CSV);
        assertEquals(2, data.getRelationships().size());
    }

    @Test
    void gmlNodeGetsAllPropertiesIncludingIdAndLabel() throws Exception {
        String gml = ""
                + "graph [\n"
                + "  node [ id 42 label \"Hub\" country \"DE\" population 8000 ]\n"
                + "]\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.GML);
        assertEquals(1, data.getNodes().size());
        GraphNode n = data.getNodes().get(0);
        assertEquals("42", n.getId());
        // Every GML key/value becomes a property.
        assertEquals("Hub",     n.getProperties().get("label"));
        assertEquals("DE",      n.getProperties().get("country"));
        assertEquals(8000.0,    n.getProperties().get("population"));
        // name defaults to label.
        assertEquals("Hub",     n.getProperties().get("name"));
    }

    @Test
    void gmlEdgeGetsAllPropertiesExceptSourceAndTarget() throws Exception {
        String gml = ""
                + "graph [\n"
                + "  edge [ source 1 target 2 weight 5 label \"connect\" speed \"fast\" ]\n"
                + "]\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.GML);
        assertEquals(1, data.getRelationships().size());
        GraphRelationship e = data.getRelationships().get(0);
        assertEquals("1", e.getSourceId());
        assertEquals("2", e.getTargetId());
        // source / target are NOT properties (they are first-class fields).
        assertFalse(e.getProperties().containsKey("source"));
        assertFalse(e.getProperties().containsKey("target"));
        // Every other key/value is a property.
        assertEquals(5.0,                   e.getProperties().get(GraphRelationship.PROP_WEIGHT));
        assertEquals("connect",             e.getProperties().get("label"));
        assertEquals("fast",                e.getProperties().get("speed"));
    }

    @Test
    void gmlValueIsPromotedToWeight() throws Exception {
        // Some GML dialects (e.g. Gephi exports) use "value" instead of "weight".
        String gml = ""
                + "graph [\n"
                + "  edge [ source 1 target 2 value 7 ]\n"
                + "]\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.GML);
        GraphRelationship e = data.getRelationships().get(0);
        assertEquals(7.0, e.getProperties().get(GraphRelationship.PROP_WEIGHT));
        // "value" stays in the bag too — we don't drop it.
        assertEquals(7.0, e.getProperties().get("value"));
    }

    @Test
    void gmlNodeWithGraphicsKeepsNestedBlockAsStringProperty() throws Exception {
        String gml = ""
                + "graph [\n"
                + "  node [ id 1 label \"x\" graphics [ x 0 y 0 ] ]\n"
                + "]\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.GML);
        GraphNode n = data.getNodes().get(0);
        Object graphics = n.getProperties().get("graphics");
        assertNotNull(graphics, "graphics block should be captured");
        assertInstanceOf(String.class, graphics);
        assertTrue(((String) graphics).contains("["));
        assertTrue(((String) graphics).contains("]"));
    }

    @Test
    void gmlEdgeAutoCreatesNodesFromSourceAndTarget() throws Exception {
        String gml = ""
                + "graph [\n"
                + "  edge [ source 99 target 100 ]\n"
                + "]\n";
        GraphData data = GraphFileParser.parse(
                new ByteArrayInputStream(gml.getBytes(StandardCharsets.UTF_8)),
                GraphFileParser.Format.GML);
        assertEquals(2, data.getNodes().size());
        assertNotNull(data.findNode("99").orElse(null));
        assertNotNull(data.findNode("100").orElse(null));
    }
}

