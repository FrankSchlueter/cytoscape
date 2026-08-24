package de.tk.dependencyanalyse.rapui.visgraph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates evenly-spaced, perceptually distinct colors along the HSV hue
 * circle and maps them onto a list of keys.
 *
 * <p>Used by {@link GraphConfigurationDialog} to assign a default color to
 * each tag value or nodeType when the user has not chosen a color yet.</p>
 *
 * <p>The palette starts at hue 0° (red) and ends at hue 300° (purple),
 * skipping the trailing {@code 360° == 0°} wrap so the first and last
 * samples do not collide. Saturation and Value are fixed at
 * {@code 0.65} / {@code 0.95} for a pleasant rainbow look on white.</p>
 *
 * <p>HSV→RGB conversion is intentionally hand-written to avoid pulling
 * {@code java.awt.Color} into a UI-agnostic code path.</p>
 */
public final class ColorScale {

    private final String[] palette;

    private ColorScale(String[] palette) {
        this.palette = palette;
    }

    /**
     * Build a palette of {@code n} evenly-spaced colors. When {@code n <= 0}
     * the returned palette is empty.
     */
    public static ColorScale interpolate(int n) {
        if (n <= 0) return new ColorScale(new String[0]);
        if (n == 1) return new ColorScale(new String[] { hsvToHex(0.0, 0.65, 0.95) });
        String[] out = new String[n];
        // Map index 0..n-1 to hue 0..300 step 300/(n-1).
        for (int i = 0; i < n; i++) {
            double hue = 300.0 * i / (n - 1);
            out[i] = hsvToHex(hue, 0.65, 0.95);
        }
        return new ColorScale(out);
    }

    /** Raw HEX palette (length 0 when {@code n} was non-positive). */
    public String[] palette() {
        return palette.clone();
    }

    /**
     * Apply the palette positionally to {@code keys}, in their declaration
     * order. The {@code i}-th key receives the {@code i}-th color, wrapping
     * if {@code keys.size() > palette.length}.
     */
    public Map<String, String> asMap(List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        if (palette.length == 0 || keys == null) return out;
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (k == null) continue;
            out.put(k, palette[i % palette.length]);
        }
        return out;
    }

    /* ---------------------------------------------------- HSV → RGB → HEX */

    private static String hsvToHex(double hue, double saturation, double value) {
        double h = ((hue % 360.0) + 360.0) % 360.0;
        double c = value * saturation;
        double hi = Math.floor(h / 60.0);
        double x = c * (1.0 - Math.abs((h / 60.0) % 2.0 - 1.0));
        double r1, g1, b1;
        int sector = (int) hi;
        switch (sector) {
            case 0:  r1 = c; g1 = x; b1 = 0; break;
            case 1:  r1 = x; g1 = c; b1 = 0; break;
            case 2:  r1 = 0; g1 = c; b1 = x; break;
            case 3:  r1 = 0; g1 = x; b1 = c; break;
            case 4:  r1 = x; g1 = 0; b1 = c; break;
            case 5:  r1 = c; g1 = 0; b1 = x; break;
            default: r1 = g1 = b1 = 0; break;
        }
        double m = value - c;
        int r = clamp((int) Math.round((r1 + m) * 255.0));
        int g = clamp((int) Math.round((g1 + m) * 255.0));
        int b = clamp((int) Math.round((b1 + m) * 255.0));
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static int clamp(int v) {
        if (v < 0)   return 0;
        if (v > 255) return 255;
        return v;
    }
}
