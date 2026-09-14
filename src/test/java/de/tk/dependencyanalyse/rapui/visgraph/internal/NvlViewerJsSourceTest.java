package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        // The legacy handler must NOT be a bare `/* no-op */` \u2014 either
        // it dispatches to vgv_applyNodeColors or it logs the
        // rationale so a regression to a silent drop is visible.
        String bareNoOp = "window.vgv_applyNodeConfig = function () { /* no-op */ };";
        assertTrue(!src.contains(bareNoOp),
                "vgv_applyNodeConfig must not be a bare no-op (the original NVL bug)");
    }
}
