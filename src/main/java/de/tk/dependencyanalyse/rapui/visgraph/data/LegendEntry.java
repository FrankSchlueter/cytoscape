package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.util.Objects;

/**
 * One row in the optional color legend panel.
 *
 * <p>The legend is engine-agnostic: it carries a hex color, a human-readable
 * label (e.g. {@code "Cluster1"}, {@code "product: alpha"}, {@code "Class"}),
 * and the number of nodes currently using that color. The legend panel in
 * the browser renders these rows and supports click-to-highlight.</p>
 *
 * <p>Instances are immutable. The list ordering is significant — it is the
 * order in which the panel renders rows top-to-bottom.</p>
 *
 * @param colorHex hex color string including the leading {@code '#'} (case-insensitive)
 * @param label    human-readable label for the color
 * @param count    number of nodes currently associated with the color
 */
public record LegendEntry(String colorHex, String label, int count) {

    public LegendEntry {
        Objects.requireNonNull(colorHex, "colorHex");
        Objects.requireNonNull(label, "label");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0, got " + count);
        }
    }

    /** Lower-cased hex string (no leading {@code '#'} normalization — kept verbatim). */
    public String normalizedColor() {
        return colorHex.toLowerCase();
    }
}