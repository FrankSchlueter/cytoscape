package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller that serves the graphology payload for the sigma.js engine
 * via two parallel endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/sigma/nodes?token=<uuid>&v=<n>} — returns the node
 *       array with their {@code attributes} (label, color, tooltip, raw).</li>
 *   <li>{@code GET /api/sigma/edges?token=<uuid>&v=<n>} — returns the edge
 *       array with their {@code attributes} (weight, logWeight, label,
 *       tooltip).</li>
 * </ul>
 *
 * <p>Both endpoints require a valid {@code token} that matches the entry
 * stored in {@link SigmaGraphCache} for the current RAP UI session. An
 * unknown or stale token yields {@code 404 Not Found}. The token is
 * generated server-side by
 * {@link de.tk.dependencyanalyse.rapui.visgraph.internal.SigmaJsBridge#applyData}
 * and inlined into the iframe HTML so the iframe can fetch without
 * cross-origin complications.</p>
 *
 * <p>The response carries an {@code ETag} derived from the token + element
 * count so the iframe can issue {@code If-None-Match} on subsequent layout
 * changes — the layout refactor does NOT modify the graph payload, so a
 * 304 round-trip avoids re-serialising the (potentially large) JSON.</p>
 *
 * <p>Responses are cached by the standard Servlet stack; combined with the
 * {@code server.compression.enabled=true} setting in {@code application.yml}
 * the 151-node / 1010-edge sample graph (≈30 KB JSON) compresses to ≈5 KB.</p>
 */
@RestController
@RequestMapping("/api/sigma")
public class SigmaGraphController {

    private static final String SCHEMA_VERSION = "1";

    @GetMapping(value = "/nodes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getNodes(
            @RequestParam("token") String token,
            @RequestParam(value = "v", required = false) Long version,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        SigmaGraphCache.Entry entry = resolveEntry(token, request);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        NodeConfig cfg = currentNodeConfig(request);
        Map<String, Object> fullPayload = entry.getData().toGraphologyElements(cfg);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) fullPayload.get("nodes");
        String etag = buildEtag("n", token, nodes.size());
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", SCHEMA_VERSION);
        body.put("nodes", nodes);
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cachePrivate())
                .body(body);
    }

    @GetMapping(value = "/edges", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getEdges(
            @RequestParam("token") String token,
            @RequestParam(value = "v", required = false) Long version,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        SigmaGraphCache.Entry entry = resolveEntry(token, request);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        NodeConfig cfg = currentNodeConfig(request);
        Map<String, Object> fullPayload = entry.getData().toGraphologyElements(cfg);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) fullPayload.get("edges");
        String etag = buildEtag("e", token, edges.size());
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", SCHEMA_VERSION);
        body.put("edges", edges);
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cachePrivate())
                .body(body);
    }

    /**
     * Resolve the cache entry for the current HTTP session, verifying the
     * supplied token matches. Returns {@code null} when the session has no
     * entry or the token is stale.
     *
     * <p>Uses the underlying {@code HttpSession} ID — not
     * {@link org.eclipse.rap.rwt.RWT#getUISession()} — because the REST
     * endpoints run on regular Spring threads where {@code RWT.getUISession()}
     * throws "Invalid thread access". The {@link SigmaJsBridge#applyData}
     * keys the cache on the same HttpSession ID via
     * {@code UISession.getHttpSession().getId()}.</p>
     */
    private static SigmaGraphCache.Entry resolveEntry(String token, HttpServletRequest request) {
        if (token == null || token.isEmpty()) return null;
        if (request == null || request.getSession(false) == null) return null;
        String httpSessionId = request.getSession(false).getId();
        SigmaGraphCache.Entry entry = SigmaGraphCache.get(httpSessionId);
        if (entry == null) return null;
        if (!token.equals(entry.getToken())) return null;
        return entry;
    }

    /**
     * Best-effort NodeConfig lookup so the user-set label/tag colours
     * travel with the payload. Falls back to {@link NodeConfig#defaults()}
     * when the session has no registered NodeConfig (e.g. the cache
     * survived the NodeConfig eviction but the graph payload hasn't been
     * re-pushed yet).
     */
    private static NodeConfig currentNodeConfig(HttpServletRequest request) {
        if (request == null || request.getSession(false) == null) return NodeConfig.defaults();
        String httpSessionId = request.getSession(false).getId();
        NodeConfig cfg = NodeConfigRegistry.get(httpSessionId);
        return cfg != null ? cfg : NodeConfig.defaults();
    }

    private static String buildEtag(String prefix, String token, int count) {
        return "\"" + prefix + "-" + token + "-" + count + "\"";
    }

    /**
     * Cache-Control directives for a private, must-revalidate response.
     * Kept simple — {@code max-age=0} together with the {@code ETag}
     * header lets the iframe short-circuit re-applies of the same payload
     * via {@code If-None-Match} → 304.
     */
    private static org.springframework.http.CacheControl cachePrivate() {
        return org.springframework.http.CacheControl.noCache().cachePrivate();
    }
}
