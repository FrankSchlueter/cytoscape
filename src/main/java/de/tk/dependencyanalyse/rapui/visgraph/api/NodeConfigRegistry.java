package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-RAP-session holder for the latest {@link NodeConfig} configured by the
 * user.
 *
 * <p>Currently exposed as an extension point: callers can store a
 * per-session {@link NodeConfig} here so other components can retrieve
 * it later. The sigma engine's data push
 * ({@link de.tk.dependencyanalyse.rapui.visgraph.internal.SigmaJsBridge#applyData})
 * now keeps the active {@code NodeConfig} as an instance field on the
 * bridge, so it no longer reads from this registry — but external
 * controllers (sample-graph pre-warm, dialog settings sync) can still
 * read or write the registry as needed.</p>
 *
 * <p>Entries are evicted when the underlying RAP UI session is destroyed.</p>
 */
public final class NodeConfigRegistry {

    private static final ConcurrentMap<String, NodeConfig> BY_SESSION = new ConcurrentHashMap<>();

    private NodeConfigRegistry() {}

    public static void put(String sessionId, NodeConfig config) {
        if (sessionId == null || config == null) return;
        BY_SESSION.put(sessionId, config);
    }

    public static NodeConfig get(String sessionId) {
        return sessionId == null ? null : BY_SESSION.get(sessionId);
    }

    public static void evictSession(String sessionId) {
        if (sessionId == null) return;
        BY_SESSION.remove(sessionId);
    }

    /** Test-only: total entry count. */
    public static int size() {
        return BY_SESSION.size();
    }

    /** Test-only: snapshot of current state. */
    public static Map<String, NodeConfig> snapshot() {
        return new ConcurrentHashMap<>(BY_SESSION);
    }
}
