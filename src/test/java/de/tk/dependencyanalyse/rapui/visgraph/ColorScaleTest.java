package de.tk.dependencyanalyse.rapui.visgraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ColorScaleTest {

    @Test
    void zeroPaletteYieldsEmptyMap() {
        assertEquals(0, ColorScale.interpolate(0).palette().length);
        assertTrue(ColorScale.interpolate(0).asMap(List.of("a", "b")).isEmpty());
    }

    @Test
    void onePaletteHasSingleColor() {
        String[] palette = ColorScale.interpolate(1).palette();
        assertEquals(1, palette.length);
        assertNotNull(palette[0]);
        assertEquals(7, palette[0].length());     // #RRGGBB
        assertTrue(palette[0].startsWith("#"));
    }

    @Test
    void nColorsAreDistinct() {
        // Hue interpolation should yield visually distinct samples.
        String[] palette = ColorScale.interpolate(8).palette();
        assertEquals(8, palette.length);
        for (int i = 0; i < palette.length; i++) {
            for (int j = i + 1; j < palette.length; j++) {
                assertNotEquals(palette[i], palette[j],
                        "duplicate color at positions " + i + " / " + j);
            }
        }
    }

    @Test
    void asMapAssignsColorsInOrder() {
        ColorScale scale = ColorScale.interpolate(3);
        Map<String, String> map = scale.asMap(List.of("a", "b", "c"));
        assertEquals(3, map.size());
        String[] palette = scale.palette();
        assertEquals(palette[0], map.get("a"));
        assertEquals(palette[1], map.get("b"));
        assertEquals(palette[2], map.get("c"));
    }

    @Test
    void asMapHandlesMoreKeysThanPaletteColors() {
        // palette length 2, but 5 keys → palette wraps around.
        ColorScale scale = ColorScale.interpolate(2);
        Map<String, String> map = scale.asMap(List.of("a", "b", "c", "d", "e"));
        assertEquals(5, map.size());
        assertEquals(map.get("a"), map.get("c"));
        assertEquals(map.get("b"), map.get("d"));
        assertNotEquals(map.get("a"), map.get("b"));
    }

    @Test
    void allColorsAreHexFormat() {
        String[] palette = ColorScale.interpolate(12).palette();
        for (String color : palette) {
            assertTrue(color.matches("#[0-9A-F]{6}"), "bad hex color: " + color);
        }
    }
}
