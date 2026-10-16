///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.aesh:aesh:3.18.5
//SOURCES ../src/repl.java
//RUNTIME_OPTIONS --add-opens jdk.jshell/jdk.jshell.tool.resources=ALL-UNNAMED

package dev.jbang.repl;

import org.aesh.readline.completion.CompleteOperation;
import org.aesh.terminal.formatting.TerminalString;

import java.nio.file.*;
import java.util.*;

/** Assert-based test suite for the JBang REPL. No framework; run with: jbang replTest.java */
public class replTest {
    static int pass, fail;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // snippet evaluation & $_
        test("expression result names a temp var", () -> {
            var r = repl();
            r.repl.accept("40 + 2");
            assertContains(r.out, "$1 ==> 42");
        });
        test("$_ holds latest result", () -> {
            var r = repl();
            r.repl.accept("40 + 2");
            r.out.clear();
            r.repl.accept("$_");
            assertContains(r.out, "42");
        });
        test("$_ updates each evaluation", () -> {
            var r = repl();
            r.repl.accept("\"hi\"");
            r.repl.accept("$_ + \"!\"");
            assertContains(r.out, "hi!");
        });
        test("variable declaration prints name ==> value", () -> {
            var r = repl();
            r.repl.accept("int x = 7;");
            assertContains(r.out, "x ==> 7");
        });
        test("rejected snippet reports Error", () -> {
            var r = repl();
            r.repl.accept("int y = ;");
            assertAnyContains(r.out, "Error:");
        });
        test("multiline snippet accumulates until complete", () -> {
            var r = repl();
            r.repl.accept("int sum(int a, int b) {");
            assertEmptyOfResults(r.out);
            r.repl.accept("return a + b; }");
            r.repl.accept("sum(2, 3)");
            assertContains(r.out, "5");
        });
        test("blank line does nothing", () -> {
            var r = repl();
            r.repl.accept("   ");
            eq(0, r.out.size());
        });

        // /list /vars /methods /types /imports
        test("/vars lists declared variable", () -> {
            var r = repl();
            r.repl.accept("int a = 1;");
            r.out.clear();
            r.repl.accept("/vars");
            assertAnyContains(r.out, "int a = 1");
        });
        test("/vars filters by name", () -> {
            var r = repl();
            r.repl.accept("int a = 1;");
            r.repl.accept("int b = 2;");
            r.out.clear();
            r.repl.accept("/vars a");
            assertAnyContains(r.out, "a = 1");
            assertNoneContains(r.out, "b = 2");
        });
        test("/methods lists declared method", () -> {
            var r = repl();
            r.repl.accept("int f() { return 1; }");
            r.out.clear();
            r.repl.accept("/methods");
            assertAnyContains(r.out, "f");
        });
        test("/types lists declared class", () -> {
            var r = repl();
            r.repl.accept("class Foo {}");
            r.out.clear();
            r.repl.accept("/types");
            assertAnyContains(r.out, "Foo");
        });
        test("/imports lists imports", () -> {
            var r = repl();
            r.repl.accept("import java.util.List;");
            r.out.clear();
            r.repl.accept("/imports");
            assertAnyContains(r.out, "java.util.List");
        });
        test("/list shows snippet source", () -> {
            var r = repl();
            r.repl.accept("int z = 9;");
            r.out.clear();
            r.repl.accept("/list");
            assertAnyContains(r.out, "int z = 9;");
        });
        test("/list <name> filters", () -> {
            var r = repl();
            r.repl.accept("int z = 9;");
            r.out.clear();
            r.repl.accept("/list z");
            assertAnyContains(r.out, "int z = 9;");
        });
        test("/list unknown reports no such snippet", () -> {
            var r = repl();
            r.out.clear();
            r.repl.accept("/list nope");
            assertAnyContains(r.out, "No such snippet");
        });

        // /drop
        test("/drop removes a variable", () -> {
            var r = repl();
            r.repl.accept("int k = 5;");
            r.out.clear();
            r.repl.accept("/drop k");
            assertAnyContains(r.out, "Dropped");
            r.out.clear();
            r.repl.accept("/vars k");
            assertNoneContains(r.out, "k = 5");
        });
        test("/drop unknown reports no such snippet", () -> {
            var r = repl();
            r.repl.accept("/drop nope");
            assertAnyContains(r.out, "No such snippet");
        });

        // /reset /reload
        test("/reset clears state", () -> {
            var r = repl();
            r.repl.accept("int m = 1;");
            r.out.clear();
            r.repl.accept("/reset");
            assertAnyContains(r.out, "Resetting state");
            r.out.clear();
            r.repl.accept("/vars");
            assertNoneContains(r.out, "m = 1");
        });
        test("/reload replays snippets", () -> {
            var r = repl();
            r.repl.accept("int n = 3;");
            r.out.clear();
            r.repl.accept("/reload");
            assertAnyContains(r.out, "replaying");
            r.out.clear();
            r.repl.accept("/vars n");
            assertAnyContains(r.out, "n = 3");
        });
        test("/reload -quiet is silent about replay", () -> {
            var r = repl();
            r.repl.accept("int q = 1;");
            r.out.clear();
            r.repl.accept("/reload --quiet");
            assertNoneContains(r.out, "replaying");
        });

        // /history
        test("/history shows typed lines", () -> {
            var r = repl();
            r.repl.accept("int h = 1;");
            r.repl.accept("h + 1");
            r.out.clear();
            r.repl.accept("/history");
            assertAnyContains(r.out, "int h = 1;");
            assertAnyContains(r.out, "h + 1");
        });

        // /save
        test("/save writes snippet sources", () -> {
            var r = repl();
            r.repl.accept("int s = 8;");
            Path f = Files.createTempFile("repl-save", ".java");
            r.repl.accept("/save " + f);
            String body = Files.readString(f);
            assertStr(body, "int s = 8;");
            Files.deleteIfExists(f);
        });
        test("/save -history writes history", () -> {
            var r = repl();
            r.repl.accept("int s = 8;");
            Path f = Files.createTempFile("repl-hist", ".txt");
            r.repl.accept("/save --history " + f);
            assertStr(Files.readString(f), "int s = 8;");
            Files.deleteIfExists(f);
        });
        test("/save -all writes all snippets", () -> {
            var r = repl();
            r.repl.accept("int s = 8;");
            Path f = Files.createTempFile("repl-all", ".java");
            r.repl.accept("/save --all " + f);
            assertStr(Files.readString(f), "int s = 8;");
            Files.deleteIfExists(f);
        });
        test("/save without file is rejected by aesh", () -> {
            var r = repl();
            r.repl.accept("/save");
            assertAnyContains(r.out, "required");
        });

        // /open
        test("/open evaluates a file", () -> {
            var r = repl();
            Path f = Files.createTempFile("repl-open", ".jsh");
            Files.writeString(f, "int opened = 11;\nopened + 1\n");
            r.repl.accept("/open " + f);
            assertContains(r.out, "12");
            Files.deleteIfExists(f);
        });
        test("/open missing file reports error", () -> {
            var r = repl();
            r.repl.accept("/open /no/such/file.jsh");
            assertAnyContains(r.out, "No such file");
        });

        // rerun
        test("/! reruns last snippet", () -> {
            var r = repl();
            r.repl.accept("int c = 0;");
            r.repl.accept("c + 1");
            r.out.clear();
            r.repl.accept("/!");
            assertContains(r.out, "1");
        });
        test("/<id> reruns snippet by id", () -> {
            var r = repl();
            r.repl.accept("2 + 2");  // snippet id 1
            r.out.clear();
            r.repl.accept("/1");
            assertContains(r.out, "4");
        });
        test("/-1 reruns previous snippet", () -> {
            var r = repl();
            r.repl.accept("7 + 1");
            r.out.clear();
            r.repl.accept("/-1");
            assertContains(r.out, "8");
        });
        test("/! with nothing reports no snippet", () -> {
            var r = repl();
            r.repl.accept("/!");
            assertAnyContains(r.out, "No snippet");
        });

        // /set /env /edit
        test("/set feedback changes mode", () -> {
            var r = repl();
            r.repl.accept("/set feedback verbose");
            assertAnyContains(r.out, "verbose");
        });
        test("/set with no args shows settings", () -> {
            var r = repl();
            r.repl.accept("/set");
            assertAnyContains(r.out, "feedback");
        });
        test("/env with no extra classpath", () -> {
            var r = repl();
            r.repl.accept("/env");
            assertAnyContains(r.out, "No additional classpath");
        });
        test("/edit shows snippet source", () -> {
            var r = repl();
            r.repl.accept("int e = 4;");
            r.out.clear();
            r.repl.accept("/edit e");
            assertAnyContains(r.out, "int e = 4;");
        });

        // /help /?
        test("/help lists commands", () -> {
            var r = repl();
            r.repl.accept("/help");
            assertAnyContains(r.out, "/vars");
            assertAnyContains(r.out, "/exit");
        });
        test("/help <command> gives detail", () -> {
            var r = repl();
            r.repl.accept("/help list");
            assertAnyContains(r.out, "/list");
        });
        test("/? is an alias for help", () -> {
            var r = repl();
            r.repl.accept("/?");
            assertAnyContains(r.out, "/vars");
        });

        // /clear /exit unknown
        test("/clear empties pending buffer", () -> {
            var r = repl();
            r.repl.accept("class Half {");  // incomplete -> pending
            r.repl.accept("/clear");
            assertAnyContains(r.out, "cleared");
        });
        test("/exit sets exit flag and code", () -> {
            var r = repl();
            r.repl.accept("/exit 3");
            eq(true, r.repl.isExitRequested());
            eq(3, r.repl.exitCode());
        });
        test("Ctrl-C asks first, confirms on second within window", () -> {
            var r = repl();
            eq(false, r.repl.handleInterrupt(1000));
            assertAnyContains(r.out, "Really quit?");
            eq(true, r.repl.handleInterrupt(2500));   // within 3s
        });
        test("Ctrl-C resets after window", () -> {
            var r = repl();
            eq(false, r.repl.handleInterrupt(1000));
            eq(false, r.repl.handleInterrupt(5000));   // >3s later: asks again, no quit
        });
        test("DEFAULT startup provides common imports", () -> {
            var r = repl();
            r.repl.loadStartup("DEFAULT");
            r.repl.accept("List<Integer> xs = new ArrayList<>(); xs.size()");
            assertContains(r.out, "0");
        });
        test("startup scripts load from jshell resources", () -> {
            if (repl.jshellScript("TOOLING.jsh") == null) throw new AssertionError("TOOLING.jsh not loadable (need --add-opens)");
            assertStr(repl.jshellScript("PRINTING.jsh"), "void println(");
        });
        test("/list hides startup snippets", () -> {
            var r = repl();
            r.repl.loadStartup("DEFAULT");
            r.repl.accept("int zz = 9;");
            r.out.clear();
            r.repl.accept("/list");
            assertAnyContains(r.out, "int zz = 9;");
            assertNoneContains(r.out, "import");
        });
        test("/imports has no blank lines", () -> {
            var r = repl();
            r.repl.loadStartup("DEFAULT");
            r.out.clear();
            r.repl.accept("/imports");
            for (String l : r.out) if (l.contains("\n")) throw new AssertionError("import line has newline: <" + l + ">");
            assertAnyContains(r.out, "import");
        });
        test("PRINTING startup defines println", () -> {
            var r = repl();
            r.repl.loadStartup("PRINTING");
            r.out.clear();
            r.repl.accept("/methods");
            assertAnyContains(r.out, "println");
            assertAnyContains(r.out, "printf");
        });
        test("TOOLING startup defines tool runners", () -> {
            var r = repl();
            r.repl.loadStartup("TOOLING");
            r.out.clear();
            r.repl.accept("/methods");
            assertAnyContains(r.out, "javac");
            assertAnyContains(r.out, "jar");
        });
        test("/quit also exits", () -> {
            var r = repl();
            r.repl.accept("/quit");
            eq(true, r.repl.isExitRequested());
        });
        test("unknown command is reported", () -> {
            var r = repl();
            r.repl.accept("/bogus");
            assertAnyContains(r.out, "Unknown command");
        });
        test("block comment is not a command", () -> {
            var r = repl();
            r.repl.accept("/** ddd */");
            assertNoneContains(r.out, "Unknown command");
        });
        test("line comment is not a command", () -> {
            var r = repl();
            r.repl.accept("// hello");
            assertNoneContains(r.out, "Unknown command");
        });

        // completion
        test("signature help on open paren", () -> {
            var r = repl();
            String code = "ProcessHandle.of(";
            var lines = r.repl.documentationLines(code, code.length(), false);
            assertListContains(lines, "ProcessHandle.of(long pid)");
            // hint must be the LAST line
            eq("<press tab again to see documentation>", lines.get(lines.size() - 1));
        });
        test("javadoc is formatted, not raw tags", () -> {
            var r = repl();
            String code = "ProcessHandle.of(";
            var lines = r.repl.documentationLines(code, code.length(), true);
            assertListContains(lines, "Parameters:");
            assertListContains(lines, "pid - a native process ID");
            assertListContains(lines, "Returns:");
            assertListContains(lines, "Thrown Exceptions:");
            assertNoneContains(lines, "@param");
            assertNoneContains(lines, "{@code");
            assertNoneContains(lines, "press tab again");
        });
        test("DocFormat.colorLine colors headers, signatures, hint", () -> {
            assertStr(DocFormat.colorLine("Parameters:"), "\u001b[1;33m");
            assertStr(DocFormat.colorLine("Optional<T> X.of(long p)"), "\u001b[1;36m");
            assertStr(DocFormat.colorLine("<press tab again to see documentation>"), "\u001b[90m");
            eq("    pid - a native process ID", DocFormat.colorLine("    pid - a native process ID"));
        });
        test("DocFormat.wrap wraps to width with indent", () -> {
            var out = DocFormat.wrap("one two three four five", "  ", 12);
            if (out.stream().anyMatch(l -> l.length() > 12)) throw new AssertionError("over width: " + out);
            out.forEach(l -> { if (!l.startsWith("  ")) throw new AssertionError("no indent: " + l); });
        });
        test("DocFormat.javadoc strips inline tags and wraps", () -> {
            var out = DocFormat.javadoc("Returns an {@code Optional} value.\n@param x the {@link Thing}\n@return the result");
            assertListContains(out, "Returns an Optional value.");
            assertListContains(out, "x - the Thing");
            assertNoneContains(out, "{@");
        });
        test("java member completion", () -> {
            var r = repl();
            var co = new FakeComplete("System.out.pri", 14);
            r.repl.complete(co);
            assertListContains(co.candidates, "println(");
        });
        test("/deps add <#> with no search reports missing result", () -> {
            var r = repl();
            r.repl.accept("/deps add 1");
            assertAnyContains(r.out, "No result #1");
        });
        test("/deps search without query shows usage", () -> {
            var r = repl();
            r.repl.accept("/deps search");
            assertAnyContains(r.out, "Usage: /deps search");
        });
        test("/deps list empty", () -> {
            var r = repl();
            r.repl.accept("/deps list");
            assertAnyContains(r.out, "No added dependencies");
        });
        test("command docs: name, usage and description", () -> {
            var r = repl();
            var lines = r.repl.commandDocLines("/list", false);
            assertAnyContains(lines, "/list");
            assertAnyContains(lines, "list source you have typed");
        });
        test("command docs: group subcommands", () -> {
            var r = repl();
            var lines = r.repl.commandDocLines("/deps", false);
            assertAnyContains(lines, "/deps");
            assertAnyContains(lines, "add");
            assertAnyContains(lines, "resolve and add");
            assertAnyContains(lines, "search");
        });
        test("command docs: prefix lists multiple", () -> {
            var r = repl();
            var lines = r.repl.commandDocLines("/s", false);
            assertAnyContains(lines, "/save");
            assertAnyContains(lines, "/set");
        });
        test("/drop completes declared names", () -> {
            var r = repl();
            r.repl.accept("int widget = 1;");
            var co = new FakeComplete("/drop wid", 9);
            r.repl.complete(co);
            assertListContains(co.candidates, "widget");
        });
        test("Maven.looksLikeGav / isCoordinate", () -> {
            eq(true, Maven.looksLikeGav("g:a:1.0"));
            eq(false, Maven.looksLikeGav("just-a-word"));
            eq(true, Maven.isCoordinate("org.foo:bar:1.2.3"));
            eq(true, Maven.isCoordinate("org.foo:bar:1.2.3:jar"));
            eq(false, Maven.isCoordinate("nope"));
        });
        test("Maven.parseGavLines filters, dedups, caps", () -> {
            var in = java.util.stream.Stream.of(
                    "Searching for ...", "[gav]", "  org.a:b:1.0  ", "org.a:b:1.0", "x:y:2");
            var out = Maven.parseGavLines(in, 10);
            eq(2, out.size());
            assertListContains(out, "org.a:b:1.0");
            assertListContains(out, "x:y:2");
        });
        test("Maven.solrQuery builds prefix queries", () -> {
            eq("g:dev.jb*", Maven.solrQuery("dev.jb"));
            eq("a:jack*", Maven.solrQuery("jack"));
            eq("a:jackson-databind*", Maven.solrQuery("jackson-databind"));
            eq("g:dev.jbang* AND a:jb*", Maven.solrQuery("dev.jbang:jb"));
            eq("g:dev.jbang* AND a:*", Maven.solrQuery("dev.jbang:"));
        });
        if ("1".equals(System.getenv("REPL_NET_TESTS"))) {
            test("/deps add completes Maven coordinates (network)", () -> {
                var r = repl();
                String buf = "/deps add jackson-databind";
                var co = new FakeComplete(buf, buf.length());
                r.repl.complete(co);
                assertListContains(co.candidates, ":jackson-databind:");
                eq("/deps add ".length(), co.offset);
            });
        }

        // highlighter
        test("highlighter colors keyword and string", () -> {
            var hl = new ReplSyntaxHighlighter(jdk.jshell.JShell.builder().build());
            int[] st = hl.styles("int x = \"hi\"; // c");
            eq(1, st[0]);                       // 'i' of int -> keyword
            eq(2, st["int x = ".length()]);     // '"' -> string
            eq(3, st["int x = \"hi\"; ".length()]); // '/' of comment -> comment
        });
        test("highlighter marks slash command", () -> {
            var hl = new ReplSyntaxHighlighter(jdk.jshell.JShell.builder().build());
            int[] st = hl.styles("/list all");
            eq(5, st[0]);
            eq(5, st[4]);
            eq(0, st[5]); // space after command word
        });
        test("highlight renders ANSI", () -> {
            var hl = new ReplSyntaxHighlighter(jdk.jshell.JShell.builder().build());
            String out = hl.highlight("int x");
            assertStr(out, "\u001b[35m");
            assertStr(out, "\u001b[0m");
        });

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) {
            failures.forEach(f -> System.out.println("  FAIL " + f));
            System.exit(1);
        }
    }

    // ---- harness ----
    record Rig(repl repl, List<String> out) {}

    static Rig repl() {
        List<String> out = new ArrayList<>();
        return new Rig(new repl(out::add), out);
    }

    interface Body { void run() throws Exception; }

    static void test(String name, Body body) {
        try {
            body.run();
            pass++;
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            fail++;
            failures.add(name + " :: " + t.getMessage());
            System.out.println("FAIL " + name + " :: " + t.getMessage());
        }
    }

    static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

    static void assertContains(List<String> out, String needle) {
        if (out.stream().noneMatch(s -> s.equals(needle) || s.contains(needle)))
            throw new AssertionError("no output line equals/contains <" + needle + "> in " + out);
    }

    static void assertAnyContains(List<String> out, String needle) {
        if (out.stream().noneMatch(s -> s.contains(needle)))
            throw new AssertionError("no output line contains <" + needle + "> in " + out);
    }

    static void assertNoneContains(List<String> out, String needle) {
        if (out.stream().anyMatch(s -> s.contains(needle)))
            throw new AssertionError("unexpected output line contains <" + needle + "> in " + out);
    }

    static void assertEmptyOfResults(List<String> out) {
        if (!out.isEmpty())
            throw new AssertionError("expected no output yet but got " + out);
    }

    static void assertStr(String haystack, String needle) {
        if (!haystack.contains(needle))
            throw new AssertionError("expected <" + haystack + "> to contain <" + needle + ">");
    }

    static void assertListContains(List<String> list, String needle) {
        if (list.stream().noneMatch(s -> s.contains(needle)))
            throw new AssertionError("completion candidates " + list + " missing <" + needle + ">");
    }

    /** Minimal CompleteOperation capturing offset + candidates. */
    static final class FakeComplete implements CompleteOperation {
        final String buffer; final int cursor; int offset;
        final List<String> candidates = new ArrayList<>();
        boolean appendSeparator = true, ignoreOffset, ignoreStartsWith, ignoreNonEscapedSpace;
        char separator = ' ';
        FakeComplete(String buffer, int cursor) { this.buffer = buffer; this.cursor = cursor; }
        public String getBuffer() { return buffer; }
        public int getCursor() { return cursor; }
        public int getOffset() { return offset; }
        public void setOffset(int o) { offset = o; }
        public void setIgnoreOffset(boolean b) { ignoreOffset = b; }
        public boolean isIgnoreOffset() { return ignoreOffset; }
        public char getSeparator() { return separator; }
        public void setSeparator(char c) { separator = c; }
        public boolean isAppendSeparator() { return appendSeparator; }
        public void setAppendSeparator(boolean b) { appendSeparator = b; }
        public List<TerminalString> getCompletionCandidates() { return List.of(); }
        public void addCompletionCandidate(TerminalString c) { candidates.add(c.getCharacters()); }
        public void addCompletionCandidate(String c) { candidates.add(c); }
        public void addCompletionCandidates(List<String> c) { candidates.addAll(c); }
        public void addCompletionCandidatesTerminalString(List<TerminalString> c) { c.forEach(t -> candidates.add(t.getCharacters())); }
        public void removeEscapedSpacesFromCompletionCandidates() {}
        public List<String> getFormattedCompletionCandidates() { return candidates; }
        public List<TerminalString> getFormattedCompletionCandidatesTerminalString() { return List.of(); }
        public String getFormattedCompletion(String c) { return c; }
        public boolean isIgnoreStartsWith() { return ignoreStartsWith; }
        public void setIgnoreStartsWith(boolean b) { ignoreStartsWith = b; }
        public boolean isIgnoreNonEscapedSpace() { return ignoreNonEscapedSpace; }
        public void setIgnoreNonEscapedSpace(boolean b) { ignoreNonEscapedSpace = b; }
    }
}
