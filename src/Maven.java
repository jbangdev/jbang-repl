import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Pure helpers for Maven coordinates and search.maven.org queries. No I/O — fully testable. */
final class Maven {
    private Maven() {}

    /** A line that is exactly group:artifact:version (used to filter gavsearch output). */
    static final String GAV_LINE = "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^ ]+";
    /** Strict coordinate incl. optional classifier/type (@/:), for validating a dep to add. */
    static final String COORDINATE = "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\\-]+(?:[:@][A-Za-z0-9_.-]+)*";

    /** True if text looks like group:artifact:version (loose), used to route /deps add input. */
    static boolean looksLikeGav(String s) { return s != null && s.matches(GAV_LINE); }

    /** True if text is a valid Maven coordinate to resolve (strict, allows classifier/type). */
    static boolean isCoordinate(String s) { return s != null && s.matches(COORDINATE); }

    /** Build a prefix Solr query so partial group/artifact tokens match on search.maven.org. */
    static String solrQuery(String q) {
        q = q.strip();
        if (q.contains(":")) {
            String[] p = q.split(":", 2);
            String art = p[1].isBlank() ? "*" : p[1] + "*";
            return "g:" + p[0] + "* AND a:" + art;
        }
        if (q.contains(".")) return "g:" + q + "*"; // dotted => groupId prefix
        // Bare word => artifact prefix; field-qualified so hyphens/dots don't break the wildcard.
        return "a:" + q + "*";
    }

    /** Keep only bare GAV lines from raw search output, de-duped and capped. */
    static List<String> parseGavLines(Stream<String> lines, int limit) {
        return lines.map(String::strip)
                .filter(l -> l.matches(GAV_LINE))
                .distinct().limit(limit)
                .collect(Collectors.toList());
    }
}
