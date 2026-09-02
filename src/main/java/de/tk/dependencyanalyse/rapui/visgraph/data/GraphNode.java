package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;

/**
 * Generic graph node — Neo4j-agnostic in the public API.
 * Properties are an immutable snapshot of Neo4j node properties as key/value pairs.
 *
 * Visual attributes are kept on an internal map and applied during
 * {@link #toVisNetworkData()} serialization. Per-instance settings override
 * any earlier type-based customizer.
 */
public final class GraphNode {

	private static final String ICON_BACKGROUND_COLOR = "iconBackgroundColor";

	private static final String CIRCLE_BACKGROUND_COLOR = "circleBackgroundColor";

	private static final String ICON_NAME = "iconName";

	private static final String TYPE = "type";

	private static final String SVG_IMAGE = "svgImage";

    private static final String SVG_IMAGE_2 = "svgImage2";

	private static final String SHAPE = "shape";

    private static final String IMAGE = "image";
    
    private static final String RAW_SVG = "rawSvg";
    
    private static final String LABEL ="label";

    private static final Logger LOG = Logger.getLogger(GraphNode.class.getName());

    /** Classpath prefix where SVG icon assets live. */
    private static final String ICON_RESOURCE_PREFIX = "/static/icons/";

    /** Target width/height for icons rendered via {@link #setSvgIcon}. */
    private static final int ICON_SIZE = 30;

    /** Fallback fill color when callers pass {@code null}. */
    private static final String DEFAULT_ICON_COLOR = "#4A90E2";

    /** Cache of raw icon SVG bodies keyed by file name (loaded lazily). */
    private static final ConcurrentMap<String, String> ICON_CACHE = new ConcurrentHashMap<>();

    /** Matches the {@code viewBox="x y w h"} attribute on the root <svg>. */
    private static final Pattern VIEWBOX_PATTERN = Pattern.compile(
            "viewBox\\s*=\\s*\"\\s*([\\d.+\\-eE]+)\\s+([\\d.+\\-eE]+)\\s+([\\d.+\\-eE]+)\\s+([\\d.+\\-eE]+)\"");

    /** Matches the root {@code <svg ...>} opening tag (first occurrence). */
    private static final Pattern ROOT_SVG_PATTERN = Pattern.compile("<svg([^>]*)>", Pattern.CASE_INSENSITIVE);

    /** Strips a width / height attribute from a tag body. */
    private static final Pattern SIZE_ATTR_PATTERN = Pattern.compile(
            "\\s(?:width|height)\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    /** Matches a {@code fill="..."} attribute on a {@code <path>} element. */
    private static final Pattern PATH_FILL_PATTERN = Pattern.compile(
            "(<path\\b[^>]*?)\\bfill\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private final String id;
    private final List<String> labels;
    private final Map<String, Object> properties;
    private final Map<String, Object> visualAttrs = new LinkedHashMap<>();
    private String customCaption;
    private boolean captionOverride = false;
    private String customTooltip;
    private boolean tooltipOverride = false;
    private Map<String,Object> tooltipProperties;

	private Map<GraphNode, GraphRelationship> usedRelationships = new LinkedHashMap<>();
	private Map<GraphNode, GraphRelationship> usingRelationships = new LinkedHashMap<>();

	private String label = "";


    public GraphNode(String id, List<String> labels, Map<String, Object> properties) {
        this.id = Objects.requireNonNull(id, "id");
        this.labels = labels == null ? List.of() : List.copyOf(labels);
        this.properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public String getId() { return id; }
    public List<String> getLabels() { return labels; }
    public boolean hasLabel(String label) { return labels.contains(label); }
    public Map<String, Object> getProperties() { return properties; }
    
	public boolean hasPropertyValue( String propertyName, Object value) {
		if (propertyName == null || value == null) {
			return false;
		}
		Object propValue = properties.get(propertyName);
		return value.equals(propValue);
	}

    /* ---- visual setters (fluent, returns this) ---- */

    public GraphNode setTitle(String title) {
    	this.label = label;
        visualAttrs.put(LABEL, title);
        return this;
    }

	public void setTooltipProperties(Map<String, Object> tooltipProperties) {
		this.tooltipProperties = tooltipProperties;
	}

	public GraphNode setTitle(String nodeType, String title) {
		this.label = title;
		properties.put("_nodeType_", nodeType);
		visualAttrs.put("_title_", title);
		visualAttrs.put("_nodeType_", nodeType);
		visualAttrs.put(LABEL, "<" + nodeType + ">\n" + title);
		return this;
	}

	public String getLabel() {
		return label;
	}

	public void addUsedRelationship(GraphNode targetNode, GraphRelationship relationship) {
		usedRelationships.put(targetNode, relationship);
	}

	public Map<GraphNode, GraphRelationship> getUsedRelationships() {
		return usedRelationships;
	}

	public void addUsingRelationship(GraphNode targetNode, GraphRelationship relationship) {
		usingRelationships.put(targetNode, relationship);
	}

	public Map<GraphNode, GraphRelationship> getUsingRelationships() {
		return usingRelationships;
	}

    /**
     * Sets the caption used by NVL (see {@link #toNvlNode()}). Override
     * forces the given value; otherwise the caption falls back to
     * {@code properties.name} when present.
     */
    public GraphNode setCaption(String caption) {
        this.customCaption = caption;
        this.captionOverride = true;
        return this;
    }

    public GraphNode resetCaption() {
        this.customCaption = null;
        this.captionOverride = false;
        return this;
    }

    public boolean isCaptionOverridden() { return captionOverride; }

    /**
     * Returns the effective caption: either the explicit override from
     * {@link #setCaption(String)} or {@code properties.name} when present,
     * or {@code null}.
     */
    public String getCaption() {
        if (captionOverride) return customCaption;
        Object name = properties.get("name");
        return name == null ? null : String.valueOf(name);
    }

    public GraphNode setColor(String color) {
        visualAttrs.put("color", color);
        return this;
    }

    public GraphNode setColor(ColorSpec color) {
        visualAttrs.put("color", color.toVisValue());
        return this;
    }

    public GraphNode setShape(Shape shape) {
        if (shape != null) {
            visualAttrs.put(SHAPE, shape.name().toLowerCase());
        }
        return this;
    }

    public GraphNode setSize(int size) {
        visualAttrs.put("size", size);
        return this;
    }

    public GraphNode setIcon(String url) {
        visualAttrs.put(SHAPE, IMAGE);
        visualAttrs.put(IMAGE, url);
        return this;
    }

    /**
     * Mark this node as a 30×30 SVG icon badge.
     *
     * <p>Loads {@code svgIconName} from the classpath location
     * {@code /static/icons/}, scales it to {@value #ICON_SIZE}×{@value #ICON_SIZE}
     * pixels, fills the icon paths with the given {@code color}, and overlays
     * the {@code type} character centered on the icon. The result is encoded
     * as an {@code image/svg+xml} data URI and stored in the {@code image}
     * attribute (with {@code shape="image"}), so vis-network renders it
     * natively without further client-side work.</p>
     *
     * <p>The {@code label} argument is stored as the vis-network {@code label}
     * attribute and shown next to the icon (subject to the
     * {@link de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig#isShowTitle()}
     * setting). Pass {@code null} or an empty string to suppress it.</p>
     *
     * <p>If the icon file cannot be found on the classpath the call is a
     * no-op (a warning is logged) and any previously configured visual
     * attributes remain untouched.</p>
     *
     * @param svgIconName the SVG file name relative to {@code /static/icons/}
     *                    (e.g. {@code "java-16-svgrepo-com.svg"})
     * @param color       the fill color for the icon paths
     *                    (e.g. {@code "#4A90E2"}); may be {@code null} for
     *                    a sensible default
     * @param type        a single character rendered centered on top of the
     *                    icon (e.g. {@code 'C'} for class)
     * @param label       the vis-network label shown next to the icon
     */
    public GraphNode setSvgIcon(String svgIconName, String color, char type, String label) {
        if (svgIconName == null || svgIconName.isEmpty()) {
            LOG.warning("setSvgIcon: missing icon name");
            return this;
        }
        String source = ICON_CACHE.computeIfAbsent(svgIconName, GraphNode::loadIconResource);
        if (source == null) {
            LOG.warning("setSvgIcon: icon not found on classpath: "
                    + ICON_RESOURCE_PREFIX + svgIconName);
            return this;
        }
        // String rendered = renderIconSvg(source, color, type);
        String rendered = renderSvgIcon2(source, color, "white", type);
        String dataUri = toSvgDataUri(rendered);
        visualAttrs.put(SHAPE, IMAGE);
        visualAttrs.put(IMAGE, dataUri);
        visualAttrs.put(LABEL, label == null ? "" : label);
        return this;
    }

    /**
    * Render a compact text-only badge: a rounded rectangle whose width
     * adapts to the length of the text.
     *
     * <p>Layout:</p>
     * <pre>
     *   +---------------------------+
     *   |                           |  &lt;- height = 20
     *   |           type            |     width  = estTextWidth + 20
     *   |                           |     text   = font-size 14, centered
     *   +---------------------------+
     * </pre>
     *
     * <p>The text width is estimated from the character count (≈7 px per
     * character for Segoe UI / Arial / sans-serif at font-size 14); the
     * rectangle is then 20 px wider (≈10 px padding on each side). This is
     * an approximation; SVG renders text without an intrinsic width, so
     * the actual text may render slightly wider or narrower than the rect
     * assumes.</p>
     *
     * @param typeName Type name of the icon (e.g. "class", "enum", "record", "controller", "entity", "table")
     * @param color       the fill color for the icon paths
     *                    (e.g. {@code "#4A90E2"}); may be {@code null} for
     *                    a sensible default
     * @param label       the vis-network label shown next to the icon
     */
    public GraphNode setSvgText(String typeName, String color, String label) {
        String rendered = renderSvgIcon3(typeName, color, label);
        String dataUri = toSvgDataUri(rendered);
        visualAttrs.put(SHAPE, IMAGE);
        visualAttrs.put(IMAGE, dataUri);
        visualAttrs.put("label", label == null ? "" : label);
        return this;
    }
    /**
     * Load an SVG icon file from the classpath. Returns {@code null} when the
     * resource is missing or unreadable.
     */
    private static String loadIconResource(String name) {
        String path = ICON_RESOURCE_PREFIX + name;
        try (InputStream in = GraphNode.class.getResourceAsStream(path)) {
            if (in == null) return null;
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Strip the <?xml ... ?> declaration. Some embedded webviews
            // (VSCode, older Electron) are stricter about XML conformance
            // when loading inline SVG via <img src="data:image/svg+xml,...">;
            // the declaration is optional for SVG-as-image and removing it
            // improves cross-webview compatibility.
            return body.replaceFirst("<\\?xml[^?]*\\?>\\s*", "");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "setSvgIcon: failed to read " + path, e);
            return null;
        }
    }

    /**
     * Render an SVG icon as a 50×30 rounded-rectangle badge with a
     * side-by-side layout.
     *
     * <p>Layout:</p>
     * <pre>
     *   +---------------------------------------+
     *   |  +---------+                          |
     *   |  |  ICON   |       C                  |  &lt;- 50 wide, 30 tall
     *   |  |  25×25  |                          |     corner radius 4
     *   |  +---------+                          |
     *   +---------------------------------------+
     * </pre>
     *
     * <p>Equivalent to {@link #renderIconSvg} but produces a true badge with
     * an explicit rounded-rectangle frame, the icon and the type character
     * laid out side-by-side (icon left, character right), and explicit
     * separate foreground and background colors.</p>
     *
     * @param source          the raw SVG icon body (e.g. loaded from
     *                        {@code /static/icons/}); may contain the
     *                        {@code <?xml ...?>} declaration, which is
     *                        stripped automatically
     * @param backgroundColor fill color for the rounded rectangle (defaults
     *                        to {@link #DEFAULT_ICON_COLOR} when {@code null})
     * @param foregroundColor fill color for the icon paths and the type
     *                        character (defaults to {@code "#ffffff"} when
     *                        {@code null})
     * @param type            a single character rendered to the right of
     *                        the icon
     * @return the rendered SVG body, sized 50×30, NOT wrapped in a data URI
     */
    public static String renderSvgIcon2(String source, String backgroundColor, String foregroundColor, char type) {
        String safeBg = (backgroundColor == null || backgroundColor.isEmpty())
                ? DEFAULT_ICON_COLOR : backgroundColor;
        String safeFg = (foregroundColor == null || foregroundColor.isEmpty())
                ? "#ffffff" : foregroundColor;

        // Strip XML declaration so the source can be embedded inline.
        if (source == null) source = "";
        source = source.replaceFirst("<\\?xml[^?]*\\?>\\s*", "");

        // Parse the source viewBox so the embedded icon retains its aspect ratio.
        double vbX = 0.0, vbY = 0.0, vbW = 16.0, vbH = 16.0;
        Matcher vb = VIEWBOX_PATTERN.matcher(source);
        if (vb.find()) {
            try {
                vbX = Double.parseDouble(vb.group(1));
                vbY = Double.parseDouble(vb.group(2));
                vbW = Double.parseDouble(vb.group(3));
                vbH = Double.parseDouble(vb.group(4));
            } catch (NumberFormatException ignored) {
                // keep defaults
            }
        }

        // Recolor all <path fill="..."> attributes to the foreground color.
        // NB: do NOT use Matcher.quoteReplacement — $1 must remain a live
        // backreference so the <path ...> prefix is preserved.
        String recolored = PATH_FILL_PATTERN.matcher(source).replaceAll(
                "$1fill=\"" + safeFg + "\"");

        // Strip the root <svg ...> and </svg> tags so we can embed the body
        // inside a nested <svg> with our explicit 25×25 dimensions.
        recolored = recolored.replaceFirst("<svg[^>]*>", "");
        recolored = recolored.replaceFirst("</svg>\\s*$", "");

        // Layout constants.
        //   Badge 50×30 (landscape) — gives the type character (font-size 25)
        //   its own dedicated space to the right of the 25×25 icon without
        //   overlap.
        final int width = 50;
        final int height = 30;
        final int iconSize = 25;
        final int iconX = 2;                            // 2-unit left padding
        final int iconY = (height - iconSize) / 2;     // = 2, centered vertically
        final int charX = width - 2;                    // = 48, right edge (2-unit right padding)
        final int charY = height / 2;                   // = 15, centered vertically
        final int charFontSize = 25;                    // user-requested character height
        final int cornerRadius = 4;

        StringBuilder out = new StringBuilder(256);
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
           .append("\" height=\"").append(height).append("\">");
        // 1) Rounded-rectangle background.
        out.append("<rect width=\"").append(width).append("\" height=\"").append(height)
           .append("\" rx=\"").append(cornerRadius).append("\" ry=\"").append(cornerRadius)
           .append("\" fill=\"").append(safeBg).append("\"/>");
        // 2) Nested <svg> embedding the recolored icon body at 25×25 on the left.
        out.append("<svg x=\"").append(iconX).append("\" y=\"").append(iconY)
           .append("\" width=\"").append(iconSize).append("\" height=\"").append(iconSize)
           .append("\" viewBox=\"").append(vbX).append(" ").append(vbY).append(" ")
           .append(vbW).append(" ").append(vbH).append("\">");
        out.append(recolored);
        out.append("</svg>");
        // 3) Type character on the right (text-anchor=end so it stays inside
        //    the rectangle even when its glyph is wider than the remaining
        //    space between the icon and the right edge).
        out.append("<text x=\"").append(charX).append("\" y=\"").append(charY)
           .append("\" font-family=\"Segoe UI, Arial, sans-serif\" font-size=\"").append(charFontSize)
           .append("\" font-weight=\"bold\" fill=\"").append(safeFg)
           .append("\" text-anchor=\"end\" dy=\"0.35em\">")
           .append(type).append("</text>");
        out.append("</svg>");

        return out.toString();
    }

    /**
     * Render a compact text-only badge: a rounded rectangle whose width
     * adapts to the length of the text.
     *
     * <p>Layout:</p>
     * <pre>
     *   +---------------------------+
     *   |                           |  &lt;- height = 40
     *   |                           |     width  = estTextWidth + 20
     *   |           type            |     text   = font-size 14, y = 22
     *   |                           |              (centered + 2 px down)
     *   +---------------------------+
     * </pre>
     *
     * <p>The text width is estimated from the character count (≈7 px per
     * character for Segoe UI / Arial / sans-serif at font-size 14); the
     * rectangle is then 20 px wider (≈10 px padding on each side). This is
     * an approximation; SVG renders text without an intrinsic width, so
     * the actual text may render slightly wider or narrower than the rect
     * assumes.</p>
     *
     * <p>The text content is XML-escaped before being spliced into the SVG
     * — angle brackets, ampersands, and quotes inside {@code type} would
     * otherwise produce invalid XML (e.g. the literal string "{@code <Table>}"
     * would terminate the {@code <text>} element prematurely and break the
     * whole image, which is exactly the failure mode of {@code setSvgText}
     * when called with such inputs).</p>
     *
     * @param type            the text shown in the badge (e.g. "C", "Class",
     *                        "&lt;Table&gt;", ...); will be XML-escaped
     * @param backgroundColor fill color for the rounded rectangle (defaults
     *                        to {@link #DEFAULT_ICON_COLOR} when {@code null})
     * @param label           fill color for the text (defaults to
     *                        {@code "#ffffff"} when {@code null}); named
     *                        {@code label} for symmetry with the other
     *                        render helpers
     * @return the rendered SVG body, NOT wrapped in a data URI
     */

    /**
     * Build a {@code data:image/svg+xml;base64,…} data URI from a raw
     * SVG body. Used by every viewer (cytoscape.js, vis-network) so the
     * wire format is uniform across the application.
     *
     * <p>Base64 is the only encoding that works for both Cytoscape and
     * vis-network:</p>
     *
     * <ul>
     *   <li><strong>Cytoscape</strong> parses {@code background-image}
     *       as a <em>list</em> of URLs (the {@code urls} type in the
     *       cytoscape stylesheet) and splits on commas. URL-encoded SVG
     *       bodies contain a comma right after the prefix and again
     *       inside the encoded body, so Cytoscape treated the URI as
     *       dozens of broken URLs and the SVG never loaded. Base64 has
     *       no commas.</li>
     *   <li><strong>vis-network</strong> uses
     *       {@code new Image(); image.src = uri} — the browser's Image
     *       loader accepts any RFC-2397 data URI, but the form-encoding
     *       produced by {@link java.net.URLEncoder} encodes spaces as
     *       {@code "+"} which the browser's data-URI decoder does NOT
     *       unescape to space. Base64 has no spaces and decodes
     *       byte-for-byte, so the browser always sees a valid SVG.</li>
     * </ul>
     */
    public static String toSvgDataUri(String svgBody) {
        if (svgBody == null) svgBody = "";
        // URL-safe Base64 (RFC 4648 �5) is the only encoding that works
        // reliably for BOTH vis-network AND Cytoscape.js:
        //
        //  Standard Base64 contains '+' and '/'.
        //  - vis-network / RAP: the '+' character is interpreted as a space
        //    (URL application/x-www-form-urlencoded decoding) somewhere in
        //    the RAP ? browser pipeline, producing an invalid base64 string
        //    with embedded spaces and causing "Could not load image".
        //  - URL-safe Base64 replaces '+' ? '-' and '/' ? '_', which are
        //    never misinterpreted as spaces or path separators.
        //
        //  URL-encoded SVG (e.g. URLEncoder) fails for Cytoscape because
        //  Cytoscape parses background-image as a comma-separated URL list
        //  and splits at every comma in the encoded body.
        //
        //  URL-safe Base64 has no commas, no '+', no '/', and no spaces ?
        //  all modern browsers accept it in data URIs.
        String b64 = Base64.getEncoder().encodeToString(
                svgBody.getBytes(StandardCharsets.UTF_8));
        return "data:image/svg+xml;base64," + b64;
    }
    
    public String toUft8SvgDataUri(String svgBody) {
        if (svgBody == null) svgBody = "";
        
        String utf8DataUri = "data:image/svg+xml;charset=utf-8,"
                + URLEncoder.encode(svgBody, StandardCharsets.UTF_8);

        return utf8DataUri;
	}
  
    public static String renderSvgIcon4(String type, String backgroundColor, String label) {
        String icon = "java-16-svgrepo-com.svg";
        String circleColor = "#4c2af3"; // Green circle
        char typeChar = (type != null && !type.isEmpty()) ? type.charAt(0) : ' ';

        switch (type.toLowerCase()) {
            case "class":
                typeChar = 'C';
                break;
            case "enum":
                typeChar = 'E';
                break;
            case "tkentity":
                icon = "source-code.svg";
                typeChar = 'E';
                break;
            case "tkcontroller":
                icon = "source-code.svg";
                typeChar = 'C';
               break;
            case "batchreader":
                icon = "database-svgrepo-com.svg";
                typeChar = 'R';
                break;
            case "batchwriter":
                icon = "database-svgrepo-com.svg";
                typeChar = 'W';
                break;
            case "tableinfo":
                icon = "database-svgrepo-com.svg";
                typeChar = 'T';
                break;
            default:
                circleColor = "#9B9B9B"; // Gray circle
        }
    
        String rawSvg =SvgRenderer.renderSvgIconWithAnnotation(icon, backgroundColor, circleColor, typeChar);

        return rawSvg;
    }

    public static String renderSvgIcon3(String type, String backgroundColor, String label) {
        String safeBg = (backgroundColor == null || backgroundColor.isEmpty())
                ? DEFAULT_ICON_COLOR : backgroundColor;
        String safeFg = (label == null || label.isEmpty()) ? "#ffffff" : label;
        String text = (type == null) ? "" : type;

        final int fontSize = 14;
        // Badge grew from 20 → 40 px tall ("20 px nach unten" — gives the
        // text room to sit a bit lower inside the badge rather than at the
        // very top).
        final int height = 40;
        final int sidePadding = 10;                      // 10 px on each side → 20 px total wider than text
        // Estimated text width — 7 px/char is a reasonable approximation for
        // Segoe UI / Arial / sans-serif at font-size 14. We clamp the minimum
        // to fontSize so single-character labels get a sane rect width.
        int estTextWidth = Math.max(text.length() * 7, fontSize);
        final int width = estTextWidth + 2 * sidePadding;
        final int cornerRadius = 4;
        // Text y = vertical center (20) + 2 px down per the explicit
        // "2 px nach unten" instruction. dy="0.35em" then centers the glyph
        // visually at this y.
        final double textY = height / 2.0 + 2;

        // XML-escape the text content. Without this, callers passing
        // strings like "<Table>" produce SVG that fails to parse and
        // renders as broken-image.
        String safeText = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");

        StringBuilder out = new StringBuilder(128);
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
           .append("\" height=\"").append(height).append("\">");
        // 1) Rounded-rectangle background.
        out.append("<rect width=\"").append(width).append("\" height=\"").append(height)
           .append("\" rx=\"").append(cornerRadius).append("\" ry=\"").append(cornerRadius)
           .append("\" fill=\"").append(safeBg).append("\"/>");
        // 2) Centered text (both axes). dy="0.35em" is the reliable
        //    cross-browser trick for vertical centering (see the matching
        //    comment in renderIconSvg).
        out.append("<text x=\"").append(width / 2.0).append("\" y=\"").append(textY)
           .append("\" font-family=\"Segoe UI, Arial, sans-serif\" font-size=\"").append(fontSize)
           .append("\" font-weight=\"bold\" fill=\"").append(safeFg)
           .append("\" text-anchor=\"middle\" dy=\"0.35em\">")
           .append(safeText).append("</text>");
        out.append("</svg>");

        return out.toString();
    }

    /**
     * Mark this node as an SVG-rendered badge. The vis-graph-viewer.js
     * bridge converts the {@code svgImage} attribute into a data-URI
     * {@code image} field at render time.
     *
     * @param label the display label inside the badge (typically the node
     *              name or id)
     * @param type  one of {@code "class"}, {@code "enum"},
     *              {@code "record"}, {@code "controller"}, {@code "entity"},
     *              {@code "table"} (or any other stereotype label)
     */
    public GraphNode setSvgShape(String label, String type) {
        return setSvgShape(label, type, null);
    }

    /**
     * As {@link #setSvgShape(String, String)} but with an explicit fill
     * color baked into the SVG. When {@code color} is {@code null}, the
     * bridge falls back to the node's current {@code color} attribute.
     *
     * <p>Stores both the {@code svgImage} descriptor (consumed by the
     * Cytoscape bridge via {@link #resolveCytoscapeImage()}) AND a
     * pre-rendered {@code image} data-URI plus {@code shape: image}
     * (consumed by vis-network's {@link #toVisNetworkData()}). Without
     * the pre-rendered data URI vis-network would receive only the raw
     * {@code {label,type,color}} map and refuse to draw anything.</p>
     */
    public GraphNode setSvgShape(String label, String type, String color) {
        java.util.Map<String, String> info = new java.util.LinkedHashMap<>();
        info.put(LABEL, label == null ? "" : label);
        info.put(TYPE, type == null ? "" : type);
        if (color != null) info.put("color", color);
        visualAttrs.put(SVG_IMAGE, info);

        // Pre-render so vis-network (which reads `image` directly off the
        // serialized node) sees a ready-to-use data URI. Cytoscape ignores
        // this and re-renders from svgImage via resolveCytoscapeImage().
        String safeBg = (color == null || color.isEmpty()) ? DEFAULT_ICON_COLOR : color;
        String rendered = renderSvgIcon4(
                type == null ? "" : type, safeBg,
                label == null ? "" : label);
        // vis-network surface: keep URL-encoded URI (vis does not split on
        // commas and the existing payloads rely on this format).
        String dataUri = toSvgDataUri(rendered);
        visualAttrs.put(SHAPE, IMAGE);
        visualAttrs.put(IMAGE, dataUri);
        return this;
    }
    
    public GraphNode setSvgShape(
            String iconName,
            String iconBackgroundColor,
            String circleBackgroundColor,
            char   type) {
        java.util.Map<String, String> info = new java.util.LinkedHashMap<>();
        info.put(LABEL, label == null ? "" : label);
        info.put(ICON_NAME, iconName == null ? "" : iconName);
        info.put(TYPE, ""+ type);
        if (iconBackgroundColor != null) info.put(ICON_BACKGROUND_COLOR, iconBackgroundColor);
        if (iconBackgroundColor != null) info.put(CIRCLE_BACKGROUND_COLOR, circleBackgroundColor);
        visualAttrs.put(SVG_IMAGE_2, info);
        String rawSvg = SvgRenderer.renderSvgIconWithAnnotation(iconName, iconBackgroundColor, circleBackgroundColor, type);
        setSvgImage(rawSvg);
        
    	return this;
    }
    
    
    
    public GraphNode setSvgImage( String rawSvg ) {
        String dataUri = toSvgDataUri(rawSvg);
        visualAttrs.put(SHAPE, IMAGE);
        visualAttrs.put(RAW_SVG, rawSvg);
        return this;
    }

    /**
     * Replace the background color of this node's SVG badge without
     * touching the {@code label} / {@code type} fields. Both surfaces
     * (the Cytoscape-specific {@code svgImage} descriptor and the
     * vis-network pre-rendered {@code image} URI) are updated.
     *
     * <p>No-op when the node has not been marked as an SVG badge via
     * {@link #setSvgShape(String, String)} / {@link #setSvgShape(String, String, String)} —
     * non-badge nodes are colored via Cytoscape's {@code background-color}
     * style and re-rendering their SVG would have no effect on either
     * surface.</p>
     *
     * @param newColor hex color string (e.g. {@code "#4A90E2"});
     *                 {@code null} or blank resets to the
     *                 {@link #DEFAULT_ICON_COLOR} fallback
     * @return {@code true} when the color actually changed and the
     *         pre-rendered {@code image} data URI was regenerated
     */
    public boolean recolorSvgShape(String newColor) {
        Object rawSvg = visualAttrs.get(SVG_IMAGE);
        Object rawSvg2 = visualAttrs.get(SVG_IMAGE_2);
        if (rawSvg instanceof Map<?, ?> rawMap) {
	        @SuppressWarnings("unchecked")
	        Map<String, String> info = (Map<String, String>) rawMap;
	        String safe = (newColor == null || newColor.isEmpty()) ? DEFAULT_ICON_COLOR : newColor;
	        String prev = info.get("color");
	        if (safe.equals(prev)) return false;
	        info.put("color", safe);
	        String label = info.getOrDefault(LABEL, "");
	        String type  = info.getOrDefault(TYPE, "");
	        String rendered = renderSvgIcon4(type, safe, label);
	        visualAttrs.put(IMAGE, toSvgDataUri(rendered));
	        return true;
        } else if (rawSvg2 instanceof Map<?, ?> rawMap) {
	        @SuppressWarnings("unchecked")
	        Map<String, String> info = (Map<String, String>) rawMap;
	        String safe = (newColor == null || newColor.isEmpty()) ? DEFAULT_ICON_COLOR : newColor;
	        String prev = info.get("color");
	        if (safe.equals(prev)) return false;
	        info.put("color", safe);
	        

	        String label = info.getOrDefault(LABEL, "");
	        String iconName = info.getOrDefault(ICON_NAME, "");
	        String iconBackgroundColor = info.getOrDefault(ICON_BACKGROUND_COLOR, "");
	        String circleBackgroundColor = info.getOrDefault(CIRCLE_BACKGROUND_COLOR, "");
	        String type  = info.getOrDefault(TYPE, "");
	        String rendered = SvgRenderer.renderSvgIconWithAnnotation(iconName, iconBackgroundColor, circleBackgroundColor, type.charAt(0));
	        visualAttrs.put(IMAGE, toSvgDataUri(rendered));
	        return true;
        } else {
        	return false;
        }
    }

    /**
     * Returns the {@code svgImage} descriptor map written by
     * {@link #setSvgShape(String, String, String)} — or {@code null} if
     * the node has not been marked as an SVG badge. The returned map is
     * the live internal map; callers MUST NOT mutate it (use
     * {@link #recolorSvgShape(String)} for safe updates).
     */
    public Map<String, String> getSvgImage() {
        Object raw = visualAttrs.get(SVG_IMAGE);
        return (raw instanceof Map<?, ?>) ? (Map<String, String>) raw : null;
    }

    public GraphNode setAttribute(String key, Object value) {
        visualAttrs.put(key, value);
        return this;
    }

    /* ---- tooltip ---- */

    public GraphNode setTooltip(String html) {
        this.customTooltip = html;
        this.tooltipOverride = true;
        return this;
    }

    public GraphNode resetTooltip() {
        this.customTooltip = null;
        this.tooltipOverride = false;
        return this;
    }

    public boolean isTooltipOverridden() { return tooltipOverride; }

    /* ---- serialization ---- */

    public Map<String, Object> toVisNetworkData() {
        return toVisNetworkData(null);
    }

    /**
     * Serializes the node for vis-network, optionally honoring a
     * {@link de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig}.
     *
     * <p>When {@code config != null} and {@code config.isShowTitle()} is
     * {@code false}, the {@code label} field is omitted from the output so
     * vis-network does not render the on-node text.</p>
     *
     * <p>Tag-value colors override the node's existing {@code color} when
     * matched. A node is matched by its first label (primary label) and the
     * presence of the tag property in its properties map.</p>
     */
	public Map<String, Object> toVisNetworkData(NodeConfig config) {
		Map<String, Object> out = new LinkedHashMap<>();
		Object titleProperty = visualAttrs.get("_title_");
		Object nodeTypeProperty = visualAttrs.get("_nodeType_");
//        Map<String, Object> tooltipProperties = new LinkedHashMap<>(nodeProperties);
		if (nodeTypeProperty != null) {
			tooltipProperties.put("Type", nodeTypeProperty);
		}
		out.put("id", id);
		if (visualAttrs.containsKey(LABEL)) {
			out.put("label", visualAttrs.get("label"));
		}
		if (visualAttrs.containsKey("title")) {
			out.put("title", visualAttrs.get("title"));
		} else {
			String title = tooltipOverride ? customTooltip
					: TooltipBuilder.fromProperties(titleProperty != null ? titleProperty.toString() : "",
							tooltipProperties);
			if (title != null && !title.isEmpty()) {
				out.put("title", title);
			}
		}
		for (Map.Entry<String, Object> e : visualAttrs.entrySet()) {
			String k = e.getKey();
			if ("label".equals(k) || "title".equals(k) || "_rawSvg_".equals(k))
				continue;
			out.put(k, e.getValue());
		}
		// Re-color SVG badge nodes when a globalTagColor override matches a
		// node property value. This is triggered by
		// GraphConfigurationDialog.applyTagColors() ? pushNodeConfig() ?
		// SwitchingViewer.setNodeConfig() ? VisJsBridge.applyNodeConfig() ?
		// applyData() ? toVisNetworkData(config).
		Object rawSvg = visualAttrs.get(RAW_SVG);
		if (rawSvg instanceof String ) {
			String rawSvgString = (String) rawSvg;
			String uft8SvgDataUri = toUft8SvgDataUri(rawSvgString);
			out.put("image", uft8SvgDataUri);
		}
		return out;
	}

    /**
     * Serializes the node for {@code @neo4j-nvl/base}.
     *
     * <p>NVL node shape: {@code { id, labels?, properties?, caption?, color? }}.
     * Sets {@code caption} (via {@link #getCaption()}) and {@code color}
     * (from visualAttrs) when present. Does NOT apply the
     * {@link de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig}-style
     * tag-value color override — NVL does not honor that pattern, so callers
     * who want per-node color rules should set them via
     * {@link #setColor(String)} on the node itself.</p>
     */
    public Map<String, Object> toNvlNode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        if (!labels.isEmpty()) {
            out.put("labels", labels);
        }
        // Properties: design D13 says NVL doesn't need them (it has no
        // per-node svgIcon/svglabelColor rendering like vis-network).
        // We still send them for downstream consumers that want to inspect
        // raw graph data via the DOM, but we coerce non-string values to
        // strings so NVL's color parser doesn't crash trying to call
        // .match() on a Number when it iterates properties.
        if (!properties.isEmpty()) {
            Map<String, Object> safeProps = new LinkedHashMap<>(properties.size());
            for (Map.Entry<String, Object> e : properties.entrySet()) {
                Object v = e.getValue();
                safeProps.put(e.getKey(), v == null ? null : (v instanceof String ? v : v.toString()));
            }
            out.put("properties", safeProps);
        }
        String caption = getCaption();
        if (caption != null && !caption.isEmpty()) {
            out.put("caption", caption);
        }
        // NVL expects `color` to be a string. vis-network's ColorSpec is
        // either a string, a {background, border} object, or a passthrough
        // map. Reduce it to a single hex/rgb string via ColorSpec.toNvlString
        // so NVL's rgb() parser can handle it. Otherwise NVL throws
        // "f.match is not a function" in setupNodeRendering -> Em(qv(v)).
        Object rawColor = visualAttrs.get("color");
        String nvlColor = ColorSpec.toNvlString(rawColor);
        if (nvlColor != null) {
            out.put("color", nvlColor);
        }
        return out;
    }

    /**
     * Build the {@code data.image} payload consumed by the Cytoscape bridge.
     *
     * <p>Returns a ready-to-use {@code image} value (either a caller-supplied
     * URL/URI from {@link #setIcon(String)} / {@link #setSvgIcon} or a freshly
     * rendered SVG data URI built via {@link #renderSvgIcon3} from the
     * {@code svgImage} map written by {@link #setSvgShape(String, String, String)}).
     * The Cytoscape JS bridge picks up this value and emits a
     * {@code background-image} style on the corresponding node.</p>
     *
     * <p>Returns {@code null} when no visual-attribute entry requests an
     * image — non-image nodes pass through untouched.</p>
     */
    @SuppressWarnings("unchecked")
    private String resolveCytoscapeImage() {
        // 1) svgImage descriptor from setSvgShape(...). This is the
        //    Cytoscape-specific entry; check it FIRST so the Cytoscape
        //    surface gets a base64-encoded URI even when setSvgShape also
        //    pre-baked a URL-encoded URI for the vis-network surface
        //    (see setSvgShape for the dual-encoding rationale).
        Object rawSvg = visualAttrs.get(SVG_IMAGE_2);
        if (rawSvg instanceof Map<?, ?> map) {
	        String iconName = stringify(map.get(ICON_NAME));
	        String type  = stringify(map.get(TYPE));
            String iconBackgroundColor = stringify(map.get(ICON_BACKGROUND_COLOR));
	        String circleBackgroundColor = stringify(map.get(CIRCLE_BACKGROUND_COLOR));
	        
	        String rendered = SvgRenderer.renderSvgIconWithAnnotation(iconName, iconBackgroundColor, circleBackgroundColor, type.charAt(0));
 
	        // Cytoscape surface: base64, not URL-encoded. Cytoscape parses
            // background-image as a comma-separated URL list and would split
            // a URL-encoded SVG payload at every comma in the SVG body,
            // silently breaking the image. Base64 has no commas and is
            // universally supported by the browser's Image() loader that
            // Cytoscape uses for background-image rendering.
            return toSvgDataUri(rendered);
        }
        // 2) Explicit image URL/URI from setIcon(...) / setSvgIcon(...).
        //    Pass through verbatim. For HTTP(S) URLs and short data: URIs
        //    (without embedded commas) Cytoscape can load them directly.
        Object rawImage = visualAttrs.get(IMAGE);
        if (rawImage instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    /** Stringify an svgImage map value, returning {@code null} for null/non-string. */
    private static String stringify(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Serializes the node as a Cytoscape.js element entry:
     * {@code { data: { id, label, nodeType, nodeTag, ...all-properties, tooltip? } }}.
     *
     * <p>The {@code data} object intentionally does NOT carry Cytoscape
     * visual attributes (color, shape, size, ...). Cytoscape styling is
     * configured separately via the style-selector map in the
     * {@code CytoscapeJsBridge}, which lets the user switch visualization
     * (e.g. color-by-nodeType vs shape-by-nodeType) without rewriting the
     * underlying data.</p>
     *
     * <p>The {@code label} field is set from {@code visualAttrs.label} when
     * present (matching the vis-network convention); otherwise from
     * {@link #getCaption()}; otherwise from {@code id}.</p>
     *
     * <p>{@code nodeType} is the primary label (first in the labels list)
     * and {@code nodeTag} is a string from the {@code nodeTag} property when
     * present (otherwise {@code null}). Both are surfaced as top-level
     * {@code data} fields so the Cytoscape style-selector can match on them
     * without having to dereference the properties sub-object.</p>
     *
     * <p>A {@code tooltip} field is added to {@code data} (built from the
     * node properties by {@link TooltipBuilder}) so the JS bridge can use
     * it for native Cytoscape tooltips.</p>
     */
    public Map<String, Object> toCytoscapeNode() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        String labelText = null;
        Object lblAttr = visualAttrs.get("label");
        if (lblAttr != null && !String.valueOf(lblAttr).isEmpty()) {
            labelText = String.valueOf(lblAttr);
        } else {
            labelText = getCaption();
        }
        if (labelText == null || labelText.isEmpty()) {
            labelText = id;
        }
        data.put("label", labelText);
        // nodeType priority:
        //   1. explicit "_nodeType_" property (set by GML/CSV importers like
        //      TVERS-Usage.gml where every node carries a _nodeType_ value)
        //   2. first GraphNode label (fallback for hand-built graphs)
        // The dialog's NodeType-Visualization style selectors key off
        // `data.nodeType` so this property name MUST match the value the
        // dialog discovered via `node[_nodeType_=...]` lookups.
        Object explicitNodeType = properties.get("_nodeType_");
        if (explicitNodeType != null && !String.valueOf(explicitNodeType).isEmpty()) {
            data.put("nodeType", String.valueOf(explicitNodeType));
        } else if (!labels.isEmpty()) {
            data.put("nodeType", labels.get(0));
        }
        Object nodeTag = properties.get("nodeTag");
        if (nodeTag != null) {
            data.put("nodeTag", String.valueOf(nodeTag));
        }
        // Copy all properties verbatim so consumers can build tooltips / selectors.
        // The "_nodeType_" property is intentionally NOT re-added here — it was
        // already promoted to data.nodeType above.
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            String key = e.getKey();
            if (key.equals("_nodeType_")) continue;
            if (data.containsKey(key)) continue;
            data.put(key, e.getValue());
        }
        // Resolve a Cytoscape data.image entry when the node has been marked
        // as an SVG badge via setSvgShape(...) or setIcon(...) / setSvgIcon(...).
        // The JS bridge turns `data.image` into a Cytoscape `background-image`
        // on the node selector so the SVG renders inside the node shape.
        String cytoscapeImage = resolveCytoscapeImage();
        if (cytoscapeImage != null && !cytoscapeImage.isEmpty()) {
            data.put(IMAGE, cytoscapeImage);
        }
        // Tooltip: prefer override, otherwise build from properties.
        String tooltip = tooltipOverride
                ? customTooltip
                : TooltipBuilder.fromProperties(id, properties);
        if (tooltip != null && !tooltip.isEmpty()) {
            data.put("tooltip", tooltip);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        return out;
    }
}
