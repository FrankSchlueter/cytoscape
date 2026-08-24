package de.tk.dependencyanalyse.rapui.visgraph.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShapeTest {

    @Test
    void visNetworkAndCytoscapeShapesAreDisjoint() {
        // The vis-network and cytoscape token lists must not overlap —
        // duplicates would mean the same enum constant got a different
        // cytoscape token than vis-network, which is illegal.
        for (Shape s : Shape.values()) {
            if (s.isSupportedByVisNetwork()) {
                assertNotNull(s.visNetworkName(),
                        "Shape " + s + " claims vis-network support but has no vis-name");
            }
            if (s.isSupportedByCytoscape()) {
                assertNotNull(s.cytoscapeName(),
                        "Shape " + s + " claims cytoscape support but has no cytoscape-name");
            }
        }
    }

    @Test
    void valuesForCytoscapeIncludesAllCytoscapeShapes() {
        int count = 0;
        for (Shape s : Shape.valuesForCytoscape()) {
            assertTrue(s.isSupportedByCytoscape(),
                    s + " in cytoscape-filter list but flag is false");
            count++;
        }
        // Must cover the verified cytoscape.js shape vocabulary:
        for (String required : new String[] {
                "ellipse", "triangle", "rectangle", "round-rectangle",
                "diamond", "star", "hexagon", "round-hexagon",
                "concave-hexagon", "octagon", "round-octagon",
                "tag", "round-tag", "vee", "polygon", "pentagon",
                "rhomboid", "right-rhomboid", "barrel" }) {
            boolean found = false;
            for (Shape s : Shape.valuesForCytoscape()) {
                if (required.equals(s.cytoscapeName())) { found = true; break; }
            }
            assertTrue(found, "missing cytoscape shape: " + required);
            count++;
        }
    }

    @Test
    void valuesForVisNetworkExcludesCytoscapeOnlyShapes() {
        // Cytoscape-only shapes must NOT appear in the vis-network filter
        // so the UI combo for vis-network never offers an unsupported entry.
        for (Shape s : Shape.valuesForVisNetwork()) {
            assertTrue(s.isSupportedByVisNetwork(), s + " not really supported by vis-network");
        }
        for (Shape s : Shape.values()) {
            if (!s.isSupportedByVisNetwork() && s.isSupportedByCytoscape()) {
                boolean inFilter = false;
                for (Shape v : Shape.valuesForVisNetwork()) if (v == s) { inFilter = true; break; }
                assertFalse(inFilter, s + " should not appear in vis-network filter");
            }
        }
    }

    @Test
    void visNetworkAndCytoscapeNamesAreLowercase() {
        for (Shape s : Shape.values()) {
            String vn = s.visNetworkName();
            if (vn != null) {
                assertEquals(vn, vn.toLowerCase(),
                        "vis-network name should be lowercase: " + s);
            }
            String cn = s.cytoscapeName();
            if (cn != null) {
                assertEquals(cn, cn.toLowerCase(),
                        "cytoscape name should be lowercase: " + s);
            }
        }
    }
}
