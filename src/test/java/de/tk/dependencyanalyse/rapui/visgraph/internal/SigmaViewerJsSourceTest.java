package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Source-level regression guards for {@code sigma-viewer.js}. The bridge
 * is JS-only — no Java unit tests can exercise the WebGL renderer or the
 * fetch path against a real browser — but the file MUST satisfy a set of
 * structural contracts:
 *
 * <ul>
 *   <li>Data is delivered via {@code fetch(...)} against the REST endpoints,
 *       NOT by Java pushing globals like {@code __vg_nodes}/{@code __vg_edges}
 *       into the iframe (the legacy vis-network / Cytoscape pattern is
 *       explicitly forbidden for sigma).</li>
 *   <li>{@code runForceDirected2(graph)} performs two layout passes —
 *       FA2 (with a log10-weight-driven edgeWeight) and NoOverlap (with
 *       a fit-to-container ratio) — and pins the graph spread to ~85 %
 *       of the canvas width.</li>
 *   <li>Selection / context-menu events are wired back to Java via
 *       {@code vg_notifyNodeSelected} / {@code vg_notifyRelationshipSelected} /
 *       {@code vg_requestNodeContextMenu} / {@code vg_requestRelationshipContextMenu}.</li>
 *   <li>The script parses correctly with a JS engine when available, else
 *       passes a brace-depth heuristic (same as the vis-network test).</li>
 * </ul>
 */
class SigmaViewerJsSourceTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/sigma/sigma-viewer.js",
            "target/classes/static/sigma/sigma-viewer.js",
    };

    private static String readViewerJs() throws IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("sigma-viewer.js not found in any known location");
    }

    @Test
    void sigmaViewerScriptLoadsOverFetchNotLegacyGlobals() throws Exception {
        String src = readViewerJs();
        // The bridge MUST use fetch() — the legacy vis/cytoscape pattern of
        // setting window.__vg_nodes / window.__vg_edges is explicitly forbidden.
        assertTrue(src.contains("function fetchWithEtag"),
                "sigma-viewer.js must implement fetchWithEtag() to load the graph payload");
        assertTrue(src.contains("fetch("),
                "sigma-viewer.js must invoke fetch(url, ...) to load nodes/edges");
        assertFalse(src.contains("window.__vg_nodes =") || src.contains("__vg_nodes ="),
                "sigma-viewer.js must not assign global __vg_nodes — payload comes via fetch");
        assertFalse(src.contains("window.__vg_edges =") || src.contains("__vg_edges ="),
                "sigma-viewer.js must not assign global __vg_edges — payload comes via fetch");
    }

    @Test
    void forceDirected2RunsFA2ThenNoOverlap() throws Exception {
        String src = readViewerJs();
        // We test against the full source rather than the function body
        // because the function contains nested arrow functions whose
        // braces confuse the simple regex matcher.
        assertTrue(src.contains("function runForceDirected2(graph)"),
                "runForceDirected2(graph) must be defined");
        assertTrue(src.contains("graphologyLayoutForceAtlas2.assign"),
                "sigma-viewer.js must call graphology-layout-forceatlas2.assign(graph, ...)");
        assertTrue(src.contains("graphologyLayoutNoverlap.assign"),
                "sigma-viewer.js must call graphology-layout-noverlap.assign(graph, ...) as a post-processing step");
        // The edge-weight callback uses log10(weight + 1) so high-weight edges
        // pull nodes closer. Allow either a one-line `Math.log10(... + 1)`
        // OR the explicit form `log10(w + 1)` (both are accepted by graphology).
        assertTrue(src.contains("Math.log10(") && (src.contains("weight + 1") || src.contains("+ 1)"))
                        && src.contains("edgeWeight"),
                "runForceDirected2 must compute edge weight from log10(weight + 1)");
    }

    @Test
    void forceDirected2UsesContainerWidthForRatio() throws Exception {
        String src = readViewerJs();
        // The Fit-to-width heuristic: ratio pinned to ~85 % of containerW divided
        // by the current axis-spread. Loose pattern because the calculation
        // is spread across several lines.
        assertTrue(src.contains("clientWidth") && src.contains("0.85"),
                "runForceDirected2 must read clientWidth and scale by 0.85 for the canvas-fit ratio");
    }

    @Test
    void selectionCallbacksAreWired() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("vg_notifyNodeSelected"),
                "sigma-viewer.js must call vg_notifyNodeSelected on clickNode");
        assertTrue(src.contains("vg_notifyRelationshipSelected"),
                "sigma-viewer.js must call vg_notifyRelationshipSelected on clickEdge");
        assertTrue(src.contains("vg_notifySelectionCleared"),
                "sigma-viewer.js must call vg_notifySelectionCleared on clickStage");
        assertTrue(src.contains("vg_requestNodeContextMenu"),
                "sigma-viewer.js must call vg_requestNodeContextMenu on rightClickNode");
        assertTrue(src.contains("vg_requestRelationshipContextMenu"),
                "sigma-viewer.js must call vg_requestRelationshipContextMenu on rightClickEdge");
        assertTrue(src.contains("\"clickNode\"") || src.contains("'clickNode'"),
                "sigma-viewer.js must register a clickNode handler");
        assertTrue(src.contains("\"clickEdge\"") || src.contains("'clickEdge'"),
                "sigma-viewer.js must register a clickEdge handler");
    }

    @Test
    void sigmaViewerParses() throws Exception {
        String src = readViewerJs();
        javax.script.ScriptEngine eng = null;
        for (String name : new String[] {"nashorn", "graal.js", "js"}) {
            eng = new javax.script.ScriptEngineManager().getEngineByName(name);
            if (eng != null) break;
        }
        if (eng != null) {
            try {
                String stripped = src
                        .replaceFirst("^\\(function\\s*\\(\\)\\s*\\{\\n\\s*'use strict';\\n", "")
                        .replaceAll("\\}\\)\\(\\);\\s*$", "");
                String probe = "(function(){var window={};var document={};" +
                        "var graphology={Graph:function(){},MultiDirectedGraph:function(){}};" +
                        "var graphologyLayout={circular:{},random:{},circlepack:{}};" +
                        "var graphologyLayoutForceAtlas2={assign:function(){}};" +
                        "var graphologyLayoutNoverlap={assign:function(){}};" +
                        "var sigma=function(){};" + stripped + "})";
                eng.eval(probe);
            } catch (javax.script.ScriptException se) {
                fail("sigma-viewer.js has a JS syntax error: " + se.getMessage());
            }
            return;
        }
        // Fallback heuristic when no JS engine is available.
        int braceDepth = 0;
        boolean inString = false;
        for (String line : src.split("\\R")) {
            String trimmed = line.trim();
            // Crude but effective: count '{' vs '}'.
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }
            if (braceDepth == 0 && trimmed.equals("};")) {
                fail("sigma-viewer.js contains a stray top-level '};' line");
            }
        }
    }

    @Test
    void rendererFallsBackGracefullyWhenSigmaFails() throws Exception {
        // When the sigma.js renderer fails to construct (no WebGL, OOM,
        // init exception) the viewer's catch path must still notify
        // Java that the bridge is "ready" so the runWhenReady queue can
        // drain. The CanvasRenderer fallback path handles this internally
        // — createRenderer() returns a working CanvasRenderer when Sigma
        // throws, so doBoot() reaches the success branch.
        String src = readViewerJs();
        Pattern catchBody = Pattern.compile(
                "catch\\s*\\(\\s*ex\\s*\\)\\s*\\{[\\s\\S]*?notifyViewerReady\\(\\);[\\s\\S]*?\\}",
                Pattern.MULTILINE);
        Matcher m = catchBody.matcher(src);
        assertTrue(m.find(), "sigma-viewer.js catch block must invoke notifyViewerReady() so the bridge sees viewerReady");
        // Either an error message OR a canvas fallback banner should be
        // surfaced when the WebGL path fails.
        assertTrue(src.contains("Renderer init failed")
                        || src.contains("WebGL renderer unavailable")
                        || src.contains("Canvas"),
                "sigma-viewer.js must surface the renderer failure (or fallback status) to the user");
    }

    @Test
    void addEdgeUsesExplicitKeyVariant() throws Exception {
        // SigmaViewer.js must use graph.addEdgeWithKey (4-arg) instead of
        // graph.addEdge (3-arg) — the latter silently drops the 4th arg on
        // MultiDirectedGraph and triggers "Expecting an object but got
        // <target>" because the target string is assigned to the
        // attributes slot.
        String src = readViewerJs();
        assertTrue(src.contains("graph.addEdgeWithKey("),
                "rebuildGraph must use graph.addEdgeWithKey(...) for explicit-key edge insertion");
    }

    @Test
    void hasWebGLProbeIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("function hasWebGL("),
                "sigma-viewer.js must define hasWebGL() for WebGL-availability detection");
    }

    @Test
    void createRendererFactoryDispatchesToWebGLOrCanvas() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("function createRenderer("),
                "sigma-viewer.js must define a createRenderer(container, graph) factory");
        // The factory must probe WebGL availability and pick the right
        // renderer class — both Sigma (WebGL) and CanvasRenderer must
        // be reachable through the factory path.
        assertTrue(src.contains("hasWebGL()"),
                "createRenderer must consult hasWebGL() to decide between Sigma and CanvasRenderer");
        assertTrue(src.contains("function CanvasRenderer(") || src.contains("CanvasRenderer.prototype"),
                "createRenderer must be able to instantiate a CanvasRenderer as the fallback path");
        assertTrue(src.contains("new Sigma("),
                "createRenderer must be able to instantiate sigma.js as the preferred path");
    }

    @Test
    void canvasRendererExposesSigmaCompatibleApiSurface() throws Exception {
        // The CanvasRenderer must expose the same API the rest of the
        // viewer uses — refresh, getCamera().fit({padding}), setSetting,
        // on(event, fn), kill() — so the Java-bridge wiring doesn't have
        // to special-case the fallback path.
        String src = readViewerJs();
        assertTrue(src.contains("CanvasRenderer.prototype.refresh"),
                "CanvasRenderer must implement refresh()");
        assertTrue(src.contains("CanvasRenderer.prototype.setSetting"),
                "CanvasRenderer must implement setSetting(key, val)");
        assertTrue(src.contains("CanvasRenderer.prototype.on"),
                "CanvasRenderer must implement on(event, fn) for the event emitter");
        assertTrue(src.contains("CanvasRenderer.prototype.kill"),
                "CanvasRenderer must implement kill() for cleanup");
        assertTrue(src.contains("fitCamera"),
                "CanvasRenderer must implement fitCamera({padding}) so renderer.getCamera().fit() works");
        // 2D context — the canvas-2d branch.
        assertTrue(src.contains("getContext('2d')"),
                "CanvasRenderer must use a 2D canvas context (not WebGL)");
    }

    @Test
    void canvasRendererEmitsHoverAndClickEvents() throws Exception {
        // Without clickNode / clickEdge / enterNode / leaveNode /
        // rightClickNode / rightClickEdge the Java-bridge has no way to
        // know which element the user picked in canvas mode.
        String src = readViewerJs();
        assertTrue(src.contains("'clickNode'") || src.contains("\"clickNode\""),
                "CanvasRenderer must emit clickNode so the Java bridge can route selection");
        assertTrue(src.contains("'clickEdge'") || src.contains("\"clickEdge\""),
                "CanvasRenderer must emit clickEdge so the Java bridge can route edge selection");
        assertTrue(src.contains("'enterNode'") || src.contains("\"enterNode\""),
                "CanvasRenderer must emit enterNode for tooltips");
        assertTrue(src.contains("'rightClickNode'") || src.contains("\"rightClickNode\""),
                "CanvasRenderer must emit rightClickNode for the context menu");
    }

    /**
     * Edge-rendering regression guard. The CanvasRenderer._render edge loop
     * previously early-returned on {@code typeof attrs.x !== 'number'} —
     * but {@code attrs} is the edge's attribute map and edges do not
     * carry their own {@code x}/{@code y} (only nodes do). The check
     * always tripped and the canvas rendered nodes but no edges.
     *
     * <p>The guard asserts that the edge-loop now ONLY checks the
     * source/target node positions — never {@code attrs.x} / {@code attrs.y}.
     * If a future refactor re-introduces the bogus check, every edge
     * silently disappears again and the user sees an isolated scatter of
     * nodes.</p>
     */
    @Test
    void canvasRendererEdgeLoopDoesNotCheckAttrsX() throws Exception {
        String src = readViewerJs();
        int edgeLoopStart = src.indexOf("// Edges first so node circles draw on top");
        int edgeLoopEnd = src.indexOf("// Nodes", edgeLoopStart);
        assertTrue(edgeLoopStart > 0 && edgeLoopEnd > edgeLoopStart,
                "CanvasRenderer._render must contain an edge loop followed by a node loop");
        String edgeBlock = src.substring(edgeLoopStart, edgeLoopEnd);
        // The early-return must guard only on the source/target x/y —
        // NEVER on attrs.x / attrs.y (edges don't have those).
        assertFalse(edgeBlock.contains("attrs.x") || edgeBlock.contains("attrs.y"),
                "CanvasRenderer edge loop must NOT consult attrs.x / attrs.y — "
                        + "edges carry no own x/y and checking them makes every edge "
                        + "skip the render. Guard only on src.x / tgt.x (and src.y / tgt.y).");
        // Positive assertion: the guard references src/tgt coordinates.
        assertTrue(edgeBlock.contains("src.x") && edgeBlock.contains("tgt.x"),
                "CanvasRenderer edge loop must guard on src.x and tgt.x — the "
                        + "endpoint positions are the only coordinates an edge has");
    }

    /**
     * Edge-event regression guard. Sigma v2.x's WebGL renderer gates
     * {@code clickEdge}, {@code enterEdge} and {@code rightClickEdge} on
     * {@code settings.enableEdgeClickEvents} (and
     * {@code settings.enableEdgeHoverEvents}). Both default to {@code false},
     * so without these settings the renderer short-circuits edge clicks
     * and hovers to {@code …Stage} and the Java bridge never sees a
     * relationship-selection or edge-tooltip event.
     *
     * <p>The fix lives in {@code defaultSigmaSettings()} — the settings
     * bag the viewer hands to {@code new Sigma(...)}. If a future
     * refactor drops one of the toggles, RelationshipSelectionListener
     * silently breaks again on the WebGL path.</p>
     */
    @Test
    void defaultSigmaSettingsEnablesEdgeEvents() throws Exception {
        String src = readViewerJs();
        // Locate the settings object literal so we can assert it covers
        // every required edge-event toggle. The block opens with
        // 'function defaultSigmaSettings()' and runs to the matching
        // closing brace of the object literal (which is followed by
        // '};').
        int fnStart = src.indexOf("function defaultSigmaSettings()");
        assertTrue(fnStart > 0, "defaultSigmaSettings() must be defined");
        // Find the closing '};' that ends the object literal — the
        // function body is just `return { ... };`.
        int fnEnd = src.indexOf("};", fnStart);
        assertTrue(fnEnd > fnStart, "defaultSigmaSettings() must return an object literal");
        String settingsBlock = src.substring(fnStart, fnEnd);
        assertTrue(settingsBlock.contains("enableEdgeClickEvents: true"),
                "defaultSigmaSettings() must set enableEdgeClickEvents: true — "
                        + "otherwise sigma.js v2.x never fires clickEdge and the "
                        + "RelationshipSelectionListener stays silent on the WebGL path");
        assertTrue(settingsBlock.contains("enableEdgeHoverEvents: true"),
                "defaultSigmaSettings() must set enableEdgeHoverEvents: true — "
                        + "without it enterEdge is never emitted and edge tooltips "
                        + "(vg_tooltipHeader / vg_tooltipBody) never reach the DOM");
    }

    /**
     * Edge-type stripping regression guard. The graphology payload for
     * each relationship carries a {@code type} attribute (e.g. "REL")
     * that {@link de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship#toGraphologyEdge}
     * emits. Sigma.js v2.x only ships render programs for the keys
     * {@code "arrow"} and {@code "line"}; an unknown value makes the
     * next refresh throw {@code "Cannot read properties of undefined
     * (reading 'process')"} from {@code edgePrograms[edge.type]}. The
     * {@code edgeReducer} must therefore strip any {@code type} that
     * is not one of sigma's recognised program keys so sigma can fall
     * back to {@code defaultDrawEdges}.
     */
    @Test
    void edgeReducerStripsUnknownType() throws Exception {
        String src = readViewerJs();
        int reducerStart = src.indexOf("edgeReducer: function");
        assertTrue(reducerStart > 0, "defaultSigmaSettings must define edgeReducer");
        // The reducer body closes with a closing brace followed by a
        // comma (the next property separator). We can't naively look
        // for "}," because the reducer body itself contains
        // `Object.assign({}, data)` which has the same substring —
        // so search for the closing brace that is followed by another
        // closing brace (i.e. the reducer's `}` followed by the
        // outer object literal's `};`).
        int reducerEnd = src.indexOf("}\n        };", reducerStart);
        assertTrue(reducerEnd > reducerStart,
                "edgeReducer must be terminated by '}' followed by the outer '};'");
        String reducerBody = src.substring(reducerStart, reducerEnd);
        assertTrue(reducerBody.contains("delete out.type") || reducerBody.contains("delete data.type"),
                "edgeReducer must delete the edge.type attribute when it is not one "
                        + "of sigma's recognised render-program keys ('arrow', 'line') "
                        + "— leaving unknown types (e.g. 'REL' from "
                        + "GraphRelationship.toGraphologyEdge) crashes the WebGL "
                        + "renderer with 'Cannot read properties of undefined'.");
    }

    /**
     * Color Palette regression guard. The panel is auto-managed by
     * the Java bridge from {@code applyNodeColors} /
     * {@code setLeidenClusterColors} — the JS side must expose
     * {@code vg_applyColorPalette(entries, enabled)} +
     * {@code vg_hideColorPalette()} and must NOT keep the legacy
     * {@code vg_applyLegend} handler (which was driven by a separate
     * manual {@code setLegend} API call).
     */
    @Test
    void colorPaletteAutoManaged() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vg_applyColorPalette"),
                "sigma-viewer.js must register window.vg_applyColorPalette (auto-pushed by bridge)");
        assertTrue(src.contains("window.vg_hideColorPalette"),
                "sigma-viewer.js must register window.vg_hideColorPalette (auto-pushed by clear())");
        assertFalse(src.contains("window.vg_applyLegend"),
                "sigma-viewer.js must NOT register window.vg_applyLegend — the "
                        + "Color Palette replaces the manually-driven Legend API "
                        + "for sigma and NVL");
    }

    /**
     * Node interaction regression guard. {@code defaultSigmaSettings()}
     * must explicitly opt the WebGL renderer into node click + hover
     * events. {@code enableNodeHoverEvents} defaults to {@code false} in
     * sigma v2.x — without an explicit override the {@code enterNode}
     * event is never emitted and the Node tooltip remains invisible.
     * {@code enableNodeClickEvents} defaults to {@code true} but we
     * pin it explicitly so a future sigma default cannot silently
     * break the {@link NodeSelectionListener} wiring.
     */
    @Test
    void defaultSigmaSettingsEnablesNodeEvents() throws Exception {
        String src = readViewerJs();
        int fnStart = src.indexOf("function defaultSigmaSettings()");
        assertTrue(fnStart > 0, "defaultSigmaSettings() must be defined");
        int fnEnd = src.indexOf("};", fnStart);
        assertTrue(fnEnd > fnStart, "defaultSigmaSettings() must return an object literal");
        String settingsBlock = src.substring(fnStart, fnEnd);
        assertTrue(settingsBlock.contains("enableNodeClickEvents: true"),
                "defaultSigmaSettings() must set enableNodeClickEvents: true — "
                        + "otherwise the WebGL renderer never fires clickNode and the "
                        + "NodeSelectionListener stays silent on the WebGL path");
        assertTrue(settingsBlock.contains("enableNodeHoverEvents: true"),
                "defaultSigmaSettings() must set enableNodeHoverEvents: true — "
                        + "sigma v2.x defaults this to false which means enterNode "
                        + "is never fired and the node tooltip never reaches the DOM");
    }

    /**
     * Arrow-type fallback regression guard. The {@code edgeReducer}
     * must pin the {@code type} attribute to a sigma-recognised render
     * program key ({@code arrow} or {@code line}). Without the explicit
     * fallback the WebGL renderer relies entirely on the
     * {@code defaultDrawEdges} setting — one default change away from
     * silently rendering every edge as a straight line. We mirror the
     * same guard in the {@code buildEdgeReducer} runtime function so
     * both the default and the dynamic reducer apply it.
     */
    @Test
    void edgeReducerFallsBackToArrowType() throws Exception {
        String src = readViewerJs();
        int reducerStart = src.indexOf("edgeReducer: function");
        assertTrue(reducerStart > 0, "defaultSigmaSettings must define edgeReducer");
        int reducerEnd = src.indexOf("}\n        };", reducerStart);
        assertTrue(reducerEnd > reducerStart,
                "edgeReducer must be terminated by '}' followed by the outer '};'");
        String reducerBody = src.substring(reducerStart, reducerEnd);
        assertTrue(reducerBody.contains("if (!out.type) out.type = 'arrow'"),
                "defaultSigmaSettings.edgeReducer must pin out.type = 'arrow' when "
                        + "no recognisable edge-program key is present — defends the "
                        + "WebGL renderer against future defaultDrawEdges changes");
        assertTrue(src.contains("if (!out.type) out.type = 'arrow'"),
                "buildEdgeReducer() must also pin out.type = 'arrow' — same guard "
                        + "must apply to the dynamic edgeReducer installed by "
                        + "vg_applyNodeColors / vg_applyLeidenColors");
    }

    /**
     * buildEdgeReducer regression guard. The runtime edgeReducer
     * resolves the source node's color from the effective / Leiden color
     * maps so the canvas renders cluster-coloured edges. Without this
     * helper the edge falls back to grey (the {@code #888} default), and
     * the user cannot see the community structure from the edges alone.
     */
    @Test
    void buildEdgeReducerResolvesSourceNodeColor() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("function buildEdgeReducer("),
                "sigma-viewer.js must define buildEdgeReducer() to derive edge "
                        + "color from the source node's effective / Leiden colour");
        assertTrue(src.contains("currentEffectiveColors"),
                "buildEdgeReducer() must consult currentEffectiveColors (highest "
                        + "precedence — same precedence as buildNodeReducer)");
        assertTrue(src.contains("currentLeidenColors"),
                "buildEdgeReducer() must fall back to currentLeidenColors when "
                        + "no effective color is set — mirrors the colour resolver path");
        assertTrue(src.contains("graph.source(") || src.contains("graph.source ("),
                "buildEdgeReducer() must read the source-node key via graph.source(edge) "
                        + "so the colour lookup targets the actual edge source");
        // Wiring — each color-applying handler must install the new reducer.
        assertTrue(src.contains("renderer.setSetting('edgeReducer', buildEdgeReducer())"),
                "All three apply handlers (vg_applyNodeConfig, vg_applyNodeColors, "
                        + "vg_applyLeidenColors) must install buildEdgeReducer() so "
                        + "the cluster colour follows when the user re-runs Leiden / Tag");
    }

    /**
     * CanvasRenderer arrowhead regression guard. The Canvas-2D renderer
     * is the test/dev path that runs without WebGL. It must draw a
     * filled triangle at the target end of every edge — otherwise the
     * directed nature of the graph is invisible in the headless /
     * sandboxed environments that fall back to this renderer.
     */
    @Test
    void canvasRendererRendersArrowheads() throws Exception {
        String src = readViewerJs();
        int edgeLoopStart = src.indexOf("// Edges first so node circles draw on top");
        int edgeLoopEnd = src.indexOf("// Nodes", edgeLoopStart);
        assertTrue(edgeLoopStart > 0 && edgeLoopEnd > edgeLoopStart,
                "CanvasRenderer._render must contain an edge loop followed by a node loop");
        String edgeBlock = src.substring(edgeLoopStart, edgeLoopEnd);
        // Arrowhead = closed triangle (beginPath / moveTo / lineTo×2 / closePath / fill).
        assertTrue(edgeBlock.contains("beginPath()") && edgeBlock.contains("closePath()")
                        && edgeBlock.contains("ctx.fill()"),
                "CanvasRenderer edge loop must draw a closed triangle and fill it "
                        + "(the arrowhead geometry)");
        assertTrue(edgeBlock.contains("tipX") && edgeBlock.contains("tipY"),
                "CanvasRenderer edge loop must compute the arrowhead tip coordinates "
                        + "(tipX/tipY) so the line stops before the target-node centre");
        // The arrowhead should inherit the edge color (cluster colour via
        // buildEdgeReducer) — assert that the source edge color is used as
        // the fill color rather than a hard-coded '#E74C3C' or '#888'.
        assertTrue(edgeBlock.contains("attrs.color"),
                "CanvasRenderer arrowhead must reuse attrs.color (the buildEdgeReducer-"
                        + "resolved cluster colour) so the pointer matches its edge");
    }

    /**
     * CanvasRenderer edge-label regression guard. The weight label
     * (e.g. "42", "149") is already serialised by
     * {@code GraphRelationship.toGraphologyEdge} as
     * {@code attributes.label}. The Canvas renderer must render it on
     * top of the edge midpoint with a small white pill background so
     * the text stays legible against the cluster-coloured line. The
     * toggle is {@code settings.renderEdgeLabels !== false}.
     */
    @Test
    void canvasRendererRendersEdgeLabels() throws Exception {
        String src = readViewerJs();
        int edgeLoopStart = src.indexOf("// Edges first so node circles draw on top");
        int edgeLoopEnd = src.indexOf("// Nodes", edgeLoopStart);
        assertTrue(edgeLoopStart > 0 && edgeLoopEnd > edgeLoopStart,
                "CanvasRenderer._render must contain an edge loop followed by a node loop");
        String edgeBlock = src.substring(edgeLoopStart, edgeLoopEnd);
        assertTrue(edgeBlock.contains("attrs.label"),
                "CanvasRenderer edge loop must consult attrs.label for the weight string");
        assertTrue(edgeBlock.contains("renderEdgeLabels"),
                "CanvasRenderer edge loop must honour settings.renderEdgeLabels "
                        + "(so the user can suppress labels via the same settings knob "
                        + "as the WebGL renderer)");
        assertTrue(edgeBlock.contains("fillText(") && edgeBlock.contains("measureText("),
                "CanvasRenderer edge loop must invoke fillText() + measureText() to "
                        + "draw the edge label with a measured white pill background");
    }

    /**
     * attachRendererEvents robustness regression guard. Before the
     * doBoot refactor the registration lived inside the inner try
     * block, so a synchronous exception inside {@code new Sigma(...)}
     * skipped the listener wiring and the bridge reported "ready"
     * without any click / hover / tooltip plumbing. The wiring must
     * run OUTSIDE the createRenderer try / catch so a Canvas fallback
     * still receives its listeners.
     */
    @Test
    void attachRendererEventsOutsideTry() throws Exception {
        String src = readViewerJs();
        int doBootStart = src.indexOf("function doBoot(container)");
        int doBootEnd = src.indexOf("\n    }\n", doBootStart);
        assertTrue(doBootStart > 0 && doBootEnd > doBootStart,
                "doBoot() must be defined as a function block");
        String doBootBlock = src.substring(doBootStart, doBootEnd);
        // The createRenderer try should ONLY wrap the createRenderer
        // call — attachRendererEvents must come AFTER its closing brace.
        int tryStart = doBootBlock.indexOf("try {");
        int tryEnd = doBootBlock.indexOf("} catch (ex)", tryStart);
        assertTrue(tryStart > 0 && tryEnd > tryStart,
                "doBoot() must wrap createRenderer in its own try/catch");
        String tryBlock = doBootBlock.substring(tryStart, tryEnd);
        assertFalse(tryBlock.contains("attachRendererEvents"),
                "attachRendererEvents(renderer) must NOT sit inside the createRenderer "
                        + "try/catch — otherwise an exception in createRenderer skips the "
                        + "listener registration and click/hover/tooltip events stay silent");
        // Outside the try, attachRendererEvents must precede notifyViewerReady.
        int attachPos = doBootBlock.indexOf("attachRendererEvents(");
        int readyPos = doBootBlock.indexOf("notifyViewerReady()");
        assertTrue(attachPos > 0 && readyPos > 0 && attachPos < readyPos,
                "attachRendererEvents(renderer) must be called BEFORE notifyViewerReady() "
                        + "so the bridge sees ready=true with live event listeners attached");
    }
}
