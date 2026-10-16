package dev.jbang.repl;

import jdk.jshell.JShell;
import jdk.jshell.SourceCodeAnalysis;
import java.util.Arrays;

final class ReplSyntaxHighlighter {
    private static final String RESET = "\u001b[0m";
    private static final String KEYWORD = "\u001b[35m";
    private static final String STRING = "\u001b[32m";
    private static final String COMMENT = "\u001b[90m";
    private static final String COMMAND = "\u001b[36m";
    private static final String DEPRECATED = "\u001b[9m";
    private final SourceCodeAnalysis analysis;

    public ReplSyntaxHighlighter(JShell shell) { this.analysis = shell.sourceCodeAnalysis(); }

    /** Render the entire input with ANSI color. */
    public String highlight(String source) {
        if (source == null || source.isEmpty()) return source;
        return render(source, styles(source));
    }

    /** Per-character style ids: 0 none, 1 keyword, 2 string, 3 comment, 4 deprecated, 5 command. */
    public int[] styles(String source) {
        if (source == null || source.isEmpty()) return new int[0];
        if (source.charAt(0) == '/') return commandStyles(source);
        int[] styles = new int[source.length()];
        // Minimal lexing of comments, strings, chars and text blocks. Semantic JShell spans overlay identifiers.
        for (int i = 0; i < source.length();) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
                Arrays.fill(styles, i, end, 3); i = end; continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                Arrays.fill(styles, i, end, 3); i = end; continue;
            }
            if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i);
                int end = i + 1;
                boolean triple = quote == '"' && source.startsWith("\"\"\"", i);
                if (triple) {
                    end = source.indexOf("\"\"\"", i + 3);
                    end = end < 0 ? source.length() : end + 3;
                } else {
                    boolean escaped = false;
                    while (end < source.length()) {
                        char c = source.charAt(end++);
                        if (!escaped && c == quote) break;
                        if (!escaped && c == '\\') escaped = true;
                        else escaped = false;
                    }
                }
                Arrays.fill(styles, i, end, 2); i = end; continue;
            }
            i++;
        }
        try {
            for (var h : analysis.highlights(source)) {
                int from = Math.max(0, h.start()), to = Math.min(source.length(), h.end());
                for (int i = from; i < to; i++) {
                    if (styles[i] != 0) continue;
                    if (h.attributes().contains(SourceCodeAnalysis.Attribute.KEYWORD)) styles[i] = 1;
                    if (h.attributes().contains(SourceCodeAnalysis.Attribute.DEPRECATED)) styles[i] = 4;
                }
            }
        } catch (IllegalArgumentException ex) {
            // Leave lexical styles intact on incomplete or temporarily malformed input.
        }
        return styles;
    }

    private static int[] commandStyles(String source) {
        int[] styles = new int[source.length()];
        int end = 1;
        while (end < source.length() && !Character.isWhitespace(source.charAt(end))) end++;
        Arrays.fill(styles, 0, end, 5);
        return styles;
    }

    private static String render(String source, int[] styles) {
        StringBuilder out = new StringBuilder(source.length() + 48);
        int current = 0;
        for (int i = 0; i < source.length(); i++) {
            if (styles[i] != current) {
                if (current != 0) out.append(RESET);
                current = styles[i];
                out.append(switch (current) {
                    case 1 -> KEYWORD;
                    case 2 -> STRING;
                    case 3 -> COMMENT;
                    case 4 -> DEPRECATED;
                    case 5 -> COMMAND;
                    default -> "";
                });
            }
            out.append(source.charAt(i));
        }
        if (current != 0) out.append(RESET);
        return out.toString();
    }
}
