package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the 3D Force-Directed Graph engine routing inside
 * {@link de.tk.dependencyanalyse.rapui.visgraph.SwitchingViewer}.
 *
 * <p>Mirror image of
 * {@link SwitchingViewerNvlRoutingTest}. Guards every place where the
 * SwitchingViewer fans out to the active engine: the {@code switchTo}
 * branch for {@code THREE_FORCE_GRAPH}, the per-method dispatches
 * ({@code setGraphData}, {@code setNodeConfig}, {@code applyNodeColors},
 * {@code setLayout}, {@code setLeidenClusterColors}, {@code fitToScreen},
 * {@code clear}, selection listeners, context menu), the {@code wireViewer}
 * overload, and {@code disposeViewer}. A regression that drops one of the
 * branches causes silent user-visible breakage (e.g. engine round-trip
 * to 3D shows an empty canvas), so these checks fail loudly.</p>
 */
class SwitchingViewerThreeRoutingTest {

    private static final String[] POSSIBLE_PATHS = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/SwitchingViewer.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/SwitchingViewer.java",
    };

    private static String loadSource() throws Exception {
        for (String p : POSSIBLE_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("SwitchingViewer.java not found in any known location");
    }

    private static String extractMethodBody(String src, int start) {
        int braceDepth = 0;
        boolean inside = false;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                braceDepth++;
                inside = true;
            } else if (c == '}') {
                braceDepth--;
                if (inside && braceDepth == 0) {
                    out.append(c);
                    return out.toString();
                }
            }
            if (inside) out.append(c);
        }
        return out.toString();
    }

    /* ---- switchTo() ---- */

    @Test
    void switchToThreeForwardsCurrentGraphData() throws Exception {
        String src = loadSource();
        int start = src.indexOf("public void switchTo(GraphEngine engine) {");
        assertTrue(start > 0, "SwitchingViewer.switchTo must exist");
        String method = extractMethodBody(src, start);
        assertTrue(method.contains("engine == GraphEngine.THREE_FORCE_GRAPH"),
                "switchTo must contain a `engine == GraphEngine.THREE_FORCE_GRAPH` branch");
        assertTrue(method.contains("threeForceGraphViewer = new ThreeForceGraphViewer("),
                "switchTo(THREE_FORCE_GRAPH) must construct a ThreeForceGraphViewer");
        assertTrue(method.contains("wireViewer(threeForceGraphViewer)"),
                "switchTo(THREE_FORCE_GRAPH) must call wireViewer(threeForceGraphViewer)");
        assertTrue(method.contains("threeForceGraphViewer.setGraphData(currentData)"),
                "switchTo(THREE_FORCE_GRAPH) must forward currentData — otherwise the user sees an empty canvas");
        assertTrue(method.contains("threeForceGraphViewer.setNodeConfig(currentNodeConfig)"),
                "switchTo(THREE_FORCE_GRAPH) must forward the cached NodeConfig");
        assertTrue(method.contains("threeForceGraphViewer.setLayout("),
                "switchTo(THREE_FORCE_GRAPH) must apply the current layout");
    }

    /* ---- per-method fan-out ---- */

    @Test
    void setGraphDataRoutesToThreeWhenActive() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("currentEngine == GraphEngine.THREE_FORCE_GRAPH && threeForceGraphViewer != null"),
                "setGraphData must contain a `currentEngine == GraphEngine.THREE_FORCE_GRAPH && threeForceGraphViewer != null` branch");
        // Make sure that branch actually calls the 3D viewer (and not just
        // a stray reference in a comment).
        int idx = src.indexOf("currentEngine == GraphEngine.THREE_FORCE_GRAPH && threeForceGraphViewer != null");
        // The next closing brace on the same indentation must contain the call.
        String tail = src.substring(idx, Math.min(idx + 600, src.length()));
        assertTrue(tail.contains("threeForceGraphViewer.setGraphData(data)"),
                "setGraphData must route to threeForceGraphViewer.setGraphData(data) when 3D is active");
    }

    @Test
    void setNodeConfigRoutesToThreeWhenActive() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("threeForceGraphViewer.setNodeConfig(config)"),
                "setNodeConfig must route to threeForceGraphViewer.setNodeConfig(config)");
        assertTrue(src.contains("threeForceGraphViewer.applyNodeColors("),
                "setNodeConfig must also push the resolved per-node color map to threeForceGraphViewer");
    }

    @Test
    void applyNodeColorsRoutesToThreeWhenActive() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("threeForceGraphViewer.applyNodeColors(effective)"),
                "applyNodeColors must route to threeForceGraphViewer.applyNodeColors(effective)");
    }

    @Test
    void getNodeConfigReadsFromThreeWhenActive() throws Exception {
        String src = loadSource();
        // getNodeConfig is a series of `if (currentEngine == ... && viewer != null) return viewer.getNodeConfig();` blocks.
        Pattern p = Pattern.compile(
                "currentEngine\\s*==\\s*GraphEngine\\.THREE_FORCE_GRAPH\\s*&&\\s*threeForceGraphViewer\\s*!=\\s*null[^}]*threeForceGraphViewer\\.getNodeConfig\\(\\)",
                Pattern.DOTALL);
        assertTrue(p.matcher(src).find(),
                "getNodeConfig must read from threeForceGraphViewer when 3D is active");
    }

    @Test
    void setLayoutRoutesToThreeWhenActive() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("threeForceGraphViewer.setLayout(algorithm)"),
                "setLayout must route to threeForceGraphViewer.setLayout(algorithm)");
        assertTrue(src.contains("isSupportedByThreeForceGraph()"),
                "setLayout must gate the threeForceGraphViewer call by isSupportedByThreeForceGraph()");
    }

    @Test
    void setLeidenClusterColorsRoutesToThreeWhenActive() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("threeForceGraphViewer.setLeidenClusterColors(currentLeidenColors)"),
                "setLeidenClusterColors must route to threeForceGraphViewer.setLeidenClusterColors");
        // And must also push the effective color map (the dialog uses it).
        int idx = src.indexOf("threeForceGraphViewer.setLeidenClusterColors(currentLeidenColors)");
        String tail = src.substring(idx, Math.min(idx + 400, src.length()));
        assertTrue(tail.contains("threeForceGraphViewer.applyNodeColors("),
                "setLeidenClusterColors must also push the resolved effective colors to threeForceGraphViewer");
    }

    @Test
    void fitToScreenRoutesToThreeWhenActive() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("threeForceGraphViewer.fitToScreen()"),
                "fitToScreen must route to threeForceGraphViewer.fitToScreen()");
    }

    @Test
    void clearRoutesToThreeWhenActive() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("threeForceGraphViewer.clear()"),
                "clear must route to threeForceGraphViewer.clear()");
    }

    /* ---- Selection listeners ---- */

    @Test
    void addNodeSelectionListenerRoutesToThree() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("if (threeForceGraphViewer != null) threeForceGraphViewer.addNodeSelectionListener(l)"),
                "addNodeSelectionListener must route to threeForceGraphViewer");
    }

    @Test
    void addRelationshipSelectionListenerRoutesToThree() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("if (threeForceGraphViewer != null) threeForceGraphViewer.addRelationshipSelectionListener(l)"),
                "addRelationshipSelectionListener must route to threeForceGraphViewer");
    }

    @Test
    void addSelectionClearedListenerRoutesToThree() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("if (threeForceGraphViewer != null) threeForceGraphViewer.addSelectionClearedListener(l)"),
                "addSelectionClearedListener must route to threeForceGraphViewer");
    }

    @Test
    void setContextMenuProviderRoutesToThree() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("if (threeForceGraphViewer != null) threeForceGraphViewer.setContextMenuProvider(provider)"),
                "setContextMenuProvider must route to threeForceGraphViewer");
    }

    /* ---- wireViewer & disposeViewer ---- */

    @Test
    void wireViewerOverloadForThreeExists() throws Exception {
        String src = loadSource();
        Pattern p = Pattern.compile(
                "private\\s+void\\s+wireViewer\\s*\\(\\s*ThreeForceGraphViewer\\s+v\\s*\\)",
                Pattern.DOTALL);
        assertTrue(p.matcher(src).find(),
                "SwitchingViewer must define a wireViewer(ThreeForceGraphViewer) overload");
    }

    @Test
    void disposeViewerDisposesThree() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("if (threeForceGraphViewer != null && !threeForceGraphViewer.isDisposed())"),
                "disposeViewer must dispose threeForceGraphViewer when present");
    }

    /* ---- Legend exclusion ---- */

    @Test
    void setLegendExcludesThree() throws Exception {
        String src = loadSource();
        int idx = src.indexOf("public void setLegend(List<LegendEntry>");
        assertTrue(idx > 0, "SwitchingViewer.setLegend must exist");
        String body = src.substring(idx, src.indexOf("public void clearLegend()", idx));
        assertTrue(body.contains("currentEngine != GraphEngine.THREE_FORCE_GRAPH"),
                "SwitchingViewer.setLegend must NOT route to 3D — Color Palette is auto-managed");
    }

    /* ---- Engine enum value ---- */

    @Test
    void engineEnumHasThreeForceGraph() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/engine/GraphEngine.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("THREE_FORCE_GRAPH"),
                "GraphEngine must have a THREE_FORCE_GRAPH value");
    }

    /* ---- Layout algorithm ---- */

    @Test
    void layoutAlgorithmHasForce3D() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/data/LayoutAlgorithm.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("FORCE_3D"),
                "LayoutAlgorithm must have a FORCE_3D value");
        assertTrue(src.contains("isSupportedByThreeForceGraph()"),
                "LayoutAlgorithm must have an isSupportedByThreeForceGraph() flag");
        assertTrue(src.contains("valuesForThreeForceGraph()"),
                "LayoutAlgorithm must have a valuesForThreeForceGraph() filter");
    }

    /* ---- GraphData serializer ---- */

    @Test
    void graphDataExposesThreeForceGraphData() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/data/GraphData.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("toThreeForceGraphData"),
                "GraphData must expose toThreeForceGraphData()");
    }

    /* ---- GraphRelationship serializer (weight-aware) ---- */

    @Test
    void graphRelationshipToThreeForceGraphLinkExposesWeight() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/data/GraphRelationship.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        // Find the toThreeForceGraphLink() method body
        int idx = src.indexOf("public Map<String, Object> toThreeForceGraphLink() {");
        assertTrue(idx > 0, "GraphRelationship.toThreeForceGraphLink() must exist");
        String body = src.substring(idx, src.indexOf("/**\n     * Serializes the relationship as a Cytoscape.js element entry", idx));
        // Weight + pre-computed logWeight must be exposed at the top level
        // of the link payload so the JS bridge can drive d3Force('link')
        // and linkWidth without dereferencing properties. Without this
        // the 3D engine silently ignores the weight attribute and all
        // edges look identical regardless of how heavy they are.
        assertTrue(body.contains("out.put(\"weight\""),
                "toThreeForceGraphLink must put 'weight' at the top level of the payload");
        assertTrue(body.contains("out.put(\"logWeight\""),
                "toThreeForceGraphLink must put 'logWeight' at the top level of the payload");
        assertTrue(body.contains("Math.log10(w + 1.0)"),
                "toThreeForceGraphLink must pre-compute logWeight via Math.log10(w + 1.0) (matches toVisNetworkData / toCytoscapeEdge)");
    }
}
