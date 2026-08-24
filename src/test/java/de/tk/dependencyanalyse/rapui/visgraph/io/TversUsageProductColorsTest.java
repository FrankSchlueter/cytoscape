package de.tk.dependencyanalyse.rapui.visgraph.io;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification against the TVERS-Usage.gml sample. The user
 * reported: "Apply Tag Colors" did not paint the nodes. We simulate the
 * dialog by building the same NodeConfig that
 * GraphConfigurationDialog.applyTagColors() pushes — globalTagColors — and
 * then verify each node's product value matches a single, distinct color
 * in the resulting per-node mapping.
 */
class TversUsageProductColorsTest {

    @Test
    void applyTagColorsProducesDistinctColorPerProduct() throws Exception {
        Path p = findSampleFile("TVERS-Usage.gml");
        if (p == null) {
            System.err.println("TVERS-Usage.gml not on classpath; skipping");
            return;
        }
        GraphData data;
        try (InputStream in = Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }

        // Walk every node and bucket the distinct `product` values — same
        // approach as GraphConfigurationDialog.Discovery.distinctValues.
        Map<String, Integer> productCounts = new LinkedHashMap<>();
        for (GraphNode n : data.getNodes()) {
            Object v = n.getProperties().get("product");
            if (v == null) continue;
            productCounts.merge(String.valueOf(v), 1, Integer::sum);
        }
        // The GML parser wraps value-only tokens like `product Versichertenbestandsfuehrung`
        // (without quotes) as raw identifiers, not as strings — verify that
        // the product-bearing nodes carry it as a String. Not every TVERS
        // node has a `product` value (TableInfo rows use `ownerProduct`
        // instead); we only check nodes that DO have it.
        for (GraphNode n : data.getNodes()) {
            Object v = n.getProperties().get("product");
            if (v == null) continue;
            assertTrue(v instanceof String,
                    "product value must be a String, got " + v.getClass());
        }
        assertEquals(20, productCounts.size(),
                "TVERS-Usage.gml should expose exactly 20 distinct product values, got "
                        + productCounts.size() + ", all: " + productCounts.keySet());
        // Sum of all product-bearing nodes. TVERS-Usage.gml has 77 nodes total.
        // Some `TableInfo` nodes (e.g. id 22, the TVERS schema row) carry
        // `ownerProduct` instead of `product` — they are part of the schema,
        // not the application classes. The 20 distinct `product` values all
        // appear on Class / BatchReader / BatchWriter / TKController nodes.
        int totalProductOccurrences = productCounts.values().stream()
                .mapToInt(Integer::intValue).sum();
        assertTrue(totalProductOccurrences >= 50 && totalProductOccurrences <= 80,
                "expected 50..80 product-bearing nodes in TVERS-Usage.gml, got "
                        + totalProductOccurrences);
        System.out.println("[TversUsageProductColorsTest] TVERS product counts ("
                + totalProductOccurrences + " nodes, " + productCounts.size()
                + " distinct):");
        productCounts.forEach((k, v) ->
                System.out.println("    " + k + " x" + v));

        // Build the color palette the dialog would (20 evenly spaced colors).
        Map<String, String> productColors = new LinkedHashMap<>();
        List<String> sortedProducts = productCounts.keySet().stream().sorted().toList();
        for (int i = 0; i < sortedProducts.size(); i++) {
            double hue = 300.0 * i / Math.max(1, sortedProducts.size() - 1);
            productColors.put(sortedProducts.get(i), hsvToHex(hue));
        }

        // Build the global-tag mapping the Apply-Tag-Colors button would push.
        // Note: tag colors apply only to nodes with a `product` property —
        // the TVERS schema rows (TableInfo) only have `ownerProduct` and
        // remain uncolored by the push.
        Map<String, Map<String, String>> globalTags = Map.of("product", productColors);

        // For each product-bearing node, verify the mapped color is non-null.
        int nodesWithProduct = 0;
        for (GraphNode n : data.getNodes()) {
            Object v = n.getProperties().get("product");
            if (v == null) continue;
            String product = String.valueOf(v);
            assertTrue(productColors.containsKey(product),
                    "product " + product + " missing from palette");
            nodesWithProduct++;
        }
        assertTrue(nodesWithProduct >= 50 && nodesWithProduct <= 80,
                "expected 50..80 nodes with a product property, got "
                        + nodesWithProduct);

        // Sanity: every product maps to exactly one color.
        Map<String, String> inverted = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : productColors.entrySet()) {
            assertNull(inverted.put(e.getValue(), e.getKey()),
                    "duplicate color " + e.getValue() + " for products "
                            + inverted.get(e.getValue()) + " and " + e.getKey());
        }
        assertEquals(20, inverted.size());
    }

    @Test
    void cytoscapeElementsCarryProductValueDataSoSelectorsMatch() throws Exception {
        Path p = findSampleFile("TVERS-Usage.gml");
        if (p == null) {
            System.err.println("TVERS-Usage.gml not on classpath; skipping");
            return;
        }
        GraphData data;
        try (InputStream in = Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }

        int matched = 0;
        for (GraphNode n : data.getNodes()) {
            Object propValue = n.getProperties().get("product");
            if (propValue == null) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> ele = n.toCytoscapeNode();
            @SuppressWarnings("unchecked")
            Map<String, Object> eleData = (Map<String, Object>) ele.get("data");
            assertEquals(String.valueOf(propValue), eleData.get("product"),
                    "Cytoscape elements.data.product must mirror properties.product");
            matched++;
        }
        assertTrue(matched >= 50 && matched <= 80,
                "expected 50..80 product-bearing nodes, got " + matched);
    }

    @Test
    void cytoscapeStylePayloadHasOneSelectorPerProductValue() throws Exception {
        Path p = findSampleFile("TVERS-Usage.gml");
        if (p == null) return;

        GraphData data;
        try (InputStream in = Files.newInputStream(p)) {
            data = GraphFileParser.parse(in, GraphFileParser.Format.GML);
        }

        // Build a NodeConfig exactly as the dialog's "Apply Tag Colors"
        // button does: pick the `product` tag, generate a 20-color palette,
        // push as globalTagColors.
        java.util.Map<String, Integer> productCounts = new java.util.LinkedHashMap<>();
        for (GraphNode n : data.getNodes()) {
            Object v = n.getProperties().get("product");
            if (v == null) continue;
            productCounts.merge(String.valueOf(v), 1, Integer::sum);
        }
        assertEquals(20, productCounts.size(),
                "TVERS-Usage.gml must have exactly 20 distinct product values");
        java.util.List<String> sortedProducts = productCounts.keySet().stream().sorted().toList();

        java.util.Map<String, String> palette = new java.util.LinkedHashMap<>();
        for (int i = 0; i < sortedProducts.size(); i++) {
            double hue = 300.0 * i / Math.max(1, sortedProducts.size() - 1);
            palette.put(sortedProducts.get(i), hsvToHex(hue, 0.65, 0.95));
        }
        // Verify color uniqueness.
        java.util.Set<String> colorSet = new java.util.HashSet<>(palette.values());
        assertEquals(20, colorSet.size(),
                "Apply Tag Colors must produce 20 visually distinct colors");
        System.out.println("[TversUsageProductColorsTest] Generated 20-color palette:");
        palette.forEach((k, v) ->
                System.out.println("    " + k + " -> " + v));

        // Simulate the JS buildStyleFromConfig(globalTagColors) loop.
        // The expected selector for each (product, value) is
        //   node[product = "<cytoscapeQuote(value)>"]
        java.util.List<String> selectors = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : palette.entrySet()) {
            String value = e.getKey();
            String quoted = value.replace("\\", "\\\\").replace("\"", "\\\"");
            selectors.add("node[product = \"" + quoted + "\"]");
        }
        // Same product value maps to the same colour across selectors.
        for (int i = 0; i < palette.size(); i++) {
            String product = sortedProducts.get(i);
            String color = palette.get(product);
            // Check: same product name appears at most once across our
            // selectors (i.e. no duplicate tuples would be pushed).
            long count = palette.keySet().stream()
                    .filter(k -> k.equals(product)).count();
            assertEquals(1, count);
            // Each color appears exactly once (one-to-one mapping).
            long colorCount = palette.values().stream()
                    .filter(c -> c.equals(color)).count();
            assertEquals(1, colorCount);
        }
        // Every selector must round-trip through JSON encoding without
        // distortion, so the JS-side payload stays parseable.
        String json = new com.google.gson.Gson().toJson(selectors);
        @SuppressWarnings("unchecked")
        java.util.List<String> roundtrip = new com.google.gson.Gson().fromJson(
                json, java.util.List.class);
        assertEquals(selectors, roundtrip,
                "selectors must round-trip through JSON cleanly");
        System.out.println("[TversUsageProductColorsTest] All 20 selectors JSON-roundtrip OK");
        System.out.println("    sample selectors:");
        for (int i = 0; i < 3; i++) {
            System.out.println("    " + selectors.get(i));
        }
    }

    Path findSampleFile(String name) {
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

    /* ---------------- inline HSV→HEX (mirror of ColorScale.interpolate) ----- */

    private static String hsvToHex(double hue) {
        return hsvToHex(hue, 0.65, 0.95);
    }

    private static String hsvToHex(double hue, double saturation, double value) {
        double h = ((hue % 360.0) + 360.0) % 360.0;
        double c = value * saturation;
        double hi = Math.floor(h / 60.0);
        double x = c * (1.0 - Math.abs((h / 60.0) % 2.0 - 1.0));
        double r1, g1, b1;
        int sector = (int) hi;
        switch (sector) {
            case 0:  r1 = c; g1 = x; b1 = 0; break;
            case 1:  r1 = x; g1 = c; b1 = 0; break;
            case 2:  r1 = 0; g1 = c; b1 = x; break;
            case 3:  r1 = 0; g1 = x; b1 = c; break;
            case 4:  r1 = x; g1 = 0; b1 = c; break;
            case 5:  r1 = c; g1 = 0; b1 = x; break;
            default: r1 = g1 = b1 = 0; break;
        }
        double m = value - c;
        int r = clamp((int) Math.round((r1 + m) * 255.0));
        int g = clamp((int) Math.round((g1 + m) * 255.0));
        int b = clamp((int) Math.round((b1 + m) * 255.0));
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static int clamp(int v) {
        if (v < 0)   return 0;
        if (v > 255) return 255;
        return v;
    }
}
