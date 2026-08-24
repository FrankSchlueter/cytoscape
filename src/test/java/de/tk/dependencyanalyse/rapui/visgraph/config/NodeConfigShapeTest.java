package de.tk.dependencyanalyse.rapui.visgraph.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeConfigShapeTest {

    @Test
    void labelShapeRoundtripsThroughBuilder() {
        NodeConfig cfg = NodeConfig.builder()
                .labelShape("Hub", "round-tag")
                .labelShape("Spoke", "hexagon")
                .build();
        assertEquals("round-tag", cfg.shapeForLabel("Hub"));
        assertEquals("hexagon",   cfg.shapeForLabel("Spoke"));
        assertNull(cfg.shapeForLabel("missing"));
    }

    @Test
    void labelShapesMapIsImmutable() {
        NodeConfig cfg = NodeConfig.builder()
                .labelShape("Hub", "ellipse")
                .build();
        Map<String, String> map = cfg.getLabelShapes();
        assertEquals(1, map.size());
        assertThrows(UnsupportedOperationException.class,
                () -> map.put("X", "rectangle"));
    }

    @Test
    void withLabelShapeDerivesNewConfig() {
        NodeConfig base = NodeConfig.defaults();
        NodeConfig next = base.withLabelShape("X", "diamond");
        assertNull(base.shapeForLabel("X"), "base must not be mutated");
        assertEquals("diamond", next.shapeForLabel("X"));
    }

    @Test
    void labelShapesBuilderMapOverridesDefault() {
        NodeConfig cfg = NodeConfig.builder()
                .labelShapes(Map.of("A", "ellipse", "B", "rectangle"))
                .build();
        assertEquals("ellipse",  cfg.shapeForLabel("A"));
        assertEquals("rectangle", cfg.shapeForLabel("B"));
    }

    @Test
    void globalTagColorsRoundtrip() {
        NodeConfig cfg = NodeConfig.builder()
                .globalTagValueColor("product", "Rente", "#FF0000")
                .globalTagValueColor("product", "Versichertenbestandsfuehrung", "#00FF00")
                .build();
        Map<String, Map<String, String>> globals = cfg.getGlobalTagColors();
        assertEquals(1, globals.size());
        Map<String, String> byValue = globals.get("product");
        assertNotNull(byValue);
        assertEquals("#FF0000", byValue.get("Rente"));
        assertEquals("#00FF00", byValue.get("Versichertenbestandsfuehrung"));
    }

    @Test
    void globalTagColorsImmutability() {
        NodeConfig cfg = NodeConfig.builder()
                .globalTagValueColor("p", "v", "#000000")
                .build();
        Map<String, Map<String, String>> globals = cfg.getGlobalTagColors();
        Map<String, String> inner = globals.get("p");
        assertThrows(UnsupportedOperationException.class,
                () -> inner.put("x", "#111111"));
        assertThrows(UnsupportedOperationException.class,
                () -> globals.put("y", Map.of()));
    }

    @Test
    void globalTagColorsReplaceViaBulkMap() {
        NodeConfig cfg = NodeConfig.builder()
                .globalTagColors(Map.of(
                        "product", Map.of("A", "#111111", "B", "#222222"),
                        "bundle",  Map.of("X", "#333333")))
                .build();
        Map<String, Map<String, String>> globals = cfg.getGlobalTagColors();
        assertEquals(2, globals.size());
        assertEquals(2, globals.get("product").size());
        assertEquals(1, globals.get("bundle").size());
    }
}
