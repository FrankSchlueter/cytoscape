package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link SigmaGraphCache}. Verifies the per-session put/get
 * semantics, the evictSession cleanup path, and the evictStale fallback
 * (which catches sessions where the UISessionListener hook didn't fire —
 * server crash, RAP failover, etc.).
 */
class SigmaGraphCacheTest {

    @Test
    void putStoresEntryAndGetReturnsIt() {
        GraphData data = makeGraphData("A", "B", 1.0);
        SigmaGraphCache.put("sess-A", "tok-A", data);
        SigmaGraphCache.Entry entry = SigmaGraphCache.get("sess-A");
        assertNotNull(entry);
        assertEquals("tok-A", entry.getToken());
        assertSame(data, entry.getData());
        SigmaGraphCache.evictSession("sess-A");
        assertNull(SigmaGraphCache.get("sess-A"));
    }

    @Test
    void putOverwritesPreviousEntry() {
        GraphData data1 = makeGraphData("X", "Y", 1.0);
        GraphData data2 = makeGraphData("Z", "W", 2.0);
        SigmaGraphCache.put("sess-1", "tok-1", data1);
        SigmaGraphCache.put("sess-1", "tok-2", data2);
        SigmaGraphCache.Entry entry = SigmaGraphCache.get("sess-1");
        assertNotNull(entry);
        assertEquals("tok-2", entry.getToken());
        assertSame(data2, entry.getData());
        SigmaGraphCache.evictSession("sess-1");
    }

    @Test
    void differentSessionsAreIsolated() {
        GraphData data1 = makeGraphData("A", "B", 1.0);
        GraphData data2 = makeGraphData("C", "D", 2.0);
        SigmaGraphCache.put("sess-1", "tok-1", data1);
        SigmaGraphCache.put("sess-2", "tok-2", data2);
        assertEquals("tok-1", SigmaGraphCache.get("sess-1").getToken());
        assertEquals("tok-2", SigmaGraphCache.get("sess-2").getToken());
        SigmaGraphCache.evictSession("sess-1");
        assertNotNull(SigmaGraphCache.get("sess-2"));
        SigmaGraphCache.evictSession("sess-2");
        assertNull(SigmaGraphCache.get("sess-1"));
        assertNull(SigmaGraphCache.get("sess-2"));
    }

    @Test
    void evictStaleRemovesOldEntries() throws Exception {
        // Insert a synthetic entry with a back-dated createdAt.
        GraphData data = makeGraphData("A", "B", 1.0);
        SigmaGraphCache.put("sess-stale", "tok-stale", data);
        SigmaGraphCache.Entry entry = SigmaGraphCache.get("sess-stale");
        assertNotNull(entry);
        // Use reflection to back-date createdAt so it falls outside MAX_AGE_MS.
        Field createdAt = SigmaGraphCache.Entry.class.getDeclaredField("createdAt");
        createdAt.setAccessible(true);
        createdAt.set(entry, Instant.now().minusMillis(SigmaGraphCache.MAX_AGE_MINUTES * 2 * 60 * 1000));
        // Insert a fresh entry — must survive evictStale().
        SigmaGraphCache.put("sess-fresh", "tok-fresh", data);
        int removed = SigmaGraphCache.evictStale();
        assertTrue(removed >= 1, "evictStale should remove at least the stale entry");
        assertNull(SigmaGraphCache.get("sess-stale"));
        assertNotNull(SigmaGraphCache.get("sess-fresh"));
        SigmaGraphCache.evictSession("sess-fresh");
    }

    @Test
    void nullArgumentsAreRejected() {
        try {
            SigmaGraphCache.put(null, "tok", null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            SigmaGraphCache.put("sess", null, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static GraphData makeGraphData(String src, String tgt, double weight) {
        GraphNode source = new GraphNode(src, List.of("Node"), java.util.Map.of("name", src));
        GraphNode target = new GraphNode(tgt, List.of("Node"), java.util.Map.of("name", tgt));
        GraphRelationship edge = new GraphRelationship("e1", "REL", source, target,
                java.util.Map.of("weight", weight));
        return new GraphData(List.of(source, target), List.of(edge));
    }
}
