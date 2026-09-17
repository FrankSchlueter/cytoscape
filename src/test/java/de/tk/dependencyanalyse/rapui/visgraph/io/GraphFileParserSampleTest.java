package de.tk.dependencyanalyse.rapui.visgraph.io;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/** Smoke-tests against the real GML samples shipped under {@code sample/}. */
class GraphFileParserSampleTest {

    @Test
    void parsesLesMiserablesGml() throws Exception {
        Path p = samplePath("lesmiserables.gml");
        if (p == null) {
            System.err.println("lesmiserables.gml not on classpath; skipping");
            return;
        }
        try (InputStream in = Files.newInputStream(p)) {
            GraphData data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
            assertEquals(77, data.getNodes().size(),  "expected 77 nodes");
            assertEquals(254, data.getRelationships().size(), "expected 254 edges");

            // Spot-check that a node kept its GML attributes: the first node
            // "Myriel" is id 0 in the source file.
            GraphNode myriel = data.findNode("0").orElseThrow();
            assertEquals("Myriel", myriel.getProperties().get("label"));
            assertEquals("0",      myriel.getProperties().get("id"));

            // Spot-check that an edge has all its source/target handed off as
            // first-class fields and a numeric weight as a property.
            GraphRelationship first = data.getRelationships().get(0);
            assertNotNull(first.getSourceId());
            assertNotNull(first.getTargetId());
            assertInstanceOf(Double.class,
                    first.getProperties().get(GraphRelationship.PROP_WEIGHT));
        }
    }

    @Test
    void parsesExportCsv() throws Exception {
        Path p = samplePath("export.csv");
        if (p == null) {
            System.err.println("export.csv not on classpath; skipping");
            return;
        }
        try (InputStream in = Files.newInputStream(p)) {
            GraphData data = GraphFileParser.parse(in, GraphFileParser.Format.CSV);
            assertTrue(data.getNodes().size() > 0, "expected > 0 nodes");
            assertTrue(data.getRelationships().size() > 0, "expected > 0 edges");
        }
    }

    /**
     * End-to-end setup check for the NVL "edges take source-node color"
     * feature. {@code Einstufungsverlauf.gml} carries a {@code color}
     * attribute on its nodes but on none of its edges — so when the
     * parser is fed this file, {@code GraphNode.setColor(...)} fires
     * for every node that has a color and no
     * {@code GraphRelationship.setColor(...)} is ever invoked. The
     * downstream contract is that the NVL bridge applies that node
     * color to every outgoing edge (see {@code applyEdgeColors} in
     * {@code nvl-graph-viewer.js}). This test pins down the input
     * side of that contract: the GML must produce a graph where
     * nodes carry their GML colors and relationships carry none.
     */
    @Test
    void einstufungsverlaufNodesCarryColorsEdgesDoNot() throws Exception {
        Path p = samplePath("Einstufungsverlauf.gml");
        if (p == null) {
            System.err.println("Einstufungsverlauf.gml not on classpath; skipping");
            return;
        }
        try (InputStream in = Files.newInputStream(p)) {
            GraphData data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
            assertTrue(data.getNodes().size() > 0, "expected > 0 nodes");
            assertTrue(data.getRelationships().size() > 0, "expected > 0 edges");

            // Mindestens eine Reihe Knoten muss eine Farbe tragen — sonst
            // wäre der Test trivial und würde die Source-Farbe-Kette nicht
            // auslösen. Wir inspizieren die NVL-Serialisierung, weil die
            // internen visualAttrs privat sind und `toNvlNode` genau die
            // Form erzeugt, die der NVL-Bridge übergeben wird.
            int nodesWithColor = 0;
            for (GraphNode n : data.getNodes()) {
                Object c = n.toNvlNode().get("color");
                if (c != null && !String.valueOf(c).isEmpty()) nodesWithColor++;
            }
            assertTrue(nodesWithColor >= 2,
                    "Einstufungsverlauf.gml should yield several nodes with "
                            + "a non-empty color attribute (so the source-color "
                            + "chain has something to propagate); got " + nodesWithColor);

            // Keine einzige Relationship darf eine eigene Farbe tragen —
            // genau das ist die Vorbedingung dafür, dass die Edges
            // ausschließlich von der Source-Farbe abhängen.
            int relsWithColor = 0;
            for (GraphRelationship r : data.getRelationships()) {
                Object c = r.toNvlData().get("color");
                if (c != null && !String.valueOf(c).isEmpty()) relsWithColor++;
            }
            assertEquals(0, relsWithColor,
                    "Einstufungsverlauf.gml must not carry edge colors — the "
                            + "NVL bridge derives edge colors from the source node "
                            + "via applyEdgeColors(). Found " + relsWithColor
                            + " relationships with an explicit color attribute.");
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
