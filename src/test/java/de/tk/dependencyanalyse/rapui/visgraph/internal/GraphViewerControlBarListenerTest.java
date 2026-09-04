package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Physics / Auto-Fit checkboxes in
 * {@code GraphViewerControlBar}. Earlier revisions created the
 * widgets but never attached a {@code SelectionListener}, so
 * clicking them silently did nothing. This test pins the
 * listener wiring so a future refactor can't silently drop it.
 *
 * <p>Style choice: source-level regex inspection rather than
 * reflective field access. SWT widgets don't expose listeners
 * through a stable public API, and reflecting into
 * {@code Button.hooks} is fragile across versions. Source-level
 * matching is brittle against formatting but fails loudly when
 * the wiring is removed — exactly the regression we want to
 * catch.</p>
 */
class GraphViewerControlBarListenerTest {

    private static final String SRC =
            "src/main/java/de/tk/dependencyanalyse/rapui/visgraph/GraphViewerControlBar.java";

    @Test
    void physicsButtonIsWiredToViewerSetPhysics() throws IOException {
        String src = Files.readString(Path.of(SRC));
        int block = src.indexOf("physicsButton = new Button");
        assertTrue(block > 0, "physicsButton must be created in the control bar");
        String slice = src.substring(block,
                Math.min(src.length(), block + 1500));
        assertTrue(slice.contains("physicsButton.addSelectionListener"),
                "physicsButton.addSelectionListener must appear in the construction block");
        assertTrue(slice.contains("viewer.setPhysics"),
                "the physics listener must forward to viewer.setPhysics(...)");
    }

    @Test
    void autoFitButtonIsWiredToViewerSetAutoFitOnStabilization() throws IOException {
        String src = Files.readString(Path.of(SRC));
        int block = src.indexOf("autoFitButton = new Button");
        assertTrue(block > 0, "autoFitButton must be created in the control bar");
        String slice = src.substring(block,
                Math.min(src.length(), block + 1500));
        assertTrue(slice.contains("autoFitButton.addSelectionListener"),
                "autoFitButton.addSelectionListener must appear in the construction block");
        assertTrue(slice.contains("viewer.setAutoFitOnStabilization"),
                "the auto-fit listener must forward to viewer.setAutoFitOnStabilization(...)");
    }
}
