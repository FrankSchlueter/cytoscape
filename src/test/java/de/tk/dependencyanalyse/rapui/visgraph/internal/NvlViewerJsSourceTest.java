package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level checks against {@code nvl-graph-viewer.js}. The bridge
 * is JS-only (no Java unit tests can exercise it directly), so we
 * verify the contract by inspecting the source the iframe loads.
 *
 * <p>Guarded here: NVL must respond to the unified color-update
 * protocol — {@code vgv_applyLeidenColors} (so the "Apply Leiden
 * Clustering" button works under NVL) and
 * {@code vgv_applyNodeColors} (so the resolved per-node color map
 * from {@code NodeColorResolver} reaches the NVL canvas).</p>
 *
 * <p>Mirror of {@code CytoscapeViewerJsSourceTest} /
 * {@code VisGraphViewerJsSourceTest}: same static-source pattern, same
 * "fail loudly when the contract regresses" intent.</p>
 */
class NvlViewerJsSourceTest {

    private static final String[] POSSIBLE_VIEWER_PATHS = {
            "src/main/resources/static/nvl/nvl-graph-viewer.js",
            "target/classes/static/nvl/nvl-graph-viewer.js",
    };

    private static String readViewerJs() throws IOException {
        for (String p : POSSIBLE_VIEWER_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("nvl-graph-viewer.js not found in any known location");
    }

    /**
     * Regression test for the "Apply Leiden Clustering is a no-op on
     * NVL" bug. The handler must call
     * {@code nvl.updateElementsInGraph} with one {@code {id, color}}
     * entry per node.
     */
    @Test
    void vgvApplyLeidenColorsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyLeidenColors"),
                "nvl-graph-viewer.js must register window.vgv_applyLeidenColors");
        assertTrue(src.contains("nvl.updateElementsInGraph"),
                "vgv_applyLeidenColors must call nvl.updateElementsInGraph to push the Leiden colors");
    }

    /**
     * The unified per-node color update handler must exist and route
     * through {@code nvl.updateElementsInGraph}.
     */
    @Test
    void vgvApplyNodeColorsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyNodeColors"),
                "nvl-graph-viewer.js must register window.vgv_applyNodeColors");
        assertTrue(src.contains("nvl.updateElementsInGraph"),
                "vgv_applyNodeColors must call nvl.updateElementsInGraph");
    }

    /**
     * The legacy {@code vgv_applyNodeConfig} (kept for backwards
     * compatibility with the bridge) must NOT be a no-op without
     * comment. Either it routes to vgv_applyNodeColors or it
     * explicitly logs that NVL uses a different path — pure no-op
     * means the bridge's applyNodeConfig call silently drops the
     * NodeConfig (the original bug).
     */
    @Test
    void vgvApplyNodeConfigIsNotASilentNoOp() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyNodeConfig"),
                "nvl-graph-viewer.js must register window.vgv_applyNodeConfig (called by the bridge)");
        // The legacy handler must NOT be a bare `/* no-op */` — either
        // it dispatches to vgv_applyNodeColors or it logs the
        // rationale so a regression to a silent drop is visible.
        String bareNoOp = "window.vgv_applyNodeConfig = function () { /* no-op */ };";
        assertTrue(!src.contains(bareNoOp),
                "vgv_applyNodeConfig must not be a bare no-op (the original NVL bug)");
    }

    /**
     * Regression guard for the Color Palette feature. NVL must
     * expose {@code vgv_applyColorPalette(entries, enabled)} so the
     * Java bridge can auto-push the palette panel after every
     * {@code applyNodeColors} / {@code setLeidenColors} call, plus
     * {@code vgv_hideColorPalette()} so {@code clear()} can reset it.
     */
    @Test
    void vgvApplyColorPaletteIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("window.vgv_applyColorPalette"),
                "nvl-graph-viewer.js must register window.vgv_applyColorPalette");
        assertTrue(src.contains("window.vgv_hideColorPalette"),
                "nvl-graph-viewer.js must register window.vgv_hideColorPalette");
    }

    /**
     * The palette panel markup (#vgv-color-palette) must exist in the
     * NVL viewer HTML so the JS renderer has a place to mount rows.
     */
    @Test
    void colorPaletteMarkupIsPresentInNvlViewerHtml() throws Exception {
        String[] paths = {
                "src/main/resources/static/nvl/nvl-viewer.html",
                "target/classes/static/nvl/nvl-viewer.html"
        };
        String html = null;
        for (String p : paths) {
            java.nio.file.Path path = java.nio.file.Paths.get(p);
            if (java.nio.file.Files.exists(path)) {
                html = new String(java.nio.file.Files.readAllBytes(path),
                        java.nio.charset.StandardCharsets.UTF_8);
                break;
            }
        }
        assertNotNull(html, "nvl-viewer.html must be readable");
        assertTrue(html.contains("vgv-color-palette"),
                "nvl-viewer.html must contain the #vgv-color-palette container");
        assertTrue(html.contains("Color Palette"),
                "nvl-viewer.html must label the panel 'Color Palette'");
    }

    /**
     * Pan + Zoom regression guard. The official NVL Interaction
     * Handlers {@code Pan} (drag on empty canvas) and {@code Zoom}
     * (mouse wheel) must be constructed during the boot sequence so
     * users can navigate the canvas. Without these lines the canvas
     * is read-only outside of node-selection and the Fit button.
     */
    @Test
    void panAndZoomHandlersAreConstructed() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("new window.Neo4jNVLInteractions.Pan("),
                "nvl-graph-viewer.js must construct a Pan interaction handler "
                        + "(drag on empty canvas) so the canvas is pannable");
        assertTrue(src.contains("new window.Neo4jNVLInteractions.Zoom("),
                "nvl-graph-viewer.js must construct a Zoom interaction handler "
                        + "(mouse wheel) so the canvas is zoomable");
    }

    /**
     * Negative guard for the Pan + Zoom regression. The previous
     * implementation intentionally left the handlers as {@code null}
     * with a long comment about RAP scroll-container quirks. The
     * NVL Interaction Handlers call {@code preventDefault()} on the
     * wheel event themselves so the legacy no-op rationale no
     * longer applies — a future refactor that re-introduces the
     * silent null assignment regresses the user-facing navigation.
     */
    @Test
    void panZoomNullAssignmentsAreRemoved() throws Exception {
        String src = readViewerJs();
        // The initial `var panHandler = null;` declaration at module
        // scope is fine — only the post-construction "explicit no-op
        // assignment" pattern is the regression we want to catch.
        assertFalse(src.contains("panHandler = null;"),
                "nvl-graph-viewer.js must not re-assign panHandler to null "
                        + "after boot — the Pan handler must be constructed "
                        + "and stay constructed");
        assertFalse(src.contains("zoomHandler = null;"),
                "nvl-graph-viewer.js must not re-assign zoomHandler to null "
                        + "after boot — the Zoom handler must be constructed "
                        + "and stay constructed");
    }

    /**
     * Edge-Farbe aus Quell-Node. Die Funktion {@code applyEdgeColors}
     * muss vorhanden sein, den Source-Node-Lookup ({@code r.from})
     * verwenden und das NVL-Update-API mit color-Updates ansprechen.
     * Sigma-konform zu {@code sigma-viewer.js:1653-1672}.
     */
    @Test
    void applyEdgeColorsIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("function applyEdgeColors"),
                "nvl-graph-viewer.js must define applyEdgeColors()");
        assertTrue(src.contains("r.from"),
                "applyEdgeColors must use r.from as the source-node lookup key");
        assertTrue(src.contains("currentEffectiveColors"),
                "applyEdgeColors must consult currentEffectiveColors (resolver map pushed by vgv_applyNodeColors)");
        assertTrue(src.contains("currentLeidenColors"),
                "applyEdgeColors must fall back to currentLeidenColors when the resolver map is silent");
        // Diff-basiert: nur color-Unterschiede werden gepusht.
        assertTrue(src.contains("nvl.updateElementsInGraph([], updates)")
                        || src.contains("nvl.updateElementsInGraph( [], updates )"),
                "applyEdgeColors must call nvl.updateElementsInGraph with the color-diff updates");
    }

    /**
     * Apply-Node-Colors / Apply-Leiden-Colors müssen über den
     * geteilten {@code updateNodeColors}-Helper indirekt
     * {@code applyEdgeColors} triggern, damit eine Farbänderung an
     * einer Source-Node unmittelbar auf die auslaufenden Edges
     * propagiert.
     */
    @Test
    void applyEdgeColorsCalledFromColorUpdateHandlers() throws Exception {
        String src = readViewerJs();
        int idxHelper = src.indexOf("function updateNodeColors");
        assertTrue(idxHelper > 0,
                "nvl-graph-viewer.js must define updateNodeColors()");
        // Body bis zum nächsten Top-Level-Tokens '"function ' einsammeln —
        // verhindert, dass ein inneres '}' den Body zu früh abschneidet.
        int endHelper = src.indexOf("\n    function ", idxHelper + 10);
        if (endHelper < 0) endHelper = src.length();
        String helperBody = src.substring(idxHelper, endHelper);
        assertTrue(helperBody.contains("applyEdgeColors"),
                "updateNodeColors must trigger applyEdgeColors so edge colors "
                        + "follow source-node effective-color updates (this is the "
                        + "shared path used by vgv_applyNodeColors and vgv_applyLeidenColors)");
    }

    /**
     * Node-Sichtbarkeits-Filter. Die Funktion {@code applyNodeFilter}
     * muss vorhanden sein, das Node-Hidden-Backup
     * ({@code hiddenNodeBackups}) verwalten und das NVL-Node-API
     * ({@code removeNodesWithIds}, {@code addAndUpdateElementsInGraph},
     * {@code setNodePositions}) für das Remove/Re-Insert-Pattern
     * ansprechen — analog zum bestehenden Edge-Filter-Pattern.
     */
    @Test
    void applyNodeFilterIsImplemented() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("function applyNodeFilter"),
                "nvl-graph-viewer.js must define applyNodeFilter()");
        assertTrue(src.contains("var hiddenNodeBackups"),
                "nvl-graph-viewer.js must declare hiddenNodeBackups "
                        + "(the per-node analog of hiddenRelBackups)");
        assertTrue(src.contains("nvl.removeNodesWithIds"),
                "applyNodeFilter must call nvl.removeNodesWithIds to hide "
                        + "out-of-filter nodes (NVL has no per-node hidden property)");
        assertTrue(src.contains("nvl.addAndUpdateElementsInGraph(restoreNodes, [])")
                        || src.contains("nvl.addAndUpdateElementsInGraph( restoreNodes, [] )"),
                "applyNodeFilter must re-insert restored nodes via "
                        + "nvl.addAndUpdateElementsInGraph");
        assertTrue(src.contains("nvl.setNodePositions"),
                "applyNodeFilter must call nvl.setNodePositions to restore "
                        + "the original layout slot after re-insert");
        // Apply-Edge-Filter triggert den Node-Filter am Ende. Body bis
        // zur nächsten Top-Level-function-Deklaration einsammeln.
        int idx = src.indexOf("function applyEdgeFilter");
        int end = src.indexOf("\n    function ", idx + 10);
        if (end < 0) end = src.length();
        String body = src.substring(idx, end);
        assertTrue(body.contains("applyNodeFilter()"),
                "applyEdgeFilter must call applyNodeFilter() at the end so "
                        + "node visibility follows edge-filter changes");
    }

    /**
     * HiddenNodeBackups muss in den Graph-Reset-Pfaden geleert
     * werden, damit kein verwaistes Backup gegen den nächsten
     * Datenaufbau läuft.
     */
    @Test
    void hiddenNodeBackupsResetOnDataRefresh() throws Exception {
        String src = readViewerJs();
        int idxSetData = src.indexOf("function setDataInternal");
        int endSetData = src.indexOf("\n    function ", idxSetData + 10);
        if (endSetData < 0) endSetData = src.length();
        String setDataBody = src.substring(idxSetData, endSetData);
        assertTrue(setDataBody.contains("hiddenNodeBackups = {}"),
                "setDataInternal must reset hiddenNodeBackups when replacing "
                        + "the graph (otherwise backups dangle against stale node IDs)");
        int idxClear = src.indexOf("window.vgv_clear = function");
        int endClear = src.indexOf("\n    window.", idxClear + 10);
        if (endClear < 0) endClear = src.length();
        String clearBody = src.substring(idxClear, endClear);
        assertTrue(clearBody.contains("hiddenNodeBackups = {}"),
                "vgv_clear must reset hiddenNodeBackups so the next "
                        + "graph load starts with an empty backup pool");
    }

    /**
     * computeKeptNodeIds berücksichtigt sowohl
     * {@code currentEffectiveColors} (Resolver-Map) als auch
     * {@code currentLeidenColors} (Leiden-Fallback) beim
     * Cluster-Match — sonst würden Tag-color-only Nodes vom
     * Cluster-Filter ausgeschlossen.
     */
    @Test
    void computeKeptNodeIdsHandlesBothColorMaps() throws Exception {
        String src = readViewerJs();
        assertTrue(src.contains("function computeKeptNodeIds"),
                "nvl-graph-viewer.js must define computeKeptNodeIds()");
        int idx = src.indexOf("function computeKeptNodeIds");
        int end = src.indexOf("\n    }\n", idx);
        String body = src.substring(idx, end);
        assertTrue(body.contains("currentEffectiveColors")
                        || body.contains("ec"),
                "computeKeptNodeIds must consult the resolver / effective map");
        assertTrue(body.contains("currentLeidenColors")
                        || body.contains("lc"),
                "computeKeptNodeIds must consult the Leiden fallback map");
        // Cluster-Pfad muss Brücken-Edges berücksichtigen.
        assertTrue(body.contains("type === 'cluster'") || body.contains("type == 'cluster'"),
                "computeKeptNodeIds must implement the cluster branch");
    }
}
