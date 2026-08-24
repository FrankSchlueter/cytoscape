package de.tk.dependencyanalyse.rapui.visgraph.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tag-Property: a string property of a node label whose value is shared
 * across multiple nodes of the same label and therefore identifies a
 * "subtype" within that label.
 *
 * <p>Example: {@code Class} nodes with a {@code classType} property whose
 * values are {@code Enum}, {@code Record}, {@code Controller},
 * {@code Entity} — every value appears on multiple nodes, and the value
 * is a useful visual classifier.</p>
 *
 * <p>The instance is immutable. Use {@link #withValueColor(String, String)}
 * to derive a modified copy.</p>
 */
public final class TagProperty {

    private final String label;
    private final String property;
    private final Map<String, String> valueColors;

    public TagProperty(String label, String property, Map<String, String> valueColors) {
        this.label = Objects.requireNonNull(label, "label");
        this.property = Objects.requireNonNull(property, "property");
        this.valueColors = valueColors == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(valueColors));
    }

    public String getLabel() { return label; }

    public String getProperty() { return property; }

    public Map<String, String> getValueColors() { return valueColors; }

    /** Distinct count of values. */
    public int distinctCount() { return valueColors.size(); }

    /** Returns a new {@link TagProperty} with the given value-color mapping overridden. */
    public TagProperty withValueColor(String value, String color) {
        Map<String, String> next = new LinkedHashMap<>(valueColors);
        next.put(Objects.requireNonNull(value, "value"), Objects.requireNonNull(color, "color"));
        return new TagProperty(label, property, next);
    }

    /** Stable display string such as {@code classType (4)}. */
    public String displayLabel() {
        return property + " (" + distinctCount() + ")";
    }

    @Override
    public String toString() {
        return "TagProperty[" + label + "/" + property + " values=" + valueColors.keySet() + "]";
    }
}