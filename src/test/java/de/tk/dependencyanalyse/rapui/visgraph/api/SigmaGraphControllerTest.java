package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SigmaGraphController} that exercise the
 * success and failure paths of {@code /api/sigma/nodes} and
 * {@code /api/sigma/edges} without spinning up a full Spring stack.
 *
 * <p>The HttpSession-keyed lookup isolates the public endpoints from
 * the UISession-keyed RAP-side store; the test stub returns a known
 * session id and inserts the corresponding entry directly into
 * {@link SigmaGraphCache} so the controller's lookup hits.</p>
 */
class SigmaGraphControllerTest {

    private static final String SESSION_ID = "test-session-1";
    private static final String TOKEN = "test-token-abc";

    @BeforeEach
    void setUp() {
        SigmaGraphCache.put(SESSION_ID, TOKEN, makeGraphData());
    }

    @AfterEach
    void tearDown() {
        SigmaGraphCache.evictSession(SESSION_ID);
    }

    @Test
    void getNodesReturns200WithEtag() {
        SigmaGraphController ctrl = new SigmaGraphController();
        ResponseEntity<?> resp = ctrl.getNodes(TOKEN, 0L, null, httpRequestFor(SESSION_ID));
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(Objects.requireNonNull(resp.getHeaders().getETag()).startsWith("\"n-"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertEquals("1", body.get("version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) body.get("nodes");
        assertEquals(2, nodes.size());
    }

    @Test
    void getNodesReturns304WhenIfNoneMatchMatches() {
        SigmaGraphController ctrl = new SigmaGraphController();
        String etag = "\"n-" + TOKEN + "-2\"";
        ResponseEntity<?> resp = ctrl.getNodes(TOKEN, 0L, etag, httpRequestFor(SESSION_ID));
        assertEquals(304, resp.getStatusCode().value());
    }

    @Test
    void getNodesReturns404WhenTokenMismatches() {
        SigmaGraphController ctrl = new SigmaGraphController();
        ResponseEntity<?> resp = ctrl.getNodes("definitely-not-real", 0L, null, httpRequestFor(SESSION_ID));
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void getEdgesReturns200WithEtag() {
        SigmaGraphController ctrl = new SigmaGraphController();
        ResponseEntity<?> resp = ctrl.getEdges(TOKEN, 0L, null, httpRequestFor(SESSION_ID));
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(Objects.requireNonNull(resp.getHeaders().getETag()).startsWith("\"e-"));
    }

    @Test
    void getEdgesReturns404AfterEviction() {
        SigmaGraphCache.evictSession(SESSION_ID);
        SigmaGraphController ctrl = new SigmaGraphController();
        ResponseEntity<?> resp = ctrl.getEdges(TOKEN, 0L, null, httpRequestFor(SESSION_ID));
        assertEquals(404, resp.getStatusCode().value());
    }

    private static GraphData makeGraphData() {
        GraphNode src = new GraphNode("A", List.of("Node"), Map.of("name", "A"));
        GraphNode tgt = new GraphNode("B", List.of("Node"), Map.of("name", "B"));
        GraphRelationship edge = new GraphRelationship("e1", "REL", src, tgt,
                Map.of("weight", 1.0));
        return new GraphData(List.of(src, tgt), List.of(edge));
    }

    private static HttpServletRequest httpRequestFor(String sessionId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(req.getSession(any(Boolean.class))).thenReturn(session);
        return req;
    }
}
