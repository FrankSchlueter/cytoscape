package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the NVL-Engine routing inside
 * {@link de.tk.dependencyanalyse.rapui.visgraph.SwitchingViewer}.
 *
 * <p>These tests guard the contract that fixes the "NVL-Engine shows an
 * empty canvas" bug: the {@code SwitchingViewer.switchTo(GraphEngine.NEO4J_NVL)}
 * branch used to wire only {@code setNodeConfig} / {@code setLayout} but
 * forgot to forward the cached {@code currentData} to the freshly-created
 * {@link de.tk.dependencyanalyse.rapui.visgraph.Neo4jNvlViewer}, so the
 * user had to manually call {@code setGraphData} again after every
 * engine switch.</p>
 *
 * <p>In addition to {@code switchTo} the routing was missing in
 * {@code setGraphData}, {@code getNodeConfig}, {@code setLayout},
 * {@code fitToScreen}, {@code clear},
 * {@code addNodeSelectionListener},
 * {@code addRelationshipSelectionListener},
 * {@code addSelectionClearedListener},
 * {@code setContextMenuProvider} and {@code disposeViewer} — every method
 * that delegates to the active engine. These source-level checks fail
 * loudly if any of the branches regress.</p>
 *
 * <p>Mirror image of the atomic-script contract test in
 * {@code CytoscapeViewerEndToEndTest}: rather than standing up a full
 * SWT {@code Browser} (out of scope for a JUnit test), we read the
 * SwitchingViewer source and assert the routing calls exist where the
 * contract says they must.</p>
 */
class SwitchingViewerNvlRoutingTest {

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

    /**
     * The headline regression test: when the user switches to the NVL
     * engine, the current graph data MUST be pushed to the freshly-created
     * NVL viewer. Without this line the user sees an empty canvas
     * because {@code Neo4jNvlViewer.runWhenReady()} has no
     * pre-loaded data when {@code vgv_viewerReady} fires.
     */
    @Test
    void switchToNvlForwardsCurrentGraphData() throws Exception {
        String src = loadSource();

        // Extract the NEO4J_NVL branch of switchTo().
        Pattern branchPattern = Pattern.compile(
                "\\}\\s*else\\s+if\\s*\\(engine\\s*==\\s*GraphEngine\\.NEO4J_NVL\\)\\s*\\{([\\s\\S]*?)\\n\\s+\\}\\s*else\\s*\\{",
                Pattern.MULTILINE);
        Matcher m = branchPattern.matcher(src);
        assertTrue(m.find(),
                "SwitchingViewer.switchTo() must contain an `else if (engine == GraphEngine.NEO4J_NVL)` branch");
        String branch = m.group(1);

        assertTrue(branch.contains("nvlViewer = new Neo4jNvlViewer("),
                "switchTo(NEO4J_NVL) must construct a Neo4jNvlViewer");
        assertTrue(branch.contains("wireViewer(nvlViewer)"),
                "switchTo(NEO4J_NVL) must call wireViewer(nvlViewer) so listeners survive the swap");
        assertTrue(branch.contains("nvlViewer.setGraphData(currentData)"),
                "switchTo(NEO4J_NVL) must forward currentData to the new NVL viewer — "
                        + "this is the fix for the 'empty canvas after switching to NVL' bug");
        assertTrue(branch.contains("nvlViewer.setNodeConfig(currentNodeConfig)"),
                "switchTo(NEO4J_NVL) must forward the cached NodeConfig");
        assertTrue(branch.contains("nvlViewer.setLayout("),
                "switchTo(NEO4J_NVL) must apply the current layout");
    }

    /**
     * {@code setGraphData} must route to the active NVL viewer when
     * NVL is the active engine. Without this branch any later
     * {@code viewer.setGraphData(data)} call after an engine switch to
     * NVL is silently dropped.
     */
    @Test
    void setGraphDataRoutesToNvlWhenActive() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "public void setGraphData\\(GraphData data\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "setGraphData(GraphData) must exist");
        String body = m.group(1);
        assertTrue(body.contains("currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null"),
                "setGraphData must branch on `currentEngine == GraphEngine.NEO4J_NVL`");
        assertTrue(body.contains("nvlViewer.setGraphData(data)"),
                "setGraphData must forward the data to nvlViewer when NVL is active");
    }

    /**
     * {@code getNodeConfig} must read from the active NVL viewer so
     * the configuration dialog reflects NVL's effective config (rather
     * than stale Cytoscape state) after an engine switch.
     */
    @Test
    void getNodeConfigRoutesToNvlWhenActive() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "public NodeConfig getNodeConfig\\(\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "getNodeConfig() must exist");
        String body = m.group(1);
        assertTrue(body.contains("currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null"),
                "getNodeConfig must branch on `currentEngine == GraphEngine.NEO4J_NVL`");
        assertTrue(body.contains("return nvlViewer.getNodeConfig()"),
                "getNodeConfig must return nvlViewer's config when NVL is active");
    }

    /**
     * {@code setLayout} must route to the active NVL viewer and honour
     * the engine's support matrix.
     */
    @Test
    void setLayoutRoutesToNvlWhenActive() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "public void setLayout\\(LayoutAlgorithm algorithm\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "setLayout(LayoutAlgorithm) must exist");
        String body = m.group(1);
        assertTrue(body.contains("currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null"),
                "setLayout must branch on `currentEngine == GraphEngine.NEO4J_NVL`");
        assertTrue(body.contains("algorithm.isSupportedByNvl()"),
                "setLayout must respect NVL's support matrix when routing");
        assertTrue(body.contains("nvlViewer.setLayout(algorithm)"),
                "setLayout must forward the algorithm to nvlViewer when NVL is active");
    }

    /**
     * {@code fitToScreen} must route to the active NVL viewer.
     */
    @Test
    void fitToScreenRoutesToNvlWhenActive() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "public void fitToScreen\\(\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "fitToScreen() must exist");
        String body = m.group(1);
        assertTrue(body.contains("currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null"),
                "fitToScreen must branch on `currentEngine == GraphEngine.NEO4J_NVL`");
        assertTrue(body.contains("nvlViewer.fitToScreen()"),
                "fitToScreen must forward to nvlViewer when NVL is active");
    }

    /**
     * {@code clear} must route to the active NVL viewer.
     */
    @Test
    void clearRoutesToNvlWhenActive() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "public void clear\\(\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "clear() must exist");
        String body = m.group(1);
        assertTrue(body.contains("currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null"),
                "clear must branch on `currentEngine == GraphEngine.NEO4J_NVL`");
        assertTrue(body.contains("nvlViewer.clear()"),
                "clear must forward to nvlViewer when NVL is active");
    }

    /**
     * Selection-listener registrations must reach the NVL viewer so
     * clicks on NVL-rendered nodes propagate back to Java listeners.
     */
    @Test
    void selectionListenerRegistrationsIncludeNvl() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("nvlViewer.addNodeSelectionListener(l)"),
                "addNodeSelectionListener must register on nvlViewer");
        assertTrue(src.contains("nvlViewer.addRelationshipSelectionListener(l)"),
                "addRelationshipSelectionListener must register on nvlViewer");
        assertTrue(src.contains("nvlViewer.addSelectionClearedListener(l)"),
                "addSelectionClearedListener must register on nvlViewer");
    }

    /**
     * {@code setContextMenuProvider} must wire the NVL viewer too —
     * otherwise right-clicks on NVL-rendered nodes show no menu.
     */
    @Test
    void setContextMenuProviderRoutesToNvl() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("nvlViewer.setContextMenuProvider(provider)"),
                "setContextMenuProvider must wire nvlViewer");
    }

    /**
     * {@code disposeViewer} must dispose the NVL viewer so the
     * underlying RAP Browser is released on engine switch.
     */
    @Test
    void disposeViewerDisposesNvl() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "private void disposeViewer\\(\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "disposeViewer() must exist");
        String body = m.group(1);
        assertTrue(body.contains("if (nvlViewer != null && !nvlViewer.isDisposed())"),
                "disposeViewer must guard with isDisposed()");
        assertTrue(body.contains("nvlViewer.dispose()"),
                "disposeViewer must dispose the NVL viewer");
        assertTrue(body.contains("nvlViewer = null;"),
                "disposeViewer must null out the NVL viewer field");
    }

    /**
     * Regression test for the "Apply Leiden Clustering does nothing on
     * NVL" bug: {@code setLeidenClusterColors} MUST forward to the
     * NVL viewer — previously it only branched on CYTOSCAPE/SIGMA/
     * VIS_NETWORK and silently dropped the call when NVL was active.
     */
    @Test
    void setLeidenClusterColorsRoutesToNvlWhenActive() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "public void setLeidenClusterColors\\(Map<String, String> colors\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "setLeidenClusterColors(Map) must exist");
        String body = m.group(1);
        assertTrue(body.contains("currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null"),
                "setLeidenClusterColors must branch on `currentEngine == GraphEngine.NEO4J_NVL`");
        assertTrue(body.contains("nvlViewer.setLeidenClusterColors(currentLeidenColors)"),
                "setLeidenClusterColors must forward the Leiden colors to nvlViewer when NVL is active");
    }

    /**
     * {@code setNodeConfig} must apply the resolved per-node color
     * map on the active NVL viewer so the unified color-update path
     * reaches every engine (not just Cytoscape/vis/sigma). Previously
     * the NVL branch was missing the applyNodeColors call entirely.
     */
    @Test
    void setNodeConfigRoutesToNvlWhenActive() throws Exception {
        String src = loadSource();
        Pattern method = Pattern.compile(
                "public void setNodeConfig\\(NodeConfig config\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = method.matcher(src);
        assertTrue(m.find(), "setNodeConfig(NodeConfig) must exist");
        String body = m.group(1);
        assertTrue(body.contains("currentEngine == GraphEngine.NEO4J_NVL && nvlViewer != null"),
                "setNodeConfig must branch on `currentEngine == GraphEngine.NEO4J_NVL`");
        assertTrue(body.contains("nvlViewer.setNodeConfig(config)"),
                "setNodeConfig must forward to nvlViewer when NVL is active");
        assertTrue(body.contains("nvlViewer.applyNodeColors("),
                "setNodeConfig must trigger applyNodeColors on the NVL viewer "
                        + "so the unified color update reaches NVL");
    }

    /**
     * The unified {@code applyNodeColors(NodeConfig, Map<String,String>)}
     * entry point must exist on SwitchingViewer and dispatch to every
     * engine branch (Cytoscape, vis-network, sigma, NVL).
     */
    @Test
    void applyNodeColorsDispatchesToEveryEngine() throws Exception {
        String src = loadSource();
        assertTrue(src.contains("public void applyNodeColors(NodeConfig config, Map<String, String> leidenColors)"),
                "SwitchingViewer must expose applyNodeColors(NodeConfig, Map) — the unified update entry point");
        assertTrue(src.contains("NodeColorResolver"),
                "applyNodeColors must delegate to NodeColorResolver for the effective per-node color computation");
        assertTrue(src.contains("cytoscapeViewer.applyNodeColors("),
                "applyNodeColors must forward to cytoscapeViewer");
        assertTrue(src.contains("visViewer.applyNodeColors("),
                "applyNodeColors must forward to visViewer");
        assertTrue(src.contains("sigmaViewer.applyNodeColors("),
                "applyNodeColors must forward to sigmaViewer");
        assertTrue(src.contains("nvlViewer.applyNodeColors("),
                "applyNodeColors must forward to nvlViewer");
    }

    /**
     * After {@code switchTo(NEO4J_NVL)} the freshly-created NVL viewer
     * must receive both the cached Leiden colors and the resolved
     * per-node color map — otherwise the previous engine's colors
     * disappear on every switch into NVL.
     *
     * <p>Uses the full {@code switchTo(...)} method body (rather than a
     * regex slice of just the NEO4J_NVL branch) because the branch now
     * contains nested {@code if / else} pairs whose inner {@code }
     * else {} } confuses non-greedy regex slicing. Asserting against
     * the whole method is brittle-but-clear: each lookup confirms one
     * routing requirement survived.</p>
     */
    @Test
    void switchToNvlForwardsCachedLeidenAndResolvedColors() throws Exception {
        String src = loadSource();

        Pattern switchToPattern = Pattern.compile(
                "public void switchTo\\(GraphEngine engine\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}",
                Pattern.MULTILINE);
        Matcher m = switchToPattern.matcher(src);
        assertTrue(m.find(), "switchTo(GraphEngine) must exist");
        String methodBody = m.group(1);

        // Find the NEO4J_NVL branch by anchoring on the `else if
        // (engine == GraphEngine.NEO4J_NVL)` marker; from that marker
        // to the matching outer `} else {` we slice manually by
        // counting braces so the inner layout-fallback if/else does
        // not fool the non-greedy regex.
        int branchStart = methodBody.indexOf("engine == GraphEngine.NEO4J_NVL");
        assertTrue(branchStart > 0,
                "switchTo() must contain an `else if (engine == GraphEngine.NEO4J_NVL)` branch");
        // Walk forward, counting braces, until depth returns to 0 — that
        // is the end of the NEO4J_NVL branch.
        int openIdx = methodBody.indexOf('{', branchStart);
        assertTrue(openIdx > 0, "NEO4J_NVL branch must be followed by '{'");
        int depth = 1;
        int i = openIdx + 1;
        while (i < methodBody.length() && depth > 0) {
            char c = methodBody.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        String branch = methodBody.substring(branchStart, i);

        assertTrue(branch.contains("nvlViewer.setLeidenClusterColors(currentLeidenColors)"),
                "switchTo(NEO4J_NVL) must forward the cached Leiden colors so the cluster recolor "
                        + "survives engine switches");
        assertTrue(branch.contains("nvlViewer.applyNodeColors("),
                "switchTo(NEO4J_NVL) must call nvlViewer.applyNodeColors(...) so the resolved "
                        + "per-node color map is pushed onto the fresh viewer");
    }
}
