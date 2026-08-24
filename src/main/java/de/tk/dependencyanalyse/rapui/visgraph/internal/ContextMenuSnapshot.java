package de.tk.dependencyanalyse.rapui.visgraph.internal;

import de.tk.dependencyanalyse.rapui.visgraph.callback.ContextMenuEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of a context menu for dispatching actions to the right target.
 *
 * <p>When the user right-clicks a node or relationship, the Java side knows
 * the target object. It builds a {@code ContextMenuSnapshot} containing the
 * menu entries <em>and</em> the original target. When the user clicks an
 * entry, the entry id alone comes back through the BrowserFunction; without
 * the snapshot, the bridge would have to look up the target by id (and would
 * fail in the multi-menu case where entry ids are UUIDs that don't survive
 * a second navigation).</p>
 *
 * <p>Shared between {@code VisJsBridge} (vis-network) and {@code NvlJsBridge}
 * (NVL). The snapshot is <em>per-menu-show</em> — the bridge replaces it
 * every time {@code showContextMenu} is called.</p>
 */
public final class ContextMenuSnapshot {

    private final List<ContextMenuEntry> entries;
    private final Object target;

    public ContextMenuSnapshot(List<ContextMenuEntry> entries, Object target) {
        this.entries = entries;
        this.target = target;
    }

    public Object target() { return target; }

    public ContextMenuEntry findById(String id) {
        return findById(entries, id);
    }

    private static ContextMenuEntry findById(List<ContextMenuEntry> list, String id) {
        for (ContextMenuEntry e : list) {
            if (id.equals(e.getId())) return e;
            if (e.getChildren() != null) {
                ContextMenuEntry child = findById(e.getChildren(), id);
                if (child != null) return child;
            }
        }
        return null;
    }

    public List<Map<String, Object>> toJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContextMenuEntry e : entries) out.add(toJsonEntry(e));
        return out;
    }

    private static Map<String, Object> toJsonEntry(ContextMenuEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("label", e.getLabel());
        m.put("separator", e.isSeparator());
        m.put("disabled", e.isDisabled());
        if (e.getChildren() != null && !e.getChildren().isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (ContextMenuEntry c : e.getChildren()) kids.add(toJsonEntry(c));
            m.put("children", kids);
        }
        return m;
    }
}
