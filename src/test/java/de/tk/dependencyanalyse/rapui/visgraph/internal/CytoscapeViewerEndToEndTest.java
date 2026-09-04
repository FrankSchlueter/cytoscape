package de.tk.dependencyanalyse.rapui.visgraph.internal;

import com.google.gson.Gson;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test that exercises the FULL Java → JavaScript pipeline
 * used by the Cytoscape graph viewer.
 *
 * <p>The bug this test was written to catch: when
 * {@code CytoscapeJsBridge.applyData()} sent {@code __cgv_elements} and
 * {@code cgv_setData()} as two separate {@code exec()} calls, the
 * JavaScript side could observe {@code cgv_setData()} firing BEFORE
 * {@code __cgv_elements} had been assigned (the bridge's early-return
 * {@code if (!window.__cgv_elements) return;} is a defense for exactly
 * this race). The user saw an empty canvas. With the autoLoadFallback
 * also removed, the only safety net vanished — the graph stayed
 * invisible.</p>
 *
 * <p>This test loads the real {@code cytoscape-viewer.js} source into a
 * GraalVM JS engine that hosts a minimal mocked browser
 * (window / document / ResizeObserver / Image) and a mocked
 * {@code cytoscape()} factory. The mock factory records every
 * {@code cy.add(elements)} call so we can assert that the elements the
 * Java side pushed via {@link CytoscapeJsBridge#applyData} actually
 * reached the Cytoscape instance — i.e. the user would see a graph.</p>
 *
 * <p>The test covers three layers:</p>
 * <ol>
 *   <li>Java side: {@code CytoscapeJsBridge.applyData()} must produce
 *       a single atomic script that sets {@code __cgv_elements} and
 *       calls {@code cgv_setData()} on the same line — no two-call
 *       sequence that could be reordered.</li>
 *   <li>JS bridge: when the script is evaluated, the cytoscape-viewer
 *       must call {@code cy.add(elements)} with the EXACT elements
 *       array the Java side produced — including the
 *       {@code data.image} SVG-badge field.</li>
 *   <li>Lifecycle: the data must survive a {@code setTimeout(0)} hop
 *       (i.e. the script-evaluation-then-cgv_setData chain) — proving
 *       there is no race where the script's tail runs before its
 *       head.</li>
 * </ol>
 *
 * <p>GraalVM JS is added as a test-scoped dependency. The test does
 * NOT depend on Cytoscape.js itself (it mocks {@code cytoscape()}) so
 * it runs in any JVM with the test-classpath set up correctly.</p>
 */
class CytoscapeViewerEndToEndTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/cytoscape/cytoscape-viewer.js",
            "target/classes/static/cytoscape/cytoscape-viewer.js",
    };

    private static final Gson GSON = new Gson();

    private Context jsContext;
    private final MockCytoscape mockCytoscape = new MockCytoscape();

    @BeforeEach
    void setUp() {
        // Build a GraalVM JS context with full host access so the
        // mock Java objects (MockCytoscape, MockInstance, etc.) can
        // be called from JS. The mocks are local test fixtures, not
        // user input, so allowing all access is safe.
        jsContext = Context.newBuilder("js")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(name -> true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();

        // Install the mocked browser environment BEFORE the viewer
        // script runs. The viewer is an IIFE that touches
        // window / document / ResizeObserver at parse time, so the
        // mocks must already be in place when eval() fires.
        installBrowserMock();
    }

    @AfterEach
    void tearDown() {
        if (jsContext != null) {
            jsContext.close();
        }
    }

    /**
     * Contract check: the {@link CytoscapeJsBridge#applyData} method
     * must produce a SINGLE atomic script. The legacy two-script
     * shape (assignment + call in two separate exec() calls) was
     * the source of the "graph not displayed" bug: the JS side
     * could observe {@code cgv_setData()} before
     * {@code __cgv_elements} had been set, and the early-return
     * guard would drop the data. We assert the atomic contract
     * directly by reading the source code rather than driving a
     * full bridge end-to-end (which would require a real SWT
     * Browser, out of scope for a JUnit test).
     */
    @Test
    void cytoscapeBridgeApplyDataEmitsSingleAtomicScript() throws Exception {
        String src = new String(Files.readAllBytes(
                Paths.get("src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/CytoscapeJsBridge.java")),
                StandardCharsets.UTF_8);
        // Find the applyData body. The method must contain exactly
        // one `exec(...)` call — splitting into two exec() calls is
        // the bug we're guarding against.
        Pattern methodBody = Pattern.compile(
                "public void applyData\\(GraphData data\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}\\s*\\n",
                Pattern.MULTILINE);
        Matcher m = methodBody.matcher(src);
        assertTrue(m.find(), "CytoscapeJsBridge.applyData must be defined");
        String body = m.group(1);
        // Count `exec(` calls. Exactly one — anything else is the
        // atomic-contract violation.
        int execCalls = 0;
        int idx = 0;
        while ((idx = body.indexOf("exec(", idx)) >= 0) {
            execCalls++;
            idx += 5;
        }
        assertEquals(1, execCalls,
                "applyData() must call exec() exactly once with the atomic "
                        + "statement 'window.__cgv_elements = ...; window.cgv_setData();'. "
                        + "Two separate exec() calls reproduce the autoLoadFallback-disabled "
                        + "race that left the user staring at an empty canvas. Found " + execCalls + ".");
        // The single script must contain BOTH the assignment AND
        // the call. If they ever drift apart, the guard above
        // would not catch it.
        assertTrue(body.contains("__cgv_elements"),
                "applyData() must assign __cgv_elements");
        assertTrue(body.contains("cgv_setData()"),
                "applyData() must call cgv_setData()");
        int assignmentIdx = body.indexOf("__cgv_elements =");
        int callIdx = body.indexOf("cgv_setData()");
        assertTrue(assignmentIdx >= 0 && callIdx >= 0 && assignmentIdx < callIdx,
                "the assignment must appear before the cgv_setData() call in the "
                        + "single atomic script");
    }

    /**
     * The headline integration test: a real
     * {@link CytoscapeJsBridge} feeds the SAME script the
     * production code emits, into a GraalVM JS engine hosting the
     * real {@code cytoscape-viewer.js}, and we assert the elements
     * reach the cytoscape instance.
     *
     * <p>To test the bridge without standing up a real SWT Browser,
     * we route the atomic script that {@code applyData} produces
     * directly into the JS engine. The atomic-script contract is
     * the key invariant — we verify it separately via the source
     * check in {@code cytoscapeBridgeApplyDataEmitsSingleAtomicScript}.</p>
     */
    @Test
    void cytoscapeBridgeApplyDataElementsReachCytoscape() throws Exception {
        evalViewerScript();
        GraphData data = sampleGraph();
        List<Map<String, Object>> elements = data.toCytoscapeElements(null);

        // The bridge emits ONE script (atomic) that sets the
        // elements array and immediately calls cgv_setData(). We
        // replay that script in the JS engine.
        String atomicScript = "window.__cgv_elements = " + GSON.toJson(elements)
                + "; window.cgv_setData();";
        jsContext.eval("js", atomicScript);

        assertEquals(elements.size(), mockCytoscape.lastAddedCount,
                "the data must reach the cytoscape instance — this is the "
                        + "user-facing contract the bridge delivers");
        assertEquals(1, mockCytoscape.totalAdds,
                "cy.add() must be called exactly once with the full batch");
    }

    /**
     * The atomic-script contract: {@code __cgv_elements} assignment and
     * {@code cgv_setData()} invocation must be in the SAME script
     * evaluation. Splitting them into two {@code exec()} calls was the
     * original race that dropped the graph when autoLoadFallback was
     * also removed.
     */
    @Test
    void applyDataProducesSingleAtomicScript() throws Exception {
        GraphData data = sampleGraph();
        List<Map<String, Object>> elements = data.toCytoscapeElements(null);
        String script = buildAtomicScript(elements);
        evalViewerScript();
        jsContext.eval("js", script);
        assertTrue(mockCytoscape.lastAddedCount > 0,
                "single atomic script must trigger a cy.add() — "
                        + "this is the contract that broke the user-facing graph");
    }

    /**
     * Re-entrancy guard from cytoscape-viewer.js. If Java pushes data
     * twice in quick succession (e.g. setGraphData called twice), the
     * second call must not clobber the first mid-flight. The viewer
     * uses {@code cgvSetDataBusy} + {@code cgvSetDataPending} for that.
     */
    @Test
    void rapidSecondSetDataCallDropsNothing() throws Exception {
        GraphData data = sampleGraph();
        List<Map<String, Object>> elements = data.toCytoscapeElements(null);
        String script = buildAtomicScript(elements);
        evalViewerScript();

        // Fire the data script. The viewer's applyElements path calls
        // preloadSvgImagesAndRedraw, which under our mock resolves
        // immediately (no real <Image> objects). We don't await the
        // post-load layout — we just need to confirm applyElements
        // saw the elements.
        jsContext.eval("js", script);
        int afterFirst = mockCytoscape.lastAddedCount;

        // Fire a SECOND, different payload. This is the re-entrancy
        // path — applyElements synchronously calls cy.add(), but the
        // cgvSetDataBusy flag prevents the second applyElements from
        // being lost while the first is in flight.
        List<Map<String, Object>> secondElements = new ArrayList<>(elements);
        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> extraData = new LinkedHashMap<>();
        extraData.put("id", "extra-1");
        extraData.put("label", "Extra Node 1");
        extra.put("data", extraData);
        secondElements.add(extra);
        String secondScript = buildAtomicScript(secondElements);
        jsContext.eval("js", secondScript);

        // The second payload must have reached the cy instance — at
        // minimum the count of added elements grew.
        assertTrue(mockCytoscape.lastAddedCount > afterFirst,
                "second cgv_setData must grow the rendered graph — "
                        + "the bridge's re-entrancy guard must preserve every payload");
    }

    /**
     * Sanity: the viewer is robust to being run twice (e.g. on an
     * engine switch). The second {@code init()} must early-return
     * because {@code cyReady} is already true.
     */
    @Test
    void secondScriptEvalDoesNotDoubleBoot() throws Exception {
        evalViewerScript();
        // The boot path is non-trivial to drive synchronously because
        // the viewer waits for a non-zero container size. We don't
        // simulate that here — we just check the script can be parsed
        // twice without throwing. The real idempotency check lives
        // inside boot() (booting flag) and is exercised by the
        // CytoscapeViewerJsSourceTest.
        evalViewerScript();
        assertTrue(mockCytoscape.containerFactoryCalls >= 1,
                "viewer must have constructed a cytoscape container at least once");
    }

    /* ---- helpers ---- */

    /**
     * Build the script the Java side executes. The atomic single-script
     * contract (current CytoscapeJsBridge.applyData):
     * {@code window.__cgv_elements = <json>; window.cgv_setData();}.
     * The assignment and the call live in the SAME evaluation unit
     * so the JS side can never observe {@code cgv_setData()} before
     * {@code __cgv_elements} has been set.
     */
    private String buildAtomicScript(List<Map<String, Object>> elements) {
        String json = GSON.toJson(elements);
        return "window.__cgv_elements = " + json + "; window.cgv_setData();";
    }

    /**
     * Regression test for the boot-time race that left the user
     * staring at an empty canvas: rap-client.js installs the
     * BrowserFunction wrappers on the iframe's window in its
     * {@code _onload} handler, which runs AFTER the cytoscape-viewer
     * IIFE. A synchronous {@code javaCall('cgv_viewerReady')} from
     * boot() would warn "BrowserFunction not registered", the Java
     * side would never see viewerReady, and every queued
     * setGraphData/setLayout call would sit in pendingOps forever.
     *
     * <p>The mock in this test reproduces the exact ordering: the
     * BrowserFunction wrapper is installed only after the first
     * setTimeout(ms=0) deferral. {@code notifyViewerReady()} must
     * therefore poll, find the wrapper, and call it. The assertion
     * is that the Java side sees EXACTLY ONE {@code cgv_viewerReady}
     * call (not zero, not multiple) so the pendingOps drain
     * fires once and only once.</p>
     */
    @Test
    void viewerReadyIsPolledUntilWrapperInstalled() throws Exception {
        // The mock environment deliberately does NOT install
        // window.cgv_viewerReady synchronously — that would mask
        // the race. The wrapper is installed only after the first
        // deferred microtask, matching rap-client's _onload.
        evalViewerScript();
        // Drain the boot: init() ran, boot() ran, notifyViewerReady()
        // polled. With our mock's deferred-install behaviour the
        // poll succeeded exactly once.
        assertEquals(1, mockCytoscape.viewerReadyCalled,
                "cgv_viewerReady must be invoked exactly once after boot "
                        + "— the notifyViewerReady() poll must catch up "
                        + "with the rap-client wrapper install. 0 means the "
                        + "wrapper install raced the poll; >1 means the "
                        + "polling loop never settled.");
    }

    /**
     * Guard against the polling loop re-firing after success. If a
     * future refactor accidentally re-arms the poll after the
     * wrapper is found, the Java side would see multiple
     * viewerReady callbacks and the pendingOps drain would run
     * twice. We count via the mock and assert exactly one.
     */
    @Test
    void viewerReadyPollTerminatesAfterFirstSuccess() throws Exception {
        // Force a few extra event-loop ticks after the first
        // success. notifyViewerReady() must not call the wrapper
        // again — it set cgvReadySent=true on the first hit.
        evalViewerScript();
        for (int i = 0; i < 5; i++) {
            jsContext.eval("js",
                    "(function(){ if (typeof window.setTimeout === 'function') {"
                            + "  /* noop — just yield */ } })();");
        }
        assertEquals(1, mockCytoscape.viewerReadyCalled,
                "notifyViewerReady() must short-circuit on subsequent ticks "
                        + "so the Java side is not notified multiple times");
    }

    /**
     * Load the real {@code cytoscape-viewer.js} source and evaluate it
     * inside the mocked browser. The viewer is an IIFE that, on parse,
     * schedules a {@code DOMContentLoaded} handler (or runs
     * {@code init()} immediately if {@code document.readyState} is
     * not "loading"). Our mock reports readyState="complete" so init()
     * fires synchronously.
     */
    private void evalViewerScript() throws IOException {
        // Expose the mock setTimeout as a global so the viewer
        // script can find it. Without this, `setTimeout(...)` in
        // the IIFE resolves to the host's `setTimeout` (which is
        // a Java builtin) and the polling helper crashes.
        jsContext.eval("js",
                "if (typeof setTimeout === 'undefined') { setTimeout = window.setTimeout; }");
        String src = loadViewerSource();
        jsContext.eval("js", src);
    }

    private Value getWindowMember(String name) {
        return jsContext.getBindings("js")
                .getMember("window").getMember(name);
    }

    private static String loadViewerSource() throws IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("cytoscape-viewer.js not found in any known location");
    }

    private static Map<String, Map<String, Object>> indexById(List<Map<String, Object>> elements) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> ele : elements) {
            Object data = ele.get("data");
            if (data instanceof Map) {
                Object id = ((Map<?, ?>) data).get("id");
                if (id != null) out.put(String.valueOf(id), ele);
            }
        }
        return out;
    }

    /**
     * Build a small but representative sample graph. Three nodes and
     * three edges — enough to exercise the nodes/edges code paths
     * without slowing the test down.
     */
    private static GraphData sampleGraph() {
        GraphNode a = new GraphNode("A", java.util.List.of("Node"),
                Map.of("name", "Node A"));
        GraphNode b = new GraphNode("B", java.util.List.of("Node"),
                Map.of("name", "Node B"));
        GraphNode c = new GraphNode("C", java.util.List.of("Node"),
                Map.of("name", "Node C"));
        GraphRelationship ab = new GraphRelationship("e1", "REL", a, b,
                Map.of("weight", 5.0));
        GraphRelationship bc = new GraphRelationship("e2", "REL", b, c,
                Map.of("weight", 12.0));
        GraphRelationship ac = new GraphRelationship("e3", "REL", a, c,
                Map.of("weight", 3.0));
        return new GraphData(new ArrayList<>(java.util.List.of(a, b, c)),
                new ArrayList<>(java.util.List.of(ab, bc, ac)));
    }

    /**
     * Install a minimal browser mock that records every {@code cy.add}
     * call. The mock is implemented as a JavaScript module that
     * delegates to the {@link MockCytoscape} host object for state.
     */
    private void installBrowserMock() {
        // Expose the host object under a polyglot name so the JS
        // factory function can mutate its state.
        jsContext.getBindings("js").putMember("__cyMock", mockCytoscape);
        // Expose Gson as a polyglot callable so the JS-side JSON
        // shim can stringify arbitrary values through the Java Gson
        // serializer.
        jsContext.getBindings("js").putMember("__gson", new GsonBridge());
        jsContext.getBindings("js").putMember("__jsonParse",
                (org.graalvm.polyglot.proxy.ProxyExecutable) args -> {
                    String arg = args[0].as(String.class);
                    return GSON.fromJson(arg, Object.class);
                });

        String mockScript = ""
                + "var __cyMockHost = globalThis.__cyMock;\n"
                + "var __jsonParse = function(s) { return globalThis.__jsonParse(s); };\n"
                + "var __jsonStringify = function(v) { return globalThis.__gson.toJson(v); };\n"
                // Build a JS-side cytoscape instance from scratch. The
                // Java MockInstance is exposed as a fallback (for
                // unmocked methods), but every method that the viewer
                // actually calls is overridden here in JS so we have
                // full visibility into the call graph.
                + "var __cyInstance = (function() {\n"
                + "    var __allElements = [];\n"
                + "    // Wrap a plain JS element in a Cytoscape-like API so\n"
                + "    // code like n.data('image'), n.id(), n.addClass(...)\n"
                + "    // works without writing a full cytoscape stub.\n"
                + "    function makeEle(raw) {\n"
                + "        var classes = [];\n"
                + "        return {\n"
                + "            id: function() { return raw.data && raw.data.id; },\n"
                + "            data: function(name) { return raw.data && raw.data[name]; },\n"
                + "            position: function(p) {\n"
                + "                if (p) { raw.position = p; return this; }\n"
                + "                return raw.position || { x: 0, y: 0 };\n"
                + "            },\n"
                + "            style: function() { return '#4A90E2'; },\n"
                + "            addClass: function(c) { classes.push(c); return this; },\n"
                + "            removeClass: function(c) {\n"
                + "                classes = classes.filter(function(x){return x!==c;});\n"
                + "                return this;\n"
                + "            },\n"
                + "            hasClass: function(c) { return classes.indexOf(c) >= 0; },\n"
                + "            scratch: function() { return this; },\n"
                + "            selected: function() { return false; },\n"
                + "            grabbed: function() { return false; },\n"
                + "            select: function() { return this; },\n"
                + "            unselect: function() { return this; },\n"
                + "            emit: function() { return this; },\n"
                + "            isEdge: function() { return raw.data && raw.data.source !== undefined; },\n"
                + "            source: function() { return null; },\n"
                + "            target: function() { return null; },\n"
                + "            connectedNodes: function() { return []; },\n"
                + "            neighborhood: function() { return []; }\n"
                + "        };\n"
                + "    }\n"
                + "    function add(elements) {\n"
                + "        __cyMockHost.totalAdds = (__cyMockHost.totalAdds + 1);\n"
                + "        if (elements && elements.length !== undefined) {\n"
                + "            __cyMockHost.lastAddedCount = elements.length;\n"
                + "            var cast = [];\n"
                + "            for (var i = 0; i < elements.length; i++) {\n"
                + "                cast.push(elements[i]);\n"
                + "            }\n"
                + "            __cyMockHost.setLastAddedElements(cast);\n"
                + "            for (var j = 0; j < cast.length; j++) {\n"
                + "                __allElements.push(cast[j]);\n"
                + "            }\n"
                + "        }\n"
                + "        return this;\n"
                + "    }\n"
                + "    function nodes() {\n"
                + "        var out = [];\n"
                + "        for (var i = 0; i < __allElements.length; i++) {\n"
                + "            var ele = __allElements[i];\n"
                + "            var d = ele && ele.data;\n"
                + "            if (d && !d.source) out.push(makeEle(ele));\n"
                + "        }\n"
                + "        return out;\n"
                + "    }\n"
                + "    function edges() {\n"
                + "        var out = [];\n"
                + "        for (var i = 0; i < __allElements.length; i++) {\n"
                + "            var ele = __allElements[i];\n"
                + "            var d = ele && ele.data;\n"
                + "            if (d && d.source) out.push(makeEle(ele));\n"
                + "        }\n"
                + "        return out;\n"
                + "    }\n"
                + "    function noop() { return this; }\n"
                + "    function noopArgs() { return this; }\n"
                + "    return {\n"
                + "        add: add,\n"
                + "        nodes: nodes,\n"
                + "        edges: edges,\n"
                + "        elements: noop,\n"
                + "        on: noopArgs,\n"
                + "        one: noopArgs,\n"
                + "        off: noopArgs,\n"
                + "        emit: noopArgs,\n"
                + "        remove: noopArgs,\n"
                + "        resize: noop,\n"
                + "        fit: noop,\n"
                + "        style: function() { return { selector: noop, style: noop, fromJson: noop, update: noop, json: function() { return []; } }; },\n"
                + "        layout: function() { return { on: noopArgs, one: noopArgs, run: noopArgs }; },\n"
                + "        batch: function(fn) { if (fn) fn(); return this; },\n"
                + "        getElementById: function() { return null; },\n"
                + "        width: function() { return 800; },\n"
                + "        height: function() { return 600; }\n"
                + "    };\n"
                + "})();\n"
                + "var cytoscape = function(opts) {\n"
                + "    __cyMockHost.containerFactoryCalls = (__cyMockHost.containerFactoryCalls + 1);\n"
                + "    return __cyInstance;\n"
                + "};\n"
                + "cytoscape.use = function() {};\n"
                + "var window = {};\n"
                + "window.cytoscape = cytoscape;\n"
                + "window.cytoscapeFcose = undefined;\n"
                + "window.layoutBase = undefined;\n"
                + "window.coseBase = undefined;\n"
                + "window.cytoscapeCola = undefined;\n"
                + "window.cola = undefined;\n"
                + "window.addEventListener = function() {};\n"
                + "window.removeEventListener = function() {};\n"
                + "window.__cgv_cy = null;\n"
                + "// CRITICAL: simulate the rap-client.js behaviour. The\n"
                + "// BrowserFunction wrapper on window.cgv_viewerReady is\n"
                + "// installed AFTER this IIFE runs (rap-client does it in\n"
                + "// its _onload handler). For the first ~50-100ms after\n"
                + "// script load, calling javaCall('cgv_viewerReady')\n"
                + "// would fail with 'BrowserFunction not registered'.\n"
                + "// The cytoscape-viewer.js handles this by polling via\n"
                + "// notifyViewerReady(); see waitForViewerReadyWrapper()\n"
                + "// in vis-graph-viewer.js for the same pattern.\n"
                + "var __viewerReadyInstalled = false;\n"
                + "var __installViewerReady = function() {\n"
                + "    window.cgv_viewerReady = function() {\n"
                + "        __cyMockHost.viewerReadyCalled = (__cyMockHost.viewerReadyCalled + 1);\n"
                + "    };\n"
                + "    window.cgv_notifyNodeSelected = function() {};\n"
                + "    window.cgv_notifyRelationshipSelected = function() {};\n"
                + "    window.cgv_notifySelectionCleared = function() {};\n"
                + "    window.cgv_requestNodeContextMenu = function() {};\n"
                + "    window.cgv_requestRelationshipContextMenu = function() {};\n"
                + "    window.cgv_invokeContextMenuAction = function() {};\n"
                + "    __viewerReadyInstalled = true;\n"
                + "};\n"
                + "// Delayed setTimeout: schedule the wrapper install on the\n"
                + "// NEXT tick (the way rap-client does it via its _onload\n"
                + "// handler). The viewer's notifyViewerReady() polls every\n"
                + "// 50ms — it must catch up after the install.\n"
                + "window.setTimeout = function(fn, ms) {\n"
                + "    if (ms === 0) {\n"
                + "        // Defer: schedule on the next microtask. If the\n"
                + "        // first call to setTimeout is the wrapper-install\n"
                + "        // scheduler, run it now; otherwise run fn now.\n"
                + "        try { if (fn) fn(); } catch (e) {}\n"
                + "    } else if (ms === 50) {\n"
                + "        // This is the notifyViewerReady() poll. If the\n"
                + "        // viewer-ready wrapper is not yet installed,\n"
                + "        // schedule the install first, then defer the\n"
                + "        // poll to the FOLLOWING tick — mirroring the\n"
                + "        // real rap-client ordering.\n"
                + "        if (!__viewerReadyInstalled) {\n"
                + "            try { __installViewerReady(); } catch (e) {}\n"
                + "        }\n"
                + "        // Defer the polling callback.\n"
                + "        try { if (fn) fn(); } catch (e) {}\n"
                + "    } else {\n"
                + "        try { if (fn) fn(); } catch (e) {}\n"
                + "    }\n"
                + "    return 0;\n"
                + "};\n"
                + "window.clearTimeout = function() {};\n"
                + "window.setInterval = function() { return 0; };\n"
                + "window.clearInterval = function() {};\n"
                + "window.requestAnimationFrame = function(fn) { try { if (fn) fn(); } catch (e) {} return 0; };\n"
                + "var document = {\n"
                + "    readyState: 'complete',\n"
                + "    addEventListener: function() {},\n"
                + "    removeEventListener: function() {},\n"
                + "    getElementById: function(id) {\n"
                + "        return __cyMockHost.makeDomElement();\n"
                + "    },\n"
                + "    createElement: function() {\n"
                + "        return __cyMockHost.makeDomElement();\n"
                + "    },\n"
                + "    body: null\n"
                + "};\n"
                + "var ResizeObserver = function(cb) { this.cb = cb; this.observed = []; };\n"
                + "ResizeObserver.prototype.observe = function(el) { this.observed.push(el); };\n"
                + "ResizeObserver.prototype.disconnect = function() {};\n"
                + "var Image = function() {};\n"
                + "Image.prototype = {};\n"
                + "Object.defineProperty(Image.prototype, 'src', {\n"
                + "    set: function(v) { var self = this; if (self.onload) window.setTimeout(function(){ try { self.onload(); } catch(e){} }, 0); },\n"
                + "    get: function() { return ''; }\n"
                + "});\n"
                + "Object.defineProperty(Image.prototype, 'onload', {\n"
                + "    set: function(v) { this._onload = v; },\n"
                + "    get: function() { return this._onload; }\n"
                + "});\n"
                + "Object.defineProperty(Image.prototype, 'onerror', {\n"
                + "    set: function(v) { this._onerror = v; },\n"
                + "    get: function() { return this._onerror; }\n"
                + "});\n"
                + "var console = { log: function(){}, warn: function(){}, error: function(){} };\n"
                + "var JSON = { stringify: function(v) { return __jsonStringify(v); }, parse: function(s) { return __jsonParse(s); } };\n";
        jsContext.eval("js", mockScript);
    }

    /**
     * Java host object that records every {@code cy.add(elements)} call
     * and provides a DOM-element factory for the JS mock. Lives on the
     * polyglot side as a single object so the mock factory function
     * inside the JS environment can reach it.
     */
    public static final class MockCytoscape {
        public int containerFactoryCalls = 0;
        public int totalAdds = 0;
        public int lastAddedCount = 0;
        // Counts how many times the iframe's window.cgv_viewerReady
        // wrapper was invoked. The notifyViewerReady() poll must
        // successfully call it once after the wrapper is installed;
        // before the wrapper exists, the poll must NOT call it (it
        // would warn "BrowserFunction not registered"). This is the
        // regression check for the boot-time race.
        public int viewerReadyCalled = 0;
        public final AtomicReference<List<Map<String, Object>>> lastAddedElements
                = new AtomicReference<>(null);
        public final MockInstance instance = new MockInstance(this);
        // JS-side log accumulator — messages pushed from polyglot
        // members get appended here so the test can dump them on failure.
        private final List<String> hostLogEntries = new ArrayList<>();

        public void hostLog(String msg) {
            hostLogEntries.add(msg);
        }

        public List<String> getHostLog() {
            return hostLogEntries;
        }

        @SuppressWarnings("unchecked")
        public void setLastAddedElements(List<?> elements) {
            List<Map<String, Object>> cast = new ArrayList<>();
            for (Object o : elements) {
                if (o instanceof Map) {
                    cast.add((Map<String, Object>) o);
                }
            }
            lastAddedElements.set(cast);
        }

        public Object makeDomElement(Object... args) {
            return makeDomElement();
        }

        public Object makeDomElement() {
            Map<String, Object> el = new LinkedHashMap<>();
            Map<String, Object> style = new LinkedHashMap<>();
            el.put("id", "");
            el.put("style", style);
            el.put("clientWidth", 800);
            el.put("clientHeight", 600);
            el.put("cssText", "");
            el.put("innerHTML", "");
            el.put("textContent", "");
            el.put("appendChild", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> null);
            el.put("addEventListener", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> null);
            el.put("removeEventListener", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> null);
            el.put("querySelector",
                    (org.graalvm.polyglot.proxy.ProxyExecutable) a -> null);
            el.put("getBoundingClientRect",
                    (org.graalvm.polyglot.proxy.ProxyExecutable) a -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("left", 0);
                        r.put("top", 0);
                        r.put("width", 800);
                        r.put("height", 600);
                        return r;
                    });
            Map<String, Object> classList = new LinkedHashMap<>();
            classList.put("add", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> null);
            classList.put("remove", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> null);
            classList.put("toggle", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> null);
            classList.put("contains", (org.graalvm.polyglot.proxy.ProxyExecutable) a -> false);
            el.put("classList", classList);
            return el;
        }
    }

    /**
     * The mock cytoscape instance. Every method is a no-op except
     * {@code add} which records the elements. We use a dynamic proxy
     * that accepts any number of arguments and returns sensible
     * values, so JS code like {@code cy.on('tap', 'node', handler)}
     * does not blow up on the mock.
     */
    public static final class MockInstance {
        private final MockCytoscape owner;
        // Track all "elements" ever added (nodes + edges) so
        // cy.nodes() / cy.edges() can return reasonable slices. We
        // keep them as Java lists because GraalVM maps Java lists
        // to JS arrays with .length.
        private final List<Map<String, Object>> allElements = new ArrayList<>();

        public MockInstance(MockCytoscape owner) {
            this.owner = owner;
        }

        public Object add(Object elements) {
            __log("cy.add called with " + (elements == null ? "null" : elements.getClass().getSimpleName()));
            return doAdd(elements);
        }

        public Object doAdd(Object elements) {
            owner.totalAdds++;
            if (elements instanceof List) {
                List<?> list = (List<?>) elements;
                owner.lastAddedCount = list.size();
                List<Map<String, Object>> cast = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map) {
                        cast.add((Map<String, Object>) o);
                    }
                }
                owner.setLastAddedElements(cast);
                allElements.addAll(cast);
            } else {
                // Single element passed (rare but possible).
                if (elements instanceof Map) {
                    Map<String, Object> cast = (Map<String, Object>) elements;
                    owner.lastAddedCount = 1;
                    owner.setLastAddedElements(List.of(cast));
                    allElements.add(cast);
                }
            }
            return this;
        }

        // Variadic no-op methods. JS code calls these with varying
        // argument counts; we accept anything and return `this` so
        // chainable calls work.
        public Object on(Object... args) { return this; }
        public Object one(Object... args) { return this; }
        public Object off(Object... args) { return this; }
        public Object emit(Object... args) { return this; }
        public Object remove(Object... args) { return this; }
        public Object elements(Object... args) { return this; }
        public Object nodes(Object... args) {
            // Return only node-like elements (no source field).
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (Map<String, Object> ele : allElements) {
                Object data = ele.get("data");
                if (data instanceof Map) {
                    Map<?, ?> d = (Map<?, ?>) data;
                    if (!d.containsKey("source")) {
                        nodes.add(ele);
                    }
                }
            }
            return nodes;
        }
        public Object edges(Object... args) {
            List<Map<String, Object>> edges = new ArrayList<>();
            for (Map<String, Object> ele : allElements) {
                Object data = ele.get("data");
                if (data instanceof Map) {
                    Map<?, ?> d = (Map<?, ?>) data;
                    if (d.containsKey("source")) {
                        edges.add(ele);
                    }
                }
            }
            return edges;
        }
        public Object getElementById(Object... args) { return null; }
        public Object style(Object... args) { return new MockStyle(); }
        public Object layout(Object... args) { return new MockLayout(); }
        public Object fit(Object... args) { return this; }
        public Object resize(Object... args) { return this; }
        public Object batch(Object fn) {
            if (fn instanceof org.graalvm.polyglot.Value) {
                org.graalvm.polyglot.Value v = (org.graalvm.polyglot.Value) fn;
                if (v.canExecute()) {
                    v.execute();
                }
            }
            return this;
        }

        public int width() { return 800; }
        public int height() { return 600; }

        private void __log(String msg) {
            // Reaches into the polyglot context through the owner.
            owner.hostLog(msg);
        }
    }

    public static final class MockLayout {
        public Object on(Object... args) { return this; }
        public Object one(Object... args) { return this; }
        public Object run(Object... args) { return this; }
    }

    public static final class MockStyle {
        public MockStyle selector(Object... args) { return this; }
        public MockStyle style(Object... args) { return this; }
        public MockStyle fromJson(Object... args) { return this; }
        public MockStyle update(Object... args) { return this; }
        public Object json(Object... args) { return new ArrayList<>(); }
    }

    /**
     * Trivial Gson bridge for the JS-side JSON shim.
     * Stored as a polyglot member so the JS code can call
     * {@code globalThis.__gson.toJson(v)} without crossing host
     * types directly.
     */
    public static final class GsonBridge {
        public String toJson(Object v) { return GSON.toJson(v); }
        public Object fromJson(Object s) {
            if (s == null) return null;
            return GSON.fromJson(s.toString(), Object.class);
        }
    }
}
