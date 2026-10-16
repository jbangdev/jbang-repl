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
    static final String SIG = "\u001b[1;36m";   // bold cyan: method signatures
    static final String DIM = "\u001b[90m";     // grey: the hint
    static final String RST = "\u001b[0m";

    /** The "second Tab" hint, appended after the signature list. */
    static final String HINT = "<press tab again to see documentation>";

    /** Apply ANSI colour to one documentation line based on its role. */
    static String colorLine(String l) {
        if (l.equals("Parameters:") || l.equals("Returns:") || l.equals("Thrown Exceptions:"))
            return HDR + l + RST;
        if (l.startsWith("<press tab")) return DIM + l + RST;
        if (!l.isEmpty() && !l.startsWith(" ") && l.contains("(") && l.contains(")"))
            return SIG + l + RST;   // signature line
        return l;
    }

    /** Turn raw javadoc (inline + block tags) into readable text like jshell's formatter. */
    static List<String> javadoc(String javadoc) {
        // Strip inline tags: {@code X} / {@link X} / {@literal X} -> X
        String text = javadoc.replaceAll("\\{@\\w+\\s+([^}]*)\\}", "$1").strip();
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
