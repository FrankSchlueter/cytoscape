package de.tk.dependencyanalyse.rapui.visgraph.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default tooltip builder. Renders a node/relationship's properties as an
 * HTML table. Values are HTML-escaped.
 *
 * Returns an empty string if there are no properties.
 */
final class TooltipBuilder {

    private TooltipBuilder() {}

    static String fromProperties(String id, Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"vgv-tooltip\">");
        sb.append("<thead><tr><th colspan=\"2\">")
          .append(escape(id))
          .append("</th></tr></thead>");
        sb.append("<tbody>");
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            sb.append("<tr><th>").append(escape(e.getKey())).append("</th>")
              .append("<td>").append(escape(formatValue(e.getValue()))).append("</td></tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private static String formatValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>(c.size());
            for (Object x : c) parts.add(formatValue(x));
            return "[" + String.join(", ", parts) + "]";
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, vv) -> copy.put(String.valueOf(k), vv));
            return copy.toString();
        }
        return v.toString();
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  out.append("&amp;");  break;
                case '<':  out.append("&lt;");   break;
                case '>':  out.append("&gt;");   break;
                case '"':  out.append("&quot;"); break;
                case '\'': out.append("&#39;");  break;
                default:   out.append(c);
            }
        }
        return out.toString();
    }
}
