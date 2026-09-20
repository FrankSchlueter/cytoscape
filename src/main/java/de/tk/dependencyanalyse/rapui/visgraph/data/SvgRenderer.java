package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless SVG rendering utilities.
 *
 * <p>All render methods return a raw SVG string (NOT a data-URI). Call
 * {@link #toDataUri(String)} to convert the result into a
 * {@code data:image/svg+xml;charset=utf-8,...} string suitable as a
 * Cytoscape node {@code image} attribute or HTML {@code <img src>}.</p>
 *
 * <p>For HTML reports use {@link SvgReportBuilder}, which embeds the SVG
 * <em>inline</em> and therefore needs no encoding at all.</p>
 */
public final class SvgRenderer {

    private static final Logger LOG = Logger.getLogger(SvgRenderer.class.getName());

    /** Classpath prefix where SVG icon assets live. */
    private static final String ICON_RESOURCE_PREFIX = "/static/icons/";

    /** Fallback colors when callers pass {@code null} or blank. */
    private static final String DEFAULT_ICON_BG_COLOR   = "#4A90E2";
    private static final String DEFAULT_CIRCLE_BG_COLOR = "#E24A4A";
    private static final String ICON_FG_COLOR           = "#ffffff";

    /** Cache of raw icon SVG bodies keyed by file name (loaded lazily). */
    private static final ConcurrentMap<String, String> ICON_CACHE = new ConcurrentHashMap<>();

    /** Matches the {@code viewBox="x y w h"} attribute on the root &lt;svg&gt;. */
    private static final Pattern VIEWBOX_PATTERN = Pattern.compile(
            "viewBox\\s*=\\s*\"\\s*([\\d.+\\-eE]+)\\s+([\\d.+\\-eE]+)\\s+([\\d.+\\-eE]+)\\s+([\\d.+\\-eE]+)\"");

    /**
     * Matches an explicit {@code fill="<color>"} attribute on any SVG shape /
     * container element, EXCEPT {@code fill="none"} which is intentional.
     * Groups: (1) the tag opening up to (but excluding) "fill".
     */
    private static final Pattern ANY_FILL_PATTERN = Pattern.compile(
            "(<(?:path|circle|rect|polygon|polyline|ellipse|line|g|use|text)\\b[^>]*?)"
            + "\\bfill\\s*=\\s*\"(?!none)([^\"]*?)\"",
            Pattern.CASE_INSENSITIVE);

    // Prevent instantiation ? all methods are static.
    private SvgRenderer() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Render an SVG icon with an annotation circle badge in the bottom-right
     * corner.
     *
     * <p>Layout (total outer SVG: 43&times;43 px):</p>
     * <pre>
     *   +----------------------------+
     *   |  +----------------------+  |   &lt;- 31&times;31 rounded-rect (iconBackgroundColor)
     *   |  |   icon  25&times;25  |  |      centered at offset (3,3)
     *   |  +------------------+   |  |
     *   +---------------------+---+--+
     *                         | C |        &lt;- annotation circle r=12
     *                         +---+          center at (31,31), circleBackgroundColor
     * </pre>
     *
     * @param iconName            SVG file name relative to {@code /static/icons/}
     * @param iconBackgroundColor fill color for the 31&times;31 rounded rectangle
     * @param circleBackgroundColor fill color for the annotation circle
     * @param type                character rendered centered in the annotation circle
     * @return rendered SVG body (NOT a data-URI); {@code null} when icon not found
     */
    public static String renderSvgIconWithAnnotation(
            String iconName,
            String iconBackgroundColor,
            String circleBackgroundColor,
            char   type) {

        if (iconName == null || iconName.isBlank()) {
            LOG.warning("renderSvgIconWithAnnotation: missing iconName");
            return null;
        }

        String cacheKey = iconName + "|" + type;
        String source = ICON_CACHE.computeIfAbsent(iconName, SvgRenderer::loadIconResource);
        if (source == null) {
            LOG.warning("renderSvgIconWithAnnotation: icon not found on classpath: "
                    + ICON_RESOURCE_PREFIX + iconName);
            return null;
        }

        String safeBg     = blank(iconBackgroundColor)   ? DEFAULT_ICON_BG_COLOR   : iconBackgroundColor;
        String safeCircle = blank(circleBackgroundColor) ? DEFAULT_CIRCLE_BG_COLOR : circleBackgroundColor;

        // --- Parse viewBox of the source icon so the embedded icon keeps its aspect ratio ---
        double vbX = 0.0, vbY = 0.0, vbW = 16.0, vbH = 16.0;
        Matcher vb = VIEWBOX_PATTERN.matcher(source);
        if (vb.find()) {
            try {
                vbX = Double.parseDouble(vb.group(1));
                vbY = Double.parseDouble(vb.group(2));
                vbW = Double.parseDouble(vb.group(3));
                vbH = Double.parseDouble(vb.group(4));
            } catch (NumberFormatException ignored) { /* keep defaults */ }
        }

        // --- Recolor all explicit fill attributes (except fill="none") to white ---
        // ANY_FILL_PATTERN covers path / circle / rect / g / ... so mono icons
        // whose paths have fill="#000000" or fill="#1C274C" are fully recolored.
        String recolored = ANY_FILL_PATTERN.matcher(source)
                .replaceAll("$1fill=\"" + ICON_FG_COLOR + "\"");

        // Strip the root <svg ...> wrapper so the body can be embedded inside
        // our own nested <svg> element with explicit 25×25 dimensions.
        recolored = recolored.replaceFirst("<svg[^>]*>", "");
        recolored = recolored.replaceFirst("</svg>\\s*$", "");

        // --- Layout constants ---
        final int rectSize     = 31;   // background rounded-rect
        final int cornerRadius = 5;
        final int iconSize     = 25;   // nested icon
        final int iconX        = (rectSize - iconSize) / 2;  // = 3
        final int iconY        = (rectSize - iconSize) / 2;  // = 3
        // Annotation circle: center at the bottom-right corner of the rect
        final int circleX      = rectSize;          // = 31
        final int circleY      = rectSize;          // = 31
        final int circleR      = 12;
        // Total SVG must fit the circle that extends beyond the rect
        final int totalSize    = rectSize + circleR; // = 43

        StringBuilder out = new StringBuilder(512);

        // Outer SVG wrapper
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\"")
           .append(" width=\"").append(totalSize).append("\"")
           .append(" height=\"").append(totalSize).append("\">");

        // 1) Rounded-rectangle background
        out.append("<rect")
           .append(" width=\"").append(rectSize).append("\"")
           .append(" height=\"").append(rectSize).append("\"")
           .append(" rx=\"").append(cornerRadius).append("\"")
           .append(" ry=\"").append(cornerRadius).append("\"")
           .append(" fill=\"").append(safeBg).append("\"/>");

        // 2) Nested <svg> embedding the recolored icon body at iconSize×iconSize.
        //    fill="#ffffff" on the container sets the default inherited fill to
        //    white, so any icon element that does NOT carry an explicit fill
        //    attribute (but relies on inheriting from the root SVG) is also
        //    rendered white on the colored background.
        out.append("<svg")
           .append(" x=\"").append(iconX).append("\"")
           .append(" y=\"").append(iconY).append("\"")
           .append(" width=\"").append(iconSize).append("\"")
           .append(" height=\"").append(iconSize).append("\"")
           .append(" fill=\"").append(ICON_FG_COLOR).append("\"")
           .append(" viewBox=\"").append(vbX).append(" ").append(vbY)
           .append(" ").append(vbW).append(" ").append(vbH).append("\">");
        out.append(recolored);
        out.append("</svg>");

        // 3) Annotation circle (bottom-right corner, partly overlapping the rect)
        out.append("<circle")
           .append(" cx=\"").append(circleX).append("\"")
           .append(" cy=\"").append(circleY).append("\"")
           .append(" r=\"").append(circleR).append("\"")
           .append(" fill=\"").append(safeCircle).append("\"/>");

        // 4) Type character centered inside the annotation circle.
        //    dy="0.35em" is the reliable cross-browser vertical-centering trick.
        out.append("<text")
           .append(" x=\"").append(circleX).append("\"")
           .append(" y=\"").append(circleY).append("\"")
           .append(" font-family=\"Segoe UI, Arial, sans-serif\"")
           .append(" font-size=\"10\"")
           .append(" font-weight=\"bold\"")
           .append(" fill=\"").append(ICON_FG_COLOR).append("\"")
           .append(" text-anchor=\"middle\"")
           .append(" dy=\"0.35em\">")
           .append(xmlEscape(String.valueOf(type)))
           .append("</text>");

        out.append("</svg>");
        return out.toString();
    }

/**
     * Render a transparent overlay SVG: an icon centered inside a 48×48
     * canvas with an optional annotation circle (character on a colored
     * background) anchored at the bottom-right corner.
     *
     * <p>Layout (total outer SVG: 48&times;48 px):</p>
     * <pre>
     *   +---------------------------+
     *   |                           |
     *   |        icon  25&times;25  |   &lt;- icon centered at (24, 24),
     *   |                           |      fill = white (recolored)
     *   +---------------------+-----+
     *                         | C |       &lt;- annotation circle r=8
     *                          +---+        (30% smaller than the
     *                         +---+         composite-badge variant),
     *                         | C |        center at (36,31) =
     *                         +---+         (31,31) + 5 px right shift
     * </pre>
     *
     * <p>Unlike {@link #renderSvgIconWithAnnotation} this renderer
     * <strong>does not</strong> emit the rounded-rectangle background —
     * the SVG is fully transparent so the underlying NVL-node circle
     * ({@code color} attribute on the node) shows through. The icon is
     * drawn in white because NVL fills its native node with the
     * {@code color} attribute, and a white-on-color composition is the
     * only universally-readable palette.</p>
     *
     * <p>When {@code type} is {@code ' '} or {@code 0} no annotation
     * circle is drawn and the result is a 48×48 transparent SVG with just
     * the white icon centered.</p>
     *
     * @param iconName            SVG file name relative to {@code /static/icons/}
     * @param type                character rendered centered in the annotation
     *                            circle; pass {@code ' '} or {@code 0} to skip the
     *                            annotation entirely
     * @param circleBackgroundColor fill color for the annotation circle
     * @return rendered SVG body (NOT a data-URI); {@code null} when icon not found
     */
    public static String renderTransparentSvgIconOverlay(
            String iconName,
            char type,
            String circleBackgroundColor) {

        if (iconName == null || iconName.isBlank()) {
            LOG.warning("renderTransparentSvgIconOverlay: missing iconName");
            return null;
        }

        String source = ICON_CACHE.computeIfAbsent(iconName, SvgRenderer::loadIconResource);
        if (source == null) {
            LOG.warning("renderTransparentSvgIconOverlay: icon not found on classpath: "
                    + ICON_RESOURCE_PREFIX + iconName);
            return null;
        }

        boolean wantAnnotation = (type != ' ' && type != 0);
        String safeCircle = blank(circleBackgroundColor) ? DEFAULT_CIRCLE_BG_COLOR : circleBackgroundColor;

        // --- Parse viewBox of the source icon so the embedded icon keeps its aspect ratio ---
        double vbX = 0.0, vbY = 0.0, vbW = 16.0, vbH = 16.0;
        Matcher vb = VIEWBOX_PATTERN.matcher(source);
        if (vb.find()) {
            try {
                vbX = Double.parseDouble(vb.group(1));
                vbY = Double.parseDouble(vb.group(2));
                vbW = Double.parseDouble(vb.group(3));
                vbH = Double.parseDouble(vb.group(4));
            } catch (NumberFormatException ignored) { /* keep defaults */ }
        }

        // Recolor all explicit fill attributes (except fill="none") to white
        // so the icon reads on top of the NVL-node color underneath.
        String recolored = ANY_FILL_PATTERN.matcher(source)
                .replaceAll("$1fill=\"" + ICON_FG_COLOR + "\"");

        // Strip the root <svg ...> wrapper so the body can be embedded
        // inside our own nested <svg> with explicit dimensions.
        recolored = recolored.replaceFirst("<svg[^>]*>", "");
        recolored = recolored.replaceFirst("</svg>\\s*$", "");

        // --- Layout constants ---
        // 48×48 canvas — 5 px wider than renderSvgIconWithAnnotation's 43×43
        // so the 5-px right-shifted annotation circle fits inside the
        // canvas (r=8 + cx=36 → right edge at 44, comfortably below 48).
        final int iconSize = 25;
        // Center the icon (offset = (48 - 25) / 2 = 11).
        final int iconX = 11;
        final int iconY = 11;
        // Annotation circle: 30% smaller than the composite-badge variant
        // (12 → 8), shifted 5 px to the right from (31,31) to (36,31).
        final int circleX = 36;
        final int circleY = 31;
        final int circleR = 8;
        final int totalSize = 48;

        StringBuilder out = new StringBuilder(512);

        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\"")
           .append(" width=\"").append(totalSize).append("\"")
           .append(" height=\"").append(totalSize).append("\">");

        // 1) Nested <svg> embedding the recolored icon body centered in the
        //    canvas. fill="#ffffff" on the container sets the default
        //    inherited fill to white so any element relying on inheritance
        //    is also rendered white.
        out.append("<svg")
           .append(" x=\"").append(iconX).append("\"")
           .append(" y=\"").append(iconY).append("\"")
           .append(" width=\"").append(iconSize).append("\"")
           .append(" height=\"").append(iconSize).append("\"")
           .append(" fill=\"").append(ICON_FG_COLOR).append("\"")
           .append(" viewBox=\"").append(vbX).append(" ").append(vbY)
           .append(" ").append(vbW).append(" ").append(vbH).append("\">");
        out.append(recolored);
        out.append("</svg>");

        // 2) Optional annotation circle + type character.
        if (wantAnnotation) {
            out.append("<circle")
               .append(" cx=\"").append(circleX).append("\"")
               .append(" cy=\"").append(circleY).append("\"")
               .append(" r=\"").append(circleR).append("\"")
               .append(" fill=\"").append(safeCircle).append("\"/>");

            // Annotation text grows to font-size 9 so the type character stays
            // readable in the 30%-smaller circle (r=8 → diameter 16 px).
            // 30% larger than the original font-size 7 baseline.
            out.append("<text")
               .append(" x=\"").append(circleX).append("\"")
               .append(" y=\"").append(circleY).append("\"")
               .append(" font-family=\"Segoe UI, Arial, sans-serif\"")
               .append(" font-size=\"9\"")
               .append(" font-weight=\"bold\"")
               .append(" fill=\"").append(ICON_FG_COLOR).append("\"")
               .append(" text-anchor=\"middle\"")
               .append(" dy=\"0.35em\">")
               .append(xmlEscape(String.valueOf(type)))
               .append("</text>");
        }

        out.append("</svg>");
        return out.toString();
    }

    /**
     * Wraps a raw SVG string in a {@code data:image/svg+xml;charset=utf-8,...}
     * data URI suitable for Cytoscape node {@code image} attributes or
     * vis-network {@code image} attributes.
     *
     * <p><strong>Note:</strong> Uses percent-encoding with {@code %20} for
     * spaces (NOT {@code +}) so SVG attributes such as
     * {@code viewBox="0 0 16 16"} survive the round-trip through the browser's
     * URI decoder intact.</p>
     *
     * @param svgBody the raw SVG string
     * @return the percent-encoded data URI
     */
    public static String toDataUri(String svgBody) {
        // URLEncoder.encode replaces spaces with '+', which data-URI decoders
        // treat as literal '+', breaking SVG attribute values like viewBox.
        // Replacing '+' ? '%20' produces correct RFC-3986 percent-encoding.
        return "data:image/svg+xml;charset=utf-8,"
                + URLEncoder.encode(svgBody, StandardCharsets.UTF_8)
                            .replace("+", "%20");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Load an SVG icon file from the classpath. Returns {@code null} on error. */
    private static String loadIconResource(String name) {
    	int annotationStart = name.indexOf('|');
        String path;
        if( annotationStart > 0 ) {
        	String iconName = name.substring(0, annotationStart);
        	path = ICON_RESOURCE_PREFIX + iconName;
        } else {
        	path = ICON_RESOURCE_PREFIX + name;
        }
        try (InputStream in = SvgRenderer.class.getResourceAsStream(path)) {
            if (in == null) return null;
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Strip the optional <?xml ...?> declaration.
            return body.replaceFirst("<\\?xml[^?]*\\?>\\s*", "");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "loadIconResource: failed to read " + path, e);
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** XML-escape a string so it is safe to embed inside SVG text content. */
    static String xmlEscape(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}