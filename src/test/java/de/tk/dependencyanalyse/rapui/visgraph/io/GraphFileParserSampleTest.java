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
