package de.tk.dependencyanalyse.rapui.visgraph.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level checks for {@link SvgRenderer#renderTransparentSvgIconOverlay}.
 *
 * <p>The renderer produces a 43×43 transparent SVG: an icon centered in
 * the canvas plus, optionally, an annotation circle at the bottom-right
 * corner with a single character inside. Used by the NVL overlayIcon
 * pipeline — see {@code Neo4jNvlViewer} / {@code NvlJsBridge.applyNodeImages}.</p>
 */
class SvgRendererTransparentOverlayTest {

    @Test
    void missingIconNameReturnsNull() {
        assertNull(SvgRenderer.renderTransparentSvgIconOverlay(null, ' ', null));
        assertNull(SvgRenderer.renderTransparentSvgIconOverlay("", ' ', null));
        assertNull(SvgRenderer.renderTransparentSvgIconOverlay("   ", ' ', null));
    }

    @Test
    void missingIconFileReturnsNull() {
        // Not present on classpath — must return null and log a warning,
        // never throw.
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "does-not-exist.svg", ' ', null);
        assertNull(out, "renderer must return null for unknown icon files");
    }

    @Test
    void canvasIsTransparent() {
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", ' ', null);
        assertNotNull(out);
        // No <rect width="..."> background layer must be present.
        assertFalse(out.contains("<rect"),
                "transparent overlay must not contain a background <rect>: " + out);
        // No fill on the outer <svg> (transparent background inherits).
        assertFalse(out.contains("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"43\" height=\"43\" fill="),
                "outer SVG must not carry a fill attribute: " + out);
    }

    @Test
    void outerCanvasIs48By48() {
        // Canvas was widened from 43×43 → 48×48 so the 30%-smaller
        // annotation circle (r=8) shifted 5 px right (cx=36) fits
        // comfortably inside (right edge at 44, below the 48 px canvas).
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", ' ', null);
        assertNotNull(out);
        assertTrue(out.contains("width=\"48\""),
                "outer SVG must declare width=48, got: " + out);
        assertTrue(out.contains("height=\"48\""),
                "outer SVG must declare height=48, got: " + out);
    }

    @Test
    void withoutAnnotationNoCircleOrTextEmitted() {
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", ' ', null);
        assertNotNull(out);
        assertFalse(out.contains("<circle"),
                "blank type must skip the annotation <circle>: " + out);
        assertFalse(out.contains("<text"),
                "blank type must skip the annotation <text>: " + out);
    }

    @Test
    void withAnnotationCircleAndCharAreEmitted() {
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", 'X', "#FF6B6B");
        assertNotNull(out);
        // Annotation circle anchored at the bottom-right corner.
        // 30% smaller (r=12 → r=8) and shifted 5 px right (cx=31 → cx=36).
        assertTrue(out.contains("cx=\"36\"") && out.contains("cy=\"31\""),
                "annotation circle must be centered at (36,31) — 5 px right of "
                        + "the legacy (31,31), got: " + out);
        assertTrue(out.contains("r=\"8\""),
                "annotation circle must have radius 8 (30% smaller than "
                        + "the legacy r=12), got: " + out);
        // The configured circle background color must be applied.
        assertTrue(out.contains("fill=\"#FF6B6B\""),
                "annotation circle must use the configured background color, got: " + out);
        // The type character must be present, XML-escaped if needed.
        assertTrue(out.contains(">X</text>"),
                "annotation text must contain the type char X, got: " + out);
        // Annotation font-size is 30% larger than the 7 px baseline
        // (= 9 px) so the character stays readable in the smaller circle.
        assertTrue(out.contains("font-size=\"9\""),
                "annotation character must be 30% larger (font-size=9), got: " + out);
    }

    @Test
    void annotationCharZeroIsTreatedAsBlank() {
        // type = 0 means "no annotation" — same as ' '.
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", (char) 0, "#FF6B6B");
        assertNotNull(out);
        assertFalse(out.contains("<circle"),
                "type=0 must skip the annotation circle, got: " + out);
        assertFalse(out.contains("<text"),
                "type=0 must skip the annotation text, got: " + out);
    }

    @Test
    void annotationCircleUsesFallbackColorWhenNull() {
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", 'X', null);
        assertNotNull(out);
        // SvgRenderer's DEFAULT_CIRCLE_BG_COLOR = "#E24A4A".
        assertTrue(out.contains("fill=\"#E24A4A\""),
                "null annotation color must fall back to DEFAULT_CIRCLE_BG_COLOR, got: " + out);
    }

    @Test
    void iconPathsAreRecoloredToWhite() {
        // The source java-16-svgrepo-com.svg carries dark fills — the
        // overlay must recolor every explicit fill to white so the icon
        // reads on top of any NVL-node background color.
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", ' ', null);
        assertNotNull(out);
        // White fill must appear on the nested icon container (inherited).
        assertTrue(out.contains("fill=\"#ffffff\""),
                "nested icon container must set fill=#ffffff for inherited "
                        + "recoloring, got: " + out);
        // No black/dark fills should survive inside the icon body.
        assertFalse(out.contains("fill=\"#000000\""),
                "icon paths must not retain their original dark fills: " + out);
    }

    @Test
    void renderedBodyIsValidXmlClose() {
        String out = SvgRenderer.renderTransparentSvgIconOverlay(
                "java-16-svgrepo-com.svg", 'C', "#4ECDC4");
        assertNotNull(out);
        // The overlay legitimately embeds a nested <svg> for the icon,
        // so the open-/close-tag count must balance as a whole — not
        // be exactly one.
        long opens = out.split("<svg", -1).length - 1;
        long closes = out.split("</svg>", -1).length - 1;
        assertEquals(opens, closes,
                "open and close <svg> tags must balance, got: " + out);
        assertTrue(out.endsWith("</svg>"),
                "outer SVG must end with </svg>, got: " + out);
    }
}