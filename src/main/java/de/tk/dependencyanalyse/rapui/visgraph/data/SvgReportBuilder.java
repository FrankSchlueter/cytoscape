package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fluent builder for an HTML report that visualises SVG icon badges.
 *
 * <p>Icons are embedded <em>inline</em> as raw {@code <svg>} elements ? no
 * data-URI encoding is needed, which means the icons are always rendered
 * faithfully regardless of browser quirks with percent-encoding.</p>
 *
 * <p>For Cytoscape node images (which require a data-URI), call
 * {@link SvgRenderer#toDataUri(String)} on the SVG body returned by
 * {@link SvgRenderer#renderSvgIconWithAnnotation}.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * SvgReportBuilder report = new SvgReportBuilder("SVG Icon Preview");
 *
 * report.addHeader("Java Icons");
 * report.addIconWithAnnotation("java-16-svgrepo-com.svg", "#4A90E2", "#E24A4A", 'C');
 * report.addIconWithAnnotation("java-16-svgrepo-com.svg", "#27AE60", "#E24A4A", 'E', "MyEnum");
 *
 * report.addHeader("Database Icons");
 * report.addIconWithAnnotation("database-svgrepo-com.svg", "#8E44AD", "#F39C12", 'T', "MyTable");
 *
 * report.writeToFile(Path.of("icon-report.html"));
 * }</pre>
 */
public final class SvgReportBuilder {

    private static final Logger LOG = Logger.getLogger(SvgReportBuilder.class.getName());

    private final String pageTitle;
    private final StringBuilder body = new StringBuilder(4096);

    /** {@code true} while a {@code <div class="icon-row">} has been opened but not yet closed. */
    private boolean rowOpen = false;

    /**
     * Create a new report builder.
     *
     * @param pageTitle the text used as the HTML {@code <title>} and main
     *                  {@code <h1>} heading
     */
    public SvgReportBuilder(String pageTitle) {
        this.pageTitle = pageTitle == null ? "SVG Icon Report" : pageTitle;
    }

    // -------------------------------------------------------------------------
    // Fluent API
    // -------------------------------------------------------------------------

    /**
     * Add an {@code <h2>} section heading. Any open icon-row from the previous
     * section is closed first.
     *
     * @param text the heading text (will be HTML-escaped)
     * @return {@code this} for chaining
     */
    public SvgReportBuilder addHeader(String text) {
        closeRow();
        body.append("<h2>").append(htmlEscape(text)).append("</h2>\n");
        openRow();
        return this;
    }

    /**
     * Close the current icon-row and start a fresh one. Use this to force a
     * visual line-break within a section.
     *
     * @return {@code this} for chaining
     */
    public SvgReportBuilder newRow() {
        closeRow();
        openRow();
        return this;
    }

    /**
     * Render an icon via {@link SvgRenderer#renderSvgIconWithAnnotation} and
     * add it to the report as inline SVG (no caption).
     *
     * @param iconName              SVG file name relative to {@code /static/icons/}
     * @param iconBackgroundColor   fill color for the icon background rectangle
     * @param circleBackgroundColor fill color for the annotation circle
     * @param type                  character rendered inside the annotation circle
     * @return {@code this} for chaining
     */
    public SvgReportBuilder addIconWithAnnotation(
            String iconName,
            String iconBackgroundColor,
            String circleBackgroundColor,
            char   type) {
        return addIconWithAnnotation(iconName, iconBackgroundColor, circleBackgroundColor, type, null);
    }

    /**
     * Render an icon via {@link SvgRenderer#renderSvgIconWithAnnotation} and
     * add it to the report as inline SVG with an optional caption below.
     *
     * @param iconName              SVG file name relative to {@code /static/icons/}
     * @param iconBackgroundColor   fill color for the icon background rectangle
     * @param circleBackgroundColor fill color for the annotation circle
     * @param type                  character rendered inside the annotation circle
     * @param caption               optional label shown below the icon;
     *                              {@code null} / blank ? no caption
     * @return {@code this} for chaining
     */
    public SvgReportBuilder addIconWithAnnotation(
            String iconName,
            String iconBackgroundColor,
            String circleBackgroundColor,
            char   type,
            String caption) {

        String svgBody = SvgRenderer.renderSvgIconWithAnnotation(
                iconName, iconBackgroundColor, circleBackgroundColor, type);
        if (svgBody == null) {
            LOG.warning("addIconWithAnnotation: icon not found: '" + iconName + "' ? skipped");
            return this;
        }
        return addInlineSvg(caption, svgBody);
    }

    /**
     * Add an icon cell by embedding a raw SVG string directly in the HTML.
     * This is the most reliable display method ? no encoding is involved.
     *
     * @param caption optional label shown below the icon; {@code null}/blank ? none
     * @param svgBody the raw SVG markup (must be a well-formed {@code <svg>} element)
     * @return {@code this} for chaining
     */
    public SvgReportBuilder addInlineSvg(String caption, String svgBody) {
        ensureRowOpen();
        body.append("  <div class=\"icon-cell\">\n");
        body.append("    ").append(svgBody).append("\n");
        if (caption != null && !caption.isBlank()) {
            body.append("    <div class=\"caption\">").append(htmlEscape(caption)).append("</div>\n");
        }
        body.append("  </div>\n");
        return this;
    }

    /**
     * Add an icon cell from a pre-built {@code data:image/svg+xml,...} URI.
     * Prefer {@link #addInlineSvg} for HTML reports; use this only when you
     * already have a data URI (e.g. from {@link SvgRenderer#toDataUri}).
     *
     * @param caption optional label; {@code null}/blank ? none
     * @param dataUri the data URI string
     * @return {@code this} for chaining
     */
    public SvgReportBuilder addDataUri(String caption, String dataUri) {
        ensureRowOpen();
        body.append("  <div class=\"icon-cell\">\n");
        body.append("    <img src=\"").append(dataUri).append("\"");
        if (caption != null && !caption.isBlank()) {
            body.append(" alt=\"").append(htmlEscape(caption)).append("\"");
        }
        body.append("/>\n");
        if (caption != null && !caption.isBlank()) {
            body.append("    <div class=\"caption\">").append(htmlEscape(caption)).append("</div>\n");
        }
        body.append("  </div>\n");
        return this;
    }

    /**
     * Add a horizontal separator ({@code <hr>}). Any open icon-row is closed first.
     *
     * @return {@code this} for chaining
     */
    public SvgReportBuilder addSeparator() {
        closeRow();
        body.append("<hr/>\n");
        return this;
    }

    /**
     * Add a free-form paragraph of text. Any open icon-row is closed first.
     *
     * @param text the paragraph text (will be HTML-escaped)
     * @return {@code this} for chaining
     */
    public SvgReportBuilder addParagraph(String text) {
        closeRow();
        body.append("<p>").append(htmlEscape(text)).append("</p>\n");
        return this;
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    /**
     * Serialize the full HTML document to a string.
     *
     * @return the complete HTML page as a string
     */
    public String toHtml() {
        StringBuilder html = new StringBuilder(body.length() + 2048);
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n<head>\n");
        html.append("  <meta charset=\"UTF-8\"/>\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        html.append("  <title>").append(htmlEscape(pageTitle)).append("</title>\n");
        html.append("  <style>\n").append(buildCss()).append("  </style>\n");
        html.append("</head>\n<body>\n");
        html.append("<h1>").append(htmlEscape(pageTitle)).append("</h1>\n");
        html.append(body);
        // Close any still-open icon-row div.
        if (rowOpen) {
            html.append("</div>\n");
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /**
     * Write the full HTML report to the given file path.
     * Parent directories are created automatically when missing.
     *
     * @param outputPath target file path
     * @throws IOException when the file cannot be written
     */
    public void writeToFile(Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write(toHtml());
        }
        LOG.info("SVG report written to: " + outputPath.toAbsolutePath());
    }

    /**
     * Convenience overload accepting a file-path string.
     *
     * @param outputPath target file path as a string
     * @throws IOException when the file cannot be written
     */
    public void writeToFile(String outputPath) throws IOException {
        writeToFile(Path.of(outputPath));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void openRow() {
        body.append("<div class=\"icon-row\">\n");
        rowOpen = true;
    }

    private void closeRow() {
        if (rowOpen) {
            body.append("</div>\n");
            rowOpen = false;
        }
    }

    /** Open a row automatically if icons are added without a prior {@link #addHeader}. */
    private void ensureRowOpen() {
        if (!rowOpen) {
            openRow();
        }
    }

    private static String buildCss() {
        return """
                    body {
                        font-family: Segoe UI, Arial, sans-serif;
                        background: #f5f5f5;
                        color: #333;
                        padding: 20px;
                    }
                    h1 { color: #222; border-bottom: 2px solid #4A90E2; padding-bottom: 6px; }
                    h2 { color: #444; margin-top: 28px; margin-bottom: 8px; }
                    hr { border: none; border-top: 1px solid #ccc; margin: 24px 0; }
                    .icon-row {
                        display: flex;
                        flex-wrap: wrap;
                        gap: 16px;
                        align-items: flex-start;
                        margin-bottom: 8px;
                    }
                    .icon-cell {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        padding: 6px;
                        min-width: 60px;
                    }
                    .icon-cell svg, .icon-cell img {
                        display: block;
                    }
                    .caption {
                        margin-top: 6px;
                        font-size: 11px;
                        color: #555;
                        text-align: center;
                        max-width: 120px;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        white-space: nowrap;
                    }
                """;
    }

    /** HTML-escape a string for safe embedding in HTML text / attribute values. */
    private static String htmlEscape(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // -------------------------------------------------------------------------
    // Demo main
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        SvgReportBuilder report = new SvgReportBuilder("SVG Icon Preview");

        report.addHeader("Java Icons");
        report.addIconWithAnnotation("java-16-svgrepo-com.svg",  "#4A90E2", "#E24A4A", 'C');
        report.addIconWithAnnotation("java-16-svgrepo-com.svg",  "#27AE60", "#E24A4A", 'E', "MyEnum");
        report.addIconWithAnnotation("java-16-svgrepo-com.svg",  "#8E44AD", "#E24A4A", 'I', "Interface");
        report.addIconWithAnnotation("interface-16-svgrepo-com.svg", "#4A90E2", "#27AE60", 'I');

        report.addHeader("Struct / Record Icons");
        report.addIconWithAnnotation("struct-16-svgrepo-com.svg", "#E2924A", "#4A90E2", 'R', "Record");
        report.addIconWithAnnotation("struct-16-svgrepo-com.svg", "#E2924A", "#27AE60", 'S', "Struct");

        report.addHeader("Database / Folder Icons");
        report.addIconWithAnnotation("database-svgrepo-com.svg", "#8E44AD", "#F39C12", 'T', "MyTable");
        report.addIconWithAnnotation("folder-svgrepo-com.svg",   "#8E44AD", "#F39C12", 'P', "Product");

        try {
            report.writeToFile("icon-report.html");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write SVG report", e);
        }
    }
}