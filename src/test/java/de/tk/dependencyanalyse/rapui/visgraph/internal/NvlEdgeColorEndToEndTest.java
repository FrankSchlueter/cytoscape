package de.tk.dependencyanalyse.rapui.visgraph.internal;

import com.google.gson.Gson;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.io.GraphFileParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the NVL "edges take source-node color" feature.
 *
 * <p>The bug this guards against: NVL edges used to ignore the source
 * node's color when that color was set via {@code GraphNode.setColor}
 * (directly, or via {@code GraphFileParser} reading a GML
 * {@code color} attribute). The {@code applyEdgeColors} lookup chain
 * only consulted {@code currentEffectiveColors} (Tag-/Cluster-colors)
 * and {@code currentLeidenColors} (Leiden clusters) — colors that
 * {@code setColor} never wrote to. Without the third fallback
 * ({@code nvl.getNodes()} lookup) NVL rendered every relationship in
 * its {@code defaultRelationshipColor} {@code #A0A0A0} even though the
 * source node had a perfectly valid hex color.</p>
 *
 * <p>The test loads {@code Einstufungsverlauf.gml} (the user-requested
 * fixture), feeds it through the {@code nvl-graph-viewer.js} IIFE inside
 * a GraalVM JS engine with a mocked browser environment and a mocked
 * NVL instance, and asserts that after the data load every
 * relationship ends up carrying the color of its source node. The NVL
 * mock records every {@code updateElementsInGraph([], updates)} call
 * — that is the wire-level signal the bridge would ship to the NVL
 * canvas to recolor edges, so checking its contents is equivalent to
 * checking the visual state.</p>
 */
class NvlEdgeColorEndToEndTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/nvl/nvl-graph-viewer.js",
            "target/classes/static/nvl/nvl-graph-viewer.js",
    };

    private static final Gson GSON = new Gson();

    private Context jsContext;
    private MockNvl mockNvl;

    @BeforeEach
    void setUp() {
        mockNvl = new MockNvl();
        jsContext = Context.newBuilder("js")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(name -> true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        installBrowserMock();
    }

    @AfterEach
    void tearDown() {
        if (jsContext != null) jsContext.close();
    }

    /**
     * Headline regression test. Loads {@code Einstufungsverlauf.gml},
     * serializes it via {@link GraphData#toNvlData}, runs it through
     * the real NVL bridge (in a GraalJS engine with a mocked NVL
     * instance) and asserts that every relationship in the resulting
     * graph carries the color of its source node.
     *
     * <p>This is the exact wire path the Java bridge would trigger
     * when a user opens the sample file in the NVL viewer: GML →
     * {@link GraphFileParser} → {@code GraphData} →
     * {@link de.tk.dependencyanalyse.rapui.visgraph.internal.NvlJsBridge#applyData}
     * → {@code window.__nvl_nodes} / {@code window.__nvl_relationships}
     * → {@code window.vgv_setData()} → NVL canvas.</p>
     */
    @Test
    void einstufungsverlaufEdgesCarrySourceNodeColor() throws Exception {
        GraphData data = loadEinstufungsverlauf();
        assertFalse(data.getNodes().isEmpty(), "Einstufungsverlauf.gml must yield nodes");
        assertFalse(data.getRelationships().isEmpty(), "Einstufungsverlauf.gml must yield edges");

        evalViewerScript();

        Map<String, Object> nvlPayload = data.toNvlData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) nvlPayload.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rels = (List<Map<String, Object>>) nvlPayload.get("relationships");

        // Verify the pre-conditions: the GML carries node colors and no
        // edge colors — that's the scenario the bug bites on.
        int nodesWithColor = 0;
        for (Map<String, Object> n : nodes) {
            Object c = n.get("color");
            if (c != null && !String.valueOf(c).isEmpty()) nodesWithColor++;
        }
        assertTrue(nodesWithColor >= 2,
                "fixture should carry node colors (got " + nodesWithColor + ")");
        for (Map<String, Object> r : rels) {
            assertTrue(r.get("color") == null || String.valueOf(r.get("color")).isEmpty(),
                    "fixture must not carry edge colors — the bridge derives them "
                            + "from the source node");
        }

        // Drive the bridge: assign __nvl_nodes / __nvl_relationships and
        // call vgv_setData() the same way the real bridge does.
        String atomicScript = "window.__nvl_nodes = " + GSON.toJson(nodes)
                + "; window.__nvl_relationships = " + GSON.toJson(rels)
                + "; window.vgv_setData();";

        jsContext.eval("js", atomicScript);

        // The mock captured every updateElementsInGraph call.
        Map<String, String> finalRelColor = mockNvl.lastKnownRelColor();
        assertTrue(mockNvl.totalAddCalls >= 1,
                "the bridge must add the new graph to NVL at least once");

        // Build a nodeId -> color map from the input nodes so we can
        // assert each rel's color equals its source node's color.
        Map<String, String> nodeColorById = new LinkedHashMap<>();
        for (Map<String, Object> n : nodes) {
            Object id = n.get("id");
            Object c = n.get("color");
            if (id != null && c != null && !String.valueOf(c).isEmpty()) {
                nodeColorById.put(String.valueOf(id), String.valueOf(c));
            }
        }
        assertFalse(nodeColorById.isEmpty(),
                "fixture must carry at least one colored source node, otherwise "
                        + "the assertion is vacuous");

        // Categorise: for every rel whose source node carries a color,
        // the rel must end up with exactly that color. Rels whose
        // source has no color stay uncolored (the GML intentionally
        // leaves some nodes uncolored, see Einstufungsverlauf.gml).
        int relsWithSourceColor = 0;
        int relsWithoutSourceColor = 0;
        int relsWithSourceColorMatching = 0;
        int relsWithSourceColorMismatching = 0;
        String firstMismatch = null;
        for (Map<String, Object> r : rels) {
            String rid = String.valueOf(r.get("id"));
            String from = String.valueOf(r.get("from"));
            String expected = nodeColorById.get(from);
            if (expected == null) {
                relsWithoutSourceColor++;
                continue;
            }
            relsWithSourceColor++;
            String actual = finalRelColor.get(rid);
            if (expected.equals(actual)) {
                relsWithSourceColorMatching++;
            } else {
                relsWithSourceColorMismatching++;
                if (firstMismatch == null) {
                    firstMismatch = "rel=" + rid + " from=" + from
                            + " expected=" + expected + " actual=" + actual;
                }
            }
        }
        assertTrue(relsWithSourceColorMatching > 0,
                "the bridge must have shipped at least one source-node color "
                        + "to a relationship (otherwise the third fallback in "
                        + "applyEdgeColors is a no-op)");
        assertEquals(0, relsWithSourceColorMismatching,
                "every NVL relationship whose source node carries a color must "
                        + "ship that color to the canvas. First mismatch: "
                        + firstMismatch + " (matched " + relsWithSourceColorMatching
                        + "/" + relsWithSourceColor + ", skipped "
                        + relsWithoutSourceColor + " uncolored sources)");
    }

    /**
     * Source-level guard: the third lookup stage in {@code applyEdgeColors}
     * must be implemented as a fresh {@code nvl.getNodes()} walk per call.
     * If a future refactor drops the {@code nvl.getNodes()} call from the
     * body (e.g. caches it once at module scope), the regression test above
     * would still pass against the current source, so we pin the
     * contract separately.
     */
    @Test
    void applyEdgeColorsBodyConsultsNvlGetNodes() throws Exception {
        String src = readViewerJs();
        int idx = src.indexOf("function applyEdgeColors");
        assertTrue(idx > 0, "nvl-graph-viewer.js must define applyEdgeColors()");
        int end = src.indexOf("\n    function ", idx + 10);
        if (end < 0) end = src.length();
        String body = src.substring(idx, end);
        assertTrue(body.contains("nvl.getNodes()"),
                "applyEdgeColors must consult nvl.getNodes() so edges pick up "
                        + "the source-node color set via GraphNode.setColor (third "
                        + "fallback after currentEffectiveColors / currentLeidenColors)");
    }

    /* ---------------------------------------------------------------- */
    /*  Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private String readViewerJs() throws java.io.IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("nvl-graph-viewer.js not found in any known location");
    }

    private GraphData loadEinstufungsverlauf() throws java.io.IOException {
        Path p = samplePath("Einstufungsverlauf.gml");
        assertNotNull(p, "Einstufungsverlauf.gml must be on the classpath / target");
        try (InputStream in = Files.newInputStream(p)) {
            return GraphFileParser.parse(in, GraphFileParser.Format.GML);
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

    /**
     * Evaluate the real {@code nvl-graph-viewer.js} IIFE inside the
     * GraalJS context. The browser mock installed in {@link #setUp}
     * provides just enough of {@code window} / {@code document} / NVL
     * for the IIFE to reach the {@code nvlReady=true} state without
     * throwing.
     */
    private void evalViewerScript() throws java.io.IOException {
        String src = readViewerJs();
        jsContext.eval("js", src);
    }

    /**
     * Install a minimal browser environment for the NVL IIFE. Every
     * browser API the IIFE touches during boot (DOM, ResizeObserver,
     * NVL constructor + interaction handlers) is provided here so the
     * real source can execute unchanged.
     */
    private void installBrowserMock() {
        // Expose the mock NVL instance under a polyglot name so the JS
        // Neo4jNVL constructor can store its bookkeeping there.
        jsContext.getBindings("js").putMember("__nvlMock", mockNvl);

        // Stand-in for the @neo4j-nvl/base constructor. Records every
        // call the bridge makes so we can assert state at the end of
        // the test.
        String mockScript = ""
                + "var __nvlMockHost = globalThis.__nvlMock;\n"
                // Build the JS-side NVL instance backed by the host mock.
                + "var __nvlInstance = (function() {\n"
                + "    var nodes = [];\n"
                + "    var rels = [];\n"
                + "    var nodeById = {};\n"
                + "    var relById = {};\n"
                + "    function applyUpdates(nodeList, relList) {\n"
                + "        if (nodeList) {\n"
                + "            for (var i = 0; i < nodeList.length; i++) {\n"
                + "                var n = nodeList[i];\n"
                + "                if (!n || !n.id) continue;\n"
                + "                if (nodeById[n.id]) {\n"
                + "                    if (n.color !== undefined) nodeById[n.id].color = n.color;\n"
                + "                } else {\n"
                + "                    nodeById[n.id] = { id: n.id, color: n.color };\n"
                + "                    nodes.push(nodeById[n.id]);\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "        if (relList) {\n"
                + "            for (var j = 0; j < relList.length; j++) {\n"
                + "                var r = relList[j];\n"
                + "                if (!r || !r.id) continue;\n"
                + "                if (relById[r.id]) {\n"
                + "                    if (r.color !== undefined) relById[r.id].color = r.color;\n"
                + "                } else {\n"
                + "                    relById[r.id] = { id: r.id, from: r.from, to: r.to, color: r.color };\n"
                + "                    rels.push(relById[r.id]);\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "    return {\n"
                + "        getNodes: function() { return nodes.slice(); },\n"
                + "        getRelationships: function() { return rels.slice(); },\n"
                + "        addAndUpdateElementsInGraph: function(nodeList, relList) {\n"
                + "            __nvlMockHost.totalAddCalls = (__nvlMockHost.totalAddCalls || 0) + 1;\n"
                + "            __nvlMockHost.totalRelsAdded = (__nvlMockHost.totalRelsAdded || 0) + (relList ? relList.length : 0);\n"
                + "            applyUpdates(nodeList, relList);\n"
                + "        },\n"
                + "        updateElementsInGraph: function(nodeList, relList) {\n"
                + "            __nvlMockHost.totalUpdateCalls = (__nvlMockHost.totalUpdateCalls || 0) + 1;\n"
                + "            // Capture the rel-color updates for assertion.\n"
                + "            if (relList) {\n"
                + "                for (var k = 0; k < relList.length; k++) {\n"
                + "                    var u = relList[k];\n"
                + "                    if (u && u.id) {\n"
                + "                        __nvlMockHost.lastRelColorUpdates[u.id] = u.color;\n"
                + "                        if (relById[u.id] && u.color !== undefined) relById[u.id].color = u.color;\n"
                + "                    }\n"
                + "                }\n"
                + "            }\n"
                + "            applyUpdates(nodeList, relList);\n"
                + "        },\n"
                + "        removeNodesWithIds: function(ids) {\n"
                + "            for (var i = (ids ? ids.length : 0) - 1; i >= 0; i--) {\n"
                + "                delete nodeById[ids[i]];\n"
                + "            }\n"
                + "            nodes = nodes.filter(function(n) { return !!nodeById[n.id]; });\n"
                + "        },\n"
                + "        removeRelationshipsWithIds: function(ids) {\n"
                + "            for (var i = (ids ? ids.length : 0) - 1; i >= 0; i--) {\n"
                + "                delete relById[ids[i]];\n"
                + "            }\n"
                + "            rels = rels.filter(function(r) { return !!relById[r.id]; });\n"
                + "        },\n"
                + "        getNodePositions: function() { return []; },\n"
                + "        setNodePositions: function() {},\n"
                + "        fit: function() {},\n"
                + "        setZoomAndPan: function() {},\n"
                + "        setPan: function() {},\n"
                + "        restart: function() {},\n"
                + "        render: function() {},\n"
                + "        on: function() { return this; },\n"
                + "        one: function() { return this; },\n"
                + "        off: function() { return this; },\n"
                + "        removeAllRelationships: function() { rels = []; relById = {}; },\n"
                + "        removeAllNodes: function() { nodes = []; nodeById = {}; }\n"
                + "    };\n"
                + "})();\n"
                + "globalThis.Neo4jNVL = function(container, nodes, rels, options, callbacks) {\n"
                + "    return __nvlInstance;\n"
                + "};\n"
                // Stub interaction handlers — the IIFE wires them during
                // boot, but we don't exercise click/drag/hover/pan/zoom
                // in this test. Each returns an object with updateCallback().
                + "function makeHandler() {\n"
                + "    var cb = {};\n"
                + "    return {\n"
                + "        updateCallback: function(name, fn) { cb[name] = fn; return this; },\n"
                + "        destroy: function() {}\n"
                + "    };\n"
                + "}\n"
                + "globalThis.Neo4jNVLInteractions = {\n"
                + "    DragNode: function() { return makeHandler(); },\n"
                + "    Click: function() { return makeHandler(); },\n"
                + "    Hover: function() { return makeHandler(); },\n"
                + "    Pan: function() { return makeHandler(); },\n"
                + "    Zoom: function() { return makeHandler(); }\n"
                + "};\n"
                // ResizeObserver stub — IIFE only calls .observe() on it.
                + "globalThis.ResizeObserver = function(cb) {\n"
                + "    this.observe = function() {};\n"
                + "    this.disconnect = function() {};\n"
                + "};\n"
                // Fake DOM element factory — IIFE calls
                // document.createElement, getElementById, body.appendChild,
                // addEventListener, querySelector and reads .style /
                // .innerHTML on these objects.
                + "function makeElement(tag) {\n"
                + "    var el = {\n"
                + "        tagName: (tag || 'div').toUpperCase(),\n"
                + "        id: '',\n"
                + "        style: {},\n"
                + "        cssText: '',\n"
                + "        className: '',\n"
                + "        innerHTML: '',\n"
                + "        textContent: '',\n"
                + "        children: [],\n"
                + "        appendChild: function(child) { this.children.push(child); return child; },\n"
                + "        removeChild: function(child) {\n"
                + "            this.children = this.children.filter(function(c){return c !== child;});\n"
                + "            return child;\n"
                + "        },\n"
                + "        addEventListener: function() {},\n"
                + "        removeEventListener: function() {},\n"
                + "        querySelector: function() { return null; },\n"
                + "        getBoundingClientRect: function() { return { left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600 }; }\n"
                + "    };\n"
                + "    return el;\n"
                + "}\n"
                // Pre-create the vgv-frame element so init() doesn't loop.
                + "var __vgvFrame = makeElement('div');\n"
                + "__vgvFrame.id = 'vgv-frame';\n"
                + "globalThis.document = {\n"
                + "    readyState: 'complete',\n"
                + "    getElementById: function(id) {\n"
                + "        if (id === 'vgv-frame') return __vgvFrame;\n"
                + "        if (id === 'vgv-color-palette') return null;\n"
                + "        if (id === 'vgv-context-menu') return null;\n"
                + "        return null;\n"
                + "    },\n"
                + "    createElement: function(tag) { return makeElement(tag); },\n"
                + "    body: makeElement('body'),\n"
                + "    addEventListener: function() {},\n"
                + "    removeEventListener: function() {},\n"
                + "    querySelector: function() { return null; },\n"
                + "    querySelectorAll: function() { return []; }\n"
                + "};\n"
                // GraalJS does not pre-define `window` (in browsers it
                // equals the global object). Make `window` an alias for
                // `globalThis` so the IIFE's `window.Neo4jNVL`,
                // `window.vgv_setData()`, etc. resolve.
                + "globalThis.window = globalThis;\n"
                // IIFE calls `window.addEventListener('resize', ...)` at
                // boot, plus a few other event-listener paths. Stub
                // them on the global object so the boot completes.
                + "globalThis.addEventListener = function() {};\n"
                + "globalThis.removeEventListener = function() {};\n"
                + "if (typeof globalThis.setTimeout !== 'function') {\n"
                + "    globalThis.setTimeout = function(fn, ms) { return 1; };\n"
                + "    globalThis.clearTimeout = function() {};\n"
                + "}\n"
                // Pre-register the Java callback so boot() can fire
                // vgv_viewerReady synchronously (otherwise it loops on
                // setTimeout(waitForViewerReadyWrapper, 50)).
                + "globalThis.vgv_viewerReady = function() {\n"
                + "    __nvlMockHost.viewerReadyCalls = (__nvlMockHost.viewerReadyCalls || 0) + 1;\n"
                + "};\n";

        jsContext.eval("js", mockScript);
    }

    /**
     * Host object backing the JS-side mock NVL. Records every
     * rel-color update so the test can assert which colors were
     * shipped to the canvas.
     */
    public static final class MockNvl {
        public int totalAddCalls = 0;
        public int totalUpdateCalls = 0;
        public int totalRelsAdded = 0;
        public int viewerReadyCalls = 0;
        /** relId → last color shipped via updateElementsInGraph. */
        public final Map<String, String> lastRelColorUpdates = new LinkedHashMap<>();

        /**
         * Reconstructs the final per-rel color by replaying the
         * updates against the rel payload captured during
         * addAndUpdateElementsInGraph. The JS-side mock stores these
         * directly on the host object via the bridge polyglot boundary.
         */
        public Map<String, String> lastKnownRelColor() {
            // The JS-side mock applies updates straight to the host's
            // lastRelColorUpdates map. We return a defensive copy.
            return new LinkedHashMap<>(lastRelColorUpdates);
        }
    }
}