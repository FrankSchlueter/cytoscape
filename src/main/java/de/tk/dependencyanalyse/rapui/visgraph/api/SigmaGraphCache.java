package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per-session cache for the graphology payload delivered to the sigma.js
 * engine via the {@code /api/sigma/nodes} and {@code /api/sigma/edges} REST
 * endpoints.
 *
 * <p>The cache sits between {@link de.tk.dependencyanalyse.rapui.visgraph.internal.SigmaJsBridge}
 * (the Java-side producer that pushes {@code GraphData} payloads when the user
 * loads a CSV) and {@link SigmaGraphController} (the consumer that serves
 * those payloads to the iframe via fetch). Storing the graphology-ready
 * payload server-side means the iframe can refetch on layout changes without
 * a Java roundtrip — only the cache lookup runs on each request.</p>
 *
 * <h2>Cleanup</h2>
 * <p>Entries are evicted in two complementary ways:</p>
 * <ol>
 *   <li><b>Session-aware (canonical):</b> {@code SigmaJsBridge.applyData(...)}
 *       registers a {@code UISessionListener.beforeDestroy} hook that calls
 *       {@link #evictSession(String)} when the underlying RAP UI session is
 *       destroyed (logout, tab close, server-side timeout).</li>
 *   <li><b>Time-aware (defensive):</b> A daemon thread sweeps the cache every
 *       5 minutes and removes any entry older than {@link #MAX_AGE_MINUTES}
 *       minutes. Catches the edge case where the UISessionListener didn't
 *       fire (e.g. server crash, RAP failover).</li>
 * </ol>
 *
 * <p>Each session holds at most one entry (a second {@code put(...)} overwrites
 * the previous one). The cache is process-local — for a multi-instance
 * deployment an external cache (Redis) would be needed, but for the embedded
 * Jetty + single-JVM profile this is sufficient.</p>
 */
public final class SigmaGraphCache {

    /** Cache entry. Immutable once stored; the {@link Instant} is set at insert time. */
    public static final class Entry {
        private final String token;
        private final GraphData data;
        private final Instant createdAt;

        public Entry(String token, GraphData data) {
            this.token = token;
            this.data = data;
            this.createdAt = Instant.now();
        }

        public String getToken() { return token; }
        public GraphData getData() { return data; }
        public Instant getCreatedAt() { return createdAt; }
    }

    private static final ConcurrentMap<String, Entry> BY_SESSION = new ConcurrentHashMap<>();

    /** Stale threshold for the daemon evictor. {@code public} for unit-test reflection. */
    public static final long MAX_AGE_MINUTES = 30;
    private static final long MAX_AGE_MS = MAX_AGE_MINUTES * 60 * 1000;

    static {
        // Defensive evictor — runs every 5 minutes and drops any session
        // entry whose UISessionListener.beforeDestroy hook did not fire.
        // Daemon thread so it does not block JVM shutdown.
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SigmaGraphCache-Evictor");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(SigmaGraphCache::evictStale, 5, 5, TimeUnit.MINUTES);
    }

    private SigmaGraphCache() {}

    /**
     * Insert or overwrite the graph payload for the given session. Each session
     * is limited to one entry; subsequent {@code put(...)} calls replace the
     * previous entry and the new token supersedes the old one.
     */
    public static void put(String sessionId, String token, GraphData data) {
        if (sessionId == null || token == null || data == null) {
            throw new IllegalArgumentException("sessionId, token and data must be non-null");
        }
        BY_SESSION.put(sessionId, new Entry(token, data));
    }

    /**
     * Returns the entry for the given session, or {@code null} when no
     * entry exists or the entry has been evicted. Does NOT check the token
     * — callers should verify the token themselves if they need to.
     */
    public static Entry get(String sessionId) {
        return BY_SESSION.get(sessionId);
    }

    /** Remove the entry for the given session. Idempotent. */
    public static void evictSession(String sessionId) {
        if (sessionId == null) return;
        BY_SESSION.remove(sessionId);
    }

    /**
     * Test/maintenance hook: drop every entry older than {@link #MAX_AGE_MINUTES}.
     * Invoked by the daemon evictor; can be called manually from tests.
     */
    public static int evictStale() {
        Instant cutoff = Instant.now().minusMillis(MAX_AGE_MS);
        int[] removed = { 0 };
        BY_SESSION.entrySet().removeIf(e -> {
            boolean stale = e.getValue().createdAt.isBefore(cutoff);
            if (stale) removed[0]++;
            return stale;
        });
        return removed[0];
    }

    /** Test-only: total entry count (no locking). */
    public static int size() {
        return BY_SESSION.size();
    }

    /** Test-only: snapshot of all current entries. */
    public static Map<String, Entry> snapshot() {
        return new ConcurrentHashMap<>(BY_SESSION);
    }
}
