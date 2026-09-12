package de.tk.dependencyanalyse.rapui.visgraph.api;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-RAP-session holder for the latest {@link NodeConfig} configured by the
 * user. The {@link SigmaGraphController} consults this registry when
 * serialising the graphology payload so the user-set label/tag colours
 * travel with the JSON response — the alternative (pushing the NodeConfig
 * to the iframe via {@code BrowserFunction}) would require an extra JS-side
 * state and a manual recolour pass, which is wasteful for a property the
 * server can apply directly during serialisation.
 *
 * <p>The registry is updated by
 * {@link de.tk.dependencyanalyse.rapui.visgraph.internal.SigmaJsBridge#applyNodeConfig}
 * and is evicted by the {@link SigmaGraphCache} daemon evictor (entries
 * older than {@link SigmaGraphCache#MAX_AGE_MINUTES} minutes are dropped
 * in lockstep).</p>
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
