package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flexible color specification for vis-network nodes/edges.
 *
 * Three modes:
 *   - Single color string:           new ColorSpec("#FF0000")
 *   - Background + border pair:      ColorSpec.background("#4A90E2").border("#222")
 *   - Passthrough full vis color obj: ColorSpec.passthrough({background, border, highlight, hover})
 */
public final class ColorSpec {

    private final String value;
    private final Map<String, Object> passthrough;
    private final String background;
    private final String border;

    private ColorSpec(String value, String background, String border, Map<String, Object> passthrough) {
        this.value = value;
        this.background = background;
        this.border = border;
        this.passthrough = passthrough;
    }

    public static ColorSpec of(String color) {
        if (color == null) {
            throw new IllegalArgumentException("color must not be null");
        }
        return new ColorSpec(color, null, null, null);
    }

    public static ColorSpec background(String color) {
        if (color == null) {
            throw new IllegalArgumentException("background must not be null");
        }
        return new ColorSpec(null, color, null, null);
    }

    public ColorSpec border(String color) {
        if (color == null) {
            throw new IllegalArgumentException("border must not be null");
        }
        return new ColorSpec(null, this.background, color, null);
    }

    public static ColorSpec passthrough(Map<String, Object> full) {
        if (full == null) {
            throw new IllegalArgumentException("passthrough map must not be null");
        }
        return new ColorSpec(null, null, null, new LinkedHashMap<>(full));
    }

    public Object toVisValue() {
        if (passthrough != null) {
            return passthrough;
        }
        if (background != null || border != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (background != null) m.put("background", background);
            if (border != null) m.put("border", border);
            return m;
        }
        return value;
    }

    /**
     * Reduces any of the supported color representations (raw string, this
     * {@link ColorSpec}, or a passthrough {@link Map}) to a single hex/rgb
     * string. NVL's color parser ({@code u.get.rgb}) calls {@code .match()}
     * on its input, so it requires a string and crashes on objects.
     */
    public String toNvlString() {
        if (value != null) return value;
        if (background != null) return background;
        if (passthrough != null) {
            Object v = passthrough.get("background");
            if (v instanceof String s) return s;
            v = passthrough.get("color");
            if (v instanceof String s) return s;
        }
        return null;
    }

    /**
     * Convenience: reduce any color-spec-like object to a single string for
     * engines (NVL) that only understand a flat color string. Returns
     * {@code null} when no usable string can be extracted.
     */
    public static String toNvlString(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return s;
        if (raw instanceof ColorSpec cs) return cs.toNvlString();
        if (raw instanceof Map<?, ?> m) {
            Object v = m.get("background");
            if (v instanceof String s) return s;
            v = m.get("color");
            if (v instanceof String s) return s;
            v = m.get("value");
            if (v instanceof String s) return s;
        }
        return raw.toString();
    }

    public String getValue() { return value; }
    public String getBackground() { return background; }
    public String getBorder() { return border; }
    public Map<String, Object> getPassthrough() { return passthrough; }
}
