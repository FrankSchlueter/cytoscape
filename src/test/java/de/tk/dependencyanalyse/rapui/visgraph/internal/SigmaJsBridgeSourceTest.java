package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level regression guards for {@link SigmaJsBridge}.
 *
 * <p>The bridge is exercised only at runtime by the sigma iframe; a JUnit
 * test cannot stand up a real SWT {@code Browser} widget. So we read the
 * Java source the build packages into {@code target/classes} and assert
 * the structural contracts that {@code applyData(...)} must satisfy:</p>
 *
 * <ul>
 *   <li>Data delivery: {@code applyData(...)} serializes the payload,
 *       gzip+base64-encodes it and ships it via a single atomic
 *       {@code window.vg_setDataGz(b64)} call — no REST endpoints, no
 *       per-session cache, no URL inlining. Splitting the assignment and
 *       the call into two separate {@code exec(...)} scripts reproduces
 *       the legacy race that dropped the graph on the first push.</li>
 *   <li>The bridge no longer references the removed REST classes
 *       ({@code SigmaGraphCache}, {@code SigmaGraphController},
 *       {@code UISessionListener}, {@code NodeConfigRegistry}).</li>
 * </ul>
 */
class SigmaJsBridgeSourceTest {

    private static final String[] POSSIBLE_BRIDGE_PATHS = {
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/SigmaJsBridge.java",
            "target/classes/de/tk/dependencyanalyse/rapui/visgraph/internal/SigmaJsBridge.java",
    };

    private static String readBridge() throws IOException {
        for (String p : POSSIBLE_BRIDGE_PATHS) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("SigmaJsBridge.java not found");
    }

    /**
     * Contract check: {@link SigmaJsBridge#applyData} must push EXACTLY
     * ONE atomic script per branch. The legacy bug was splitting the
     * data-delivery into two separate {@code exec(...)} calls so the
     * iframe could observe {@code vg_setDataGz} firing before
     * {@code vg_clear} had reset state — leaving the user staring at an
     * empty canvas.
     *
     * <p>The sigma bridge queues via {@code execWhenReady(...)} (rather
     * than calling {@code exec(...)} directly like Cytoscape/Vis) because
     * the {@code applyData} path may run BEFORE the iframe IIFE has
     * reported {@code vg_viewerReady}. The atomic-script contract is
     * preserved: each {@code if} branch ships its work in a single
     * {@code execWhenReady(...)} call.</p>
     */
    @Test
    void applyDataEmitsSingleAtomicScript() throws Exception {
        String src = readBridge();
        int applyIdx = src.indexOf("public void applyData(GraphData data)");
        assertTrue(applyIdx > 0, "SigmaJsBridge.applyData must be defined");
        // Method body ends at the first "\n    }" after the opening brace.
        int applyEnd = src.indexOf("\n    }\n", applyIdx);
        assertTrue(applyEnd > applyIdx, "applyData body must terminate with a closing brace");
        String body = src.substring(applyIdx, applyEnd);

        // The non-empty branch must invoke vg_setDataGz with a string
        // argument that came from gson.toJson(b64) — i.e. the script
        // contains a properly quoted string literal, not a raw identifier.
        assertTrue(body.contains("vg_setDataGz("),
                "SigmaJsBridge.applyData must invoke vg_setDataGz(...)");
        assertTrue(body.contains("gson.toJson(b64)"),
                "SigmaJsBridge.applyData must serialize the b64 string via gson.toJson(b64) "
                        + "so the inline script contains a properly quoted string literal");

        // Find the if-branch for empty data — locate the `if (data == null || data.getNodes().isEmpty())`
        // block and verify its body contains exactly ONE execWhenReady / exec call.
        int emptyBranchStart = body.indexOf("if (data == null || data.getNodes().isEmpty())");
        assertTrue(emptyBranchStart > 0,
                "SigmaJsBridge.applyData must guard the empty-data case");
        // The empty-data branch ends at the first 'return;' line.
        int emptyBranchEnd = body.indexOf("return;", emptyBranchStart);
        assertTrue(emptyBranchEnd > emptyBranchStart,
                "empty-data branch must contain an early return");
        String emptyBranch = body.substring(emptyBranchStart, emptyBranchEnd);
        assertTrue(emptyBranch.contains("execWhenReady(") && emptyBranch.contains("vg_clear"),
                "empty-data branch must push exactly one vg_clear via execWhenReady(...)");

        // Find the main non-empty branch — locate the second execWhenReady call
        // (the one that pushes the actual graph data). It must contain BOTH
        // vg_clear AND vg_setDataGz — i.e. the script is atomic.
        int mainIdx = body.indexOf("execWhenReady(", emptyBranchEnd);
        assertTrue(mainIdx > 0, "main branch must contain an execWhenReady(...) push");
        // Find the closing ");" of the execWhenReady call — simple paren counting
        // would handle nested parens inside gson.toJson(...) which has its own
        // parentheses.
        int parenDepth = 0;
        int mainEnd = -1;
        boolean started = false;
        for (int i = mainIdx; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') { parenDepth++; started = true; }
            else if (c == ')') {
                parenDepth--;
                if (started && parenDepth == 0) { mainEnd = i + 1; break; }
            }
        }
        assertTrue(mainEnd > mainIdx, "main-branch execWhenReady(...) must terminate");
        String mainPush = body.substring(mainIdx, mainEnd);
        assertTrue(mainPush.contains("vg_clear"),
                "main-branch push must include vg_clear (atomic clear+setDataGz)");
        assertTrue(mainPush.contains("vg_setDataGz("),
                "main-branch push must include vg_setDataGz(...)");
        assertTrue(mainPush.contains("gson.toJson(b64)"),
                "main-branch push must serialize b64 via gson.toJson(b64)");

        // After the main push, there must NOT be any further execWhenReady or
        // exec( calls in the body — that would split the delivery.
        int afterMain = body.indexOf(mainPush) + mainPush.length();
        String tail = body.substring(afterMain);
        assertEquals(-1, tail.indexOf("execWhenReady("),
                "no further execWhenReady(...) may follow the main push — "
                        + "splitting the clear+setDataGz into two scripts reproduces "
                        + "the race where the iframe sees vg_setDataGz before vg_clear");
        assertEquals(-1, tail.indexOf("exec("),
                "no further exec(...) may follow the main push");
    }

    /**
     * The gzip+base64 helper is package-private and reachable from the
     * test class. Asserting the visibility contract here keeps
     * {@link SigmaJsBridgeGzipTest} honest.
     */
    @Test
    void gzipAndBase64IsPackageVisible() throws Exception {
        String src = readBridge();
        assertTrue(src.contains("static String gzipAndBase64("),
                "SigmaJsBridge.gzipAndBase64 must be package-visible so the gzip "
                        + "round-trip tests in SigmaJsBridgeGzipTest can call it");
    }

    /**
     * The bridge must NOT reference the removed REST classes or the
     * per-session UISessionListener cleanup hook. If a future refactor
     * re-introduces any of those, the contract is broken — the sigma
     * engine's data delivery now lives entirely in this bridge.
     */
    @Test
    void bridgeHasNoRestReferences() throws Exception {
        String src = readBridge();
        assertEquals(-1, src.indexOf("SigmaGraphCache"),
                "SigmaJsBridge must not reference SigmaGraphCache — the per-session "
                        + "cache was removed when data delivery moved to the bridge");
        assertEquals(-1, src.indexOf("SigmaGraphController"),
                "SigmaJsBridge must not reference SigmaGraphController — the REST "
                        + "endpoints were removed");
        assertEquals(-1, src.indexOf("UISessionListener"),
                "SigmaJsBridge must not register a UISessionListener — there is no "
                        + "per-session cache to evict anymore");
        assertEquals(-1, src.indexOf("addUISessionListener"),
                "SigmaJsBridge must not call addUISessionListener");
        assertEquals(-1, src.indexOf("computeInitialUrls"),
                "SigmaJsBridge must not expose computeInitialUrls — URL inlining into "
                        + "the iframe HTML was removed");
        assertEquals(-1, src.indexOf("currentToken"),
                "SigmaJsBridge must not track currentToken — token-based REST "
                        + "delivery was removed");
        assertEquals(-1, src.indexOf("/api/sigma"),
                "SigmaJsBridge must not reference the /api/sigma REST endpoints");
        assertEquals(-1, src.indexOf("__VG_INITIAL_"),
                "SigmaJsBridge must not reference the __VG_INITIAL_*_URL__ placeholders");
    }
}
