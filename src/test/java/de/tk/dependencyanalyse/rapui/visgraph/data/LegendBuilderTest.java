package de.tk.dependencyanalyse.rapui.visgraph.data;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegendBuilderTest {

    /* ===== fromTagValues ===== */

    @Test
    void fromTagValuesReturnsEmptyForNulls() {
        assertEquals(0, LegendBuilder.fromTagValues(null, null).size());
        assertEquals(0, LegendBuilder.fromTagValues(
                LegendBuilder.graphOf(List.of()), NodeConfig.defaults()).size());
        assertEquals(0, LegendBuilder.fromTagValues(
                LegendBuilder.graphOf(List.of()), NodeConfig.builder()
                        .globalTagColors(Map.of()).build()).size());
    }

    @Test
    void fromTagValuesProducesOneEntryPerConfiguredValue() {
        NodeConfig cfg = NodeConfig.builder()
                .globalTagValueColor("product", "alpha", "#FF0000")
                .globalTagValueColor("product", "beta",  "#00FF00")
                .build();
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("n1", "Class", Map.of("product", "alpha")),
                LegendBuilder.node("n2", "Class", Map.of("product", "alpha")),
                LegendBuilder.node("n3", "Class", Map.of("product", "beta"))
        ));
        List<LegendEntry> legend = LegendBuilder.fromTagValues(data, cfg);
        assertEquals(2, legend.size());
        // Iteration order matches NodeConfig.globalTagColors insertion order.
        assertEquals("product: alpha", legend.get(0).label());
        assertEquals("#FF0000", legend.get(0).colorHex());
        assertEquals(2, legend.get(0).count());
        assertEquals("product: beta", legend.get(1).label());
        assertEquals(1, legend.get(1).count());
    }

    @Test
    void fromTagValuesSkipsValuesWithNullColor() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("alpha", null);
        values.put("beta",  "#00FF00");
        NodeConfig cfg = NodeConfig.builder()
                .globalTagColors(Map.of("product", values))
                .build();
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("n1", "Class", Map.of("product", "alpha")),
                LegendBuilder.node("n2", "Class", Map.of("product", "beta"))
        ));
        List<LegendEntry> legend = LegendBuilder.fromTagValues(data, cfg);
        assertEquals(1, legend.size());
        assertEquals("product: beta", legend.get(0).label());
    }

    /* ===== fromLeidenClusters ===== */

    @Test
    void fromLeidenClustersOrdersBySizeDescending() {
        // 4 nodes in cluster A, 2 nodes in cluster B, 1 node in cluster C.
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("a1", null, Map.of()),
                LegendBuilder.node("a2", null, Map.of()),
                LegendBuilder.node("a3", null, Map.of()),
                LegendBuilder.node("a4", null, Map.of()),
                LegendBuilder.node("b1", null, Map.of()),
                LegendBuilder.node("b2", null, Map.of()),
                LegendBuilder.node("c1", null, Map.of())
        ));
        Map<String, String> leiden = new LinkedHashMap<>();
        leiden.put("a1", "#111111");
        leiden.put("a2", "#111111");
        leiden.put("a3", "#111111");
        leiden.put("a4", "#111111");
        leiden.put("b1", "#222222");
        leiden.put("b2", "#222222");
        leiden.put("c1", "#333333");

        List<LegendEntry> legend = LegendBuilder.fromLeidenClusters(data, leiden);
        assertEquals(3, legend.size());
        // largest cluster first
        assertEquals("Cluster1", legend.get(0).label());
        assertEquals("#111111", legend.get(0).colorHex());
        assertEquals(4, legend.get(0).count());
        assertEquals("Cluster2", legend.get(1).label());
        assertEquals(2, legend.get(1).count());
        assertEquals("Cluster3", legend.get(2).label());
        assertEquals(1, legend.get(2).count());
    }

    @Test
    void fromLeidenClustersBreaksTiesDeterministicallyByColor() {
        // Two clusters with the same size — the lower-cased color breaks
        // the tie so the order is stable across JVMs.
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("x1", null, Map.of()),
                LegendBuilder.node("x2", null, Map.of()),
                LegendBuilder.node("y1", null, Map.of()),
                LegendBuilder.node("y2", null, Map.of())
        ));
        Map<String, String> leiden = new LinkedHashMap<>();
        leiden.put("x1", "#FFFFFF");
        leiden.put("x2", "#FFFFFF");
        leiden.put("y1", "#000000");
        leiden.put("y2", "#000000");
        List<LegendEntry> legend = LegendBuilder.fromLeidenClusters(data, leiden);
        assertEquals(2, legend.size());
        assertEquals("Cluster1", legend.get(0).label());
        assertEquals("Cluster2", legend.get(1).label());
        // Lower hex first when counts are equal
        assertEquals("#000000", legend.get(0).colorHex());
        assertEquals("#FFFFFF", legend.get(1).colorHex());
    }

    @Test
    void fromLeidenClustersIgnoresNodesWithoutColor() {
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("a1", null, Map.of()),
                LegendBuilder.node("a2", null, Map.of()),
                LegendBuilder.node("orphan", null, Map.of())
        ));
        Map<String, String> leiden = new LinkedHashMap<>();
        leiden.put("a1", "#111111");
        leiden.put("a2", "#111111");
        // "orphan" has no entry — should not be counted.
        List<LegendEntry> legend = LegendBuilder.fromLeidenClusters(data, leiden);
        assertEquals(1, legend.size());
        assertEquals(2, legend.get(0).count());
    }

    @Test
    void fromLeidenClustersReturnsEmptyForEmptyInput() {
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("a1", null, Map.of())
        ));
        assertEquals(0, LegendBuilder.fromLeidenClusters(data, null).size());
        assertEquals(0, LegendBuilder.fromLeidenClusters(data, Map.of()).size());
        assertEquals(0, LegendBuilder.fromLeidenClusters(null, Map.of()).size());
    }

    /* ===== fromNodeTypes ===== */

    @Test
    void fromNodeTypesUsesLabelColors() {
        NodeConfig cfg = NodeConfig.builder()
                .labelColor("Class", "#AABBCC")
                .labelColor("Interface", "#DDEEFF")
                .build();
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("n1", "Class", Map.of()),
                LegendBuilder.node("n2", "Class", Map.of()),
                LegendBuilder.node("n3", "Interface", Map.of())
        ));
        List<LegendEntry> legend = LegendBuilder.fromNodeTypes(data, cfg);
        assertEquals(2, legend.size());
        // Iteration order follows the labelColors map insertion order.
        assertEquals("Class", legend.get(0).label());
        assertEquals("#AABBCC", legend.get(0).colorHex());
        assertEquals(2, legend.get(0).count());
        assertEquals("Interface", legend.get(1).label());
        assertEquals(1, legend.get(1).count());
    }

    @Test
    void fromNodeTypesSkipsEntriesWithNullColor() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("A", "#AABBCC");
        map.put("B", null);
        NodeConfig cfg = NodeConfig.builder().labelColors(map).build();
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("n1", "A", Map.of()),
                LegendBuilder.node("n2", "B", Map.of())
        ));
        List<LegendEntry> legend = LegendBuilder.fromNodeTypes(data, cfg);
        assertEquals(1, legend.size());
        assertEquals("A", legend.get(0).label());
    }

    /* ===== combined ===== */

    @Test
    void combinedTagWinsOverClusterAndNodeType() {
        // tag uses the same hex as one of the clusters — tag entry wins.
        NodeConfig cfg = NodeConfig.builder()
                .globalTagValueColor("product", "alpha", "#111111")
                .labelColor("Class", "#444444")
                .build();
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("n1", "Class", Map.of("product", "alpha")),
                LegendBuilder.node("n2", "Class", Map.of("product", "other")),
                LegendBuilder.node("n3", "Class", Map.of())
        ));
        Map<String, String> leiden = new LinkedHashMap<>();
        leiden.put("n1", "#111111");   // same hex as the tag
        leiden.put("n2", "#222222");
        leiden.put("n3", "#444444");   // same hex as the node-type color

        List<LegendEntry> legend = LegendBuilder.combined(data, cfg, leiden);
        // Expected order (Tag > Cluster > NodeType, dedup by hex):
        //   1. "product: alpha"  -> "#111111"   (Tag)
        //   2. "Cluster1"        -> "#222222"   (Cluster, largest surviving)
        //   3. "Cluster2"        -> "#444444"   (Cluster; "#444444" NodeType is deduped)
        assertEquals(3, legend.size());
        assertEquals("product: alpha", legend.get(0).label());
        assertEquals("#111111", legend.get(0).colorHex());
        // The next entry must NOT be the cluster "#111111" — tag already owns that color.
        assertNotEquals("#111111", legend.get(1).colorHex());
        assertEquals("Cluster1", legend.get(1).label());
        assertEquals("#222222", legend.get(1).colorHex());
        // NodeType "Class" with hex "#444444" is skipped because the Cluster
        // section already claimed "#444444" — and cluster beats NodeType.
        assertEquals("Cluster2", legend.get(2).label());
        assertEquals("#444444", legend.get(2).colorHex());
    }

    @Test
    void combinedWithoutLeidenOmitsClusterSection() {
        NodeConfig cfg = NodeConfig.builder()
                .globalTagValueColor("product", "alpha", "#FF0000")
                .labelColor("Class", "#0000FF")
                .build();
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("n1", "Class", Map.of("product", "alpha"))
        ));
        List<LegendEntry> legend = LegendBuilder.combined(data, cfg, null);
        assertEquals(2, legend.size());
        assertEquals("product: alpha", legend.get(0).label());
        assertEquals("Class", legend.get(1).label());
    }

    @Test
    void combinedEmptyConfigYieldsEmptyList() {
        GraphData data = LegendBuilder.graphOf(List.of(
                LegendBuilder.node("n1", "Class", Map.of())
        ));
        assertEquals(0, LegendBuilder.combined(data, null, null).size());
        assertEquals(0, LegendBuilder.combined(data, NodeConfig.defaults(), null).size());
        assertEquals(0, LegendBuilder.combined(null, NodeConfig.defaults(), Map.of()).size());
    }

    /* ===== LegendEntry record ===== */

    @Test
    void legendEntryRejectsNullFields() {
        assertThrows(NullPointerException.class, () -> new LegendEntry(null, "x", 0));
        assertThrows(NullPointerException.class, () -> new LegendEntry("#fff", null, 0));
    }

    @Test
    void legendEntryRejectsNegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> new LegendEntry("#fff", "x", -1));
    }

    @Test
    void legendEntryNormalizesColorForLookup() {
        LegendEntry e = new LegendEntry("#FF00AA", "X", 0);
        assertEquals("#ff00aa", e.normalizedColor());
    }
}