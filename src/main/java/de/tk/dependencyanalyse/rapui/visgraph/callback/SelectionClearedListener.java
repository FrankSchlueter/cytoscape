package de.tk.dependencyanalyse.rapui.visgraph.callback;

/**
 * Listener for selection-cleared events (user clicked empty canvas).
 */
@FunctionalInterface
public interface SelectionClearedListener {
    void selectionCleared();
}
