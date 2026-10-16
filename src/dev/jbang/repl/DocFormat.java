package dev.jbang.repl;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure formatting for JShell documentation/signature help: ANSI colouring,
 * javadoc tag rendering, and word wrapping. No REPL or JShell state — fully testable.
 */
final class DocFormat {
    private DocFormat() {}

    static final String HDR = "\u001b[1;33m";   // bold yellow: section headers
    static final String SIG = "\u001b[1;36m";   // bold cyan: whole-signature fallback
    static final String TYPE = "\u001b[36m";    // cyan: types (return/param/field types, class names)
    static final String NAME = "\u001b[1;35m";  // bold magenta: method / field name
    static final String PARAM = "\u001b[33m";   // yellow: parameter / exception names
    static final String DIM = "\u001b[90m";     // grey: package qualifiers, hints, page footer
    static final String RST = "\u001b[0m";

    /** The "second Tab" hint, appended after the signature list. */
    static final String HINT = "<press tab again to see documentation>";

    private static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile("^(\\s+)(\\S+)( - .*)$");

    static String pageFooter(int page, int total) { return "\u2014 page " + page + "/" + total + " \u00b7 Tab for more \u2014"; }

    /** Apply ANSI colour to one documentation line based on its role. */
    static String colorLine(String l) {
        if (l.equals("Parameters:") || l.equals("Returns:") || l.equals("Thrown Exceptions:"))
            return HDR + l + RST;
        if (l.startsWith("<press tab") || l.startsWith("\u2014 page")) return DIM + l + RST;
        if (!l.isEmpty() && !l.startsWith(" ") && l.contains("(") && l.contains(")"))
            return colorSignature(l);  // signature line
        var m = NAMED.matcher(l);  // indented "name - description" (parameter / exception)
        if (m.matches()) return m.group(1) + PARAM + m.group(2) + RST + m.group(3);
        return l;
    }

    /** Colour a JShell signature: types cyan, method/field name magenta, param names yellow, package grey. */
    static String colorSignature(String sig) {
        int p = sig.indexOf('(');
        if (p >= 0) {                                   // method / constructor
            int rp = sig.lastIndexOf(')');
            String head = sig.substring(0, p);
            String params = rp > p ? sig.substring(p + 1, rp) : "";
            String tail = rp >= 0 && rp + 1 < sig.length() ? sig.substring(rp + 1) : "";
            int sp = head.lastIndexOf(' ');
            String ret = sp >= 0 ? head.substring(0, sp) : "";
            String qual = sp >= 0 ? head.substring(sp + 1) : head;
            int dot = qual.lastIndexOf('.');
            String owner = dot >= 0 ? qual.substring(0, dot + 1) : "";
            String method = dot >= 0 ? qual.substring(dot + 1) : qual;
            StringBuilder sb = new StringBuilder();
            if (!ret.isEmpty()) sb.append(TYPE).append(ret).append(RST).append(' ');
            if (!owner.isEmpty()) sb.append(DIM).append(owner).append(RST);
            sb.append(NAME).append(method).append(RST).append('(');
            List<String> cps = new ArrayList<>();
            for (String raw : splitTopLevel(params)) {
                String pm = raw.strip();
                if (pm.isEmpty()) continue;
                int s = pm.lastIndexOf(' ');
                cps.add(s < 0 ? TYPE + pm + RST : TYPE + pm.substring(0, s) + RST + " " + PARAM + pm.substring(s + 1) + RST);
            }
            sb.append(String.join(", ", cps)).append(')').append(tail);
            return sb.toString();
        }
        int colon = sig.indexOf(':');
        if (colon >= 0) {                                // field:  owner.name:type
            String name = sig.substring(0, colon), type = sig.substring(colon + 1);
            int dot = name.lastIndexOf('.');
            String owner = dot >= 0 ? name.substring(0, dot + 1) : "";
            String fname = dot >= 0 ? name.substring(dot + 1) : name;
            return DIM + owner + RST + NAME + fname + RST + ":" + TYPE + type + RST;
        }
        int dot = sig.lastIndexOf('.');                  // class / type name
        return dot >= 0 ? DIM + sig.substring(0, dot + 1) + RST + TYPE + sig.substring(dot + 1) + RST
                        : TYPE + sig + RST;
    }

    /** Split a parameter list on top-level commas (ignoring commas inside generics < >). */
    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    /** Split lines into fixed-height pages (never empty). */
    static List<List<String>> paginate(List<String> lines, int pageSize) {
        int ps = Math.max(1, pageSize);
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += ps)
            pages.add(new ArrayList<>(lines.subList(i, Math.min(i + ps, lines.size()))));
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        return pages;
    }

    /** Strip javadoc inline tags ({@code}, {@link}, ...), HTML tags and entities to plain text. */
    static String stripMarkup(String s) {
        s = s.replaceAll("\\{@(?:code|literal)\\s+([^}]*)\\}", "$1");
        var m = java.util.regex.Pattern.compile("\\{@link(?:plain)?\\s+([^}]*)\\}").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1).strip();
            int sp = body.indexOf(' ');
            String rep;
            if (sp >= 0) rep = body.substring(sp + 1).strip();          // explicit {@link target label}
            else {                                                       // simplify a bare target
                int h = body.indexOf('#');
                rep = h >= 0 ? body.substring(h + 1) : body.substring(body.lastIndexOf('.') + 1);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString().replaceAll("<[^>]+>", "");                      // HTML tags
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&nbsp;", " ").replace("&#64;", "@");
    }

    /** Turn raw javadoc (inline + block tags) into readable text like jshell's formatter. */
    static List<String> javadoc(String javadoc) {
        String text = stripMarkup(javadoc).strip();
        List<String> desc = new ArrayList<>();
        List<String> params = new ArrayList<>();
        List<String> returns = new ArrayList<>();
        List<String> throwsList = new ArrayList<>();
        List<String> target = desc;
        for (String raw : text.split("\n")) {
            String l = raw.strip();
            if (l.startsWith("@param")) {
                String[] p = l.substring(6).strip().split("\\s+", 2);
                params.add(p[0] + (p.length > 1 ? " - " + p[1] : ""));
                target = params;
            } else if (l.startsWith("@return")) {
                returns.add(l.substring(7).strip());
                target = returns;
            } else if (l.startsWith("@throws") || l.startsWith("@exception")) {
                String rest = l.replaceFirst("@(throws|exception)", "").strip();
                String[] p = rest.split("\\s+", 2);
                throwsList.add(p[0] + (p.length > 1 ? " - " + p[1] : ""));
                target = throwsList;
            } else if (l.startsWith("@")) {
                target = new ArrayList<>(); // ignore other block tags (@see, @since, ...)
            } else if (!l.isEmpty()) {
                if (target.isEmpty()) target.add(l);
                else target.set(target.size() - 1, target.get(target.size() - 1) + " " + l);
            }
        }
        List<String> out = new ArrayList<>();
        desc.forEach(d -> out.addAll(wrap(d, "", 76)));
        if (!params.isEmpty()) { out.add(""); out.add("Parameters:"); params.forEach(p -> out.addAll(wrap(p, "    ", 76))); }
        if (!returns.isEmpty()) { out.add(""); out.add("Returns:"); returns.forEach(r -> out.addAll(wrap(r, "    ", 76))); }
        if (!throwsList.isEmpty()) { out.add(""); out.add("Thrown Exceptions:"); throwsList.forEach(t -> out.addAll(wrap(t, "    ", 76))); }
        return out;
    }

    /** Word-wrap one paragraph to width, prefixing every line with indent. */
    static List<String> wrap(String s, String indent, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder(indent);
        for (String w : s.split("\\s+")) {
            if (cur.length() > indent.length() && cur.length() + 1 + w.length() > width) {
                out.add(cur.toString());
                cur = new StringBuilder(indent);
            }
            if (cur.length() > indent.length()) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > indent.length()) out.add(cur.toString());
        return out;
    }
}
