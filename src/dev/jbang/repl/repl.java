///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.aesh:aesh:3.18.5
//SOURCES DocFormat.java
//SOURCES Maven.java
//SOURCES ReplSyntaxHighlighter.java
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED --add-opens jdk.jshell/jdk.jshell.tool.resources=ALL-UNNAMED

package dev.jbang.repl;

import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.Diag;
import jdk.jshell.SourceCodeAnalysis;
import org.aesh.terminal.tty.TerminalConnection;
import org.aesh.readline.completion.Completion;
import org.aesh.readline.completion.CompleteOperation;
import org.aesh.readline.cursor.Line;
import org.aesh.readline.prompt.Prompt;
import org.aesh.terminal.formatting.Color;
import org.aesh.console.ReadlineConsole;
import org.aesh.command.settings.SettingsBuilder;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.CommandException;
import org.aesh.command.CommandRuntime;
import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.CommandNotFoundException;
import org.aesh.command.option.Option;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Arguments;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Interactive Java snippets via JShell, terminal input via aesh. */
public class repl implements AutoCloseable {
    private JShell jshell = JShell.builder().out(System.out).err(System.err).build();
    private ReplSyntaxHighlighter syntaxHighlighter = new ReplSyntaxHighlighter(jshell);
    private ReadlineConsole console;
    private final TerminalConnection connection;
    private final java.util.function.Consumer<String> sink;
    private final List<String> dependencies = new ArrayList<>();
    private final List<String> resolvedJars = new ArrayList<>();
    private final List<String> acceptedSnippets = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    private final StringBuilder pending = new StringBuilder();
    private boolean exitRequested;
    private int exitCode;
    private long lastInterrupt;   // Ctrl-C double-tap confirm
    private boolean interruptArmed;
    private String feedbackMode = "normal";
    private CommandRuntime<?> runtime;

    public repl() throws IOException {
        connection = new TerminalConnection();
        sink = s -> connection.write(s + "\r\n");
        buildCommandRuntime();
        introspectCommands();
        try {
            // Drive the loop with aesh's ReadlineConsole (3.18.5 exposes cursorListener +
            // completionHandler seams). The console owns the /command registry and dispatches
            // them natively; Java/rerun lines fall through commandNotFoundHandler to accept().
            var settings = SettingsBuilder.<CommandInvocation>builder()
                    .connection(connection)
                    .aeshContext(aeshContext)
                    .commandRegistry(createRegistry())
                    .enableOperatorParser(false)   // don't parse Java |, ;, >, && as shell operators
                    .enableAlias(false)
                    .persistHistory(false)
                    .redrawPromptOnInterrupt(true)
                    .promptSupplier(() -> new Prompt(pending.length() == 0 ? "jrepl> " : "...> "))
                    .commandNotFoundHandler((line, output) -> {
                        try { accept(line); } catch (Exception e) { show("Error: " + e.getMessage()); }
                    })
                    // Natively-dispatched /commands bypass accept(); record them in history here.
                    .commandExecutionListener((cmdLine, res, ms) -> {
                        if (cmdLine != null && !cmdLine.isBlank()) history.add(cmdLine);
                    })
                    .setInterruptHandler(v -> { if (handleInterrupt(System.currentTimeMillis())) doExit(null); })
                    .cursorListener(this::colorize)
                    .completionHandler(new DocHandler())
                    .tailTipSuggestions(true)
                    .build();
            console = new ReadlineConsole(settings);
            console.addCompletion(new Completion<CompleteOperation>() {
                public void complete(CompleteOperation co) { repl.this.complete(co); }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to build console", e);
        }
    }

    /** A fresh registry of all /commands (new @Command instances bound to this repl). */
    private org.aesh.command.registry.CommandRegistry<CommandInvocation> createRegistry() throws Exception {
        return AeshCommandRegistryBuilder.<CommandInvocation>builder()
                .command(new ExitCmd()).command(new HelpCmd()).command(new ClearCmd())
                .command(new ListCmd()).command(new VarsCmd()).command(new MethodsCmd())
                .command(new TypesCmd()).command(new ImportsCmd()).command(new DropCmd())
                .command(new ResetCmd()).command(new ReloadCmd()).command(new HistoryCmd())
                .command(new SaveCmd()).command(new OpenCmd()).command(new SetCmd())
                .command(new EnvCmd()).command(new EditCmd()).command(new DepsCmd())
                .create();
    }

    /** Headless command dispatch for tests; the console uses an equivalent registry internally. */
    private void buildCommandRuntime() {
        try {
            runtime = AeshCommandRuntimeBuilder.<CommandInvocation>builder().commandRegistry(createRegistry()).build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build command runtime", e);
        }
    }

    /** Two-stage Ctrl-C: first asks, a second within 3s confirms. Returns true to quit. */
    boolean handleInterrupt(long now) {
        if (interruptArmed && now - lastInterrupt < 3000) { interruptArmed = false; return true; }
        interruptArmed = true;
        lastInterrupt = now;
        show("");
        show("Really quit? Press Ctrl-C again to confirm, or keep typing to cancel.");
        return false;
    }

    static final Set<String> STARTUP_PRESETS = Set.of("DEFAULT", "PRINTING", "TOOLING", "JAVASE");

    /** Read one of jshell's predefined startup scripts from the jdk.jshell module resources. */
    static String jshellScript(String fileName) {
        try {
            Class<?> c = Class.forName("jdk.jshell.tool.JavaShellToolBuilder");
            try (var in = c.getResourceAsStream("resources/" + fileName)) {
                if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Resolve a startup spec (preset name or file path) and evaluate it silently. */
    void loadStartup(String spec) throws IOException {
        String content = switch (spec) {
            // Load the canonical scripts straight from jshell instead of hardcoding them.
            case "DEFAULT" -> jshellScript(Runtime.version().feature() >= 25 ? "DEFAULT.jsh" : "DEFAULT_NO_MODULE_IMPORTS.jsh");
            case "PRINTING" -> jshellScript("PRINTING.jsh");
            case "TOOLING" -> jshellScript("TOOLING.jsh");
            case "JAVASE" -> "import module java.se;\n"; // generated by jshell, not a resource file
            default -> Files.readString(Path.of(spec), StandardCharsets.UTF_8);
        };
        if (content == null) { show("Could not load startup '" + spec + "' from jshell (needs --add-opens)."); return; }
        runStartupSilently(content);
    }

    /** Evaluate a block of snippets without echoing results; remember them so /list hides them (like jshell). */
    void runStartupSilently(String content) {
        var sca = jshell.sourceCodeAnalysis();
        String src = content;
        while (src != null && !src.isBlank()) {
            var info = sca.analyzeCompletion(src);
            String snippet = info.source();
            if (snippet == null || snippet.isBlank()) break;
            for (SnippetEvent e : jshell.eval(snippet))
                if (e.snippet() != null) hiddenSnippetIds.add(e.snippet().id());
            src = info.remaining();
        }
    }

    /** Headless constructor for tests: no terminal, output captured via sink. */
    repl(java.util.function.Consumer<String> sink) {
        connection = null;
        console = null;
        this.sink = sink;
        buildCommandRuntime();
        introspectCommands();
    }

    public static void main(String[] args) throws Exception {
        try (var repl = new repl()) {
            List<String> startupSpecs = new ArrayList<>();
            List<String> loadFiles = new ArrayList<>();
            boolean noStartup = false;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--help", "-h", "-?" -> {
                        System.out.println("""
                            Usage: jbang repl.java [options] [load-files...]
                              --startup <file|DEFAULT|PRINTING|TOOLING|JAVASE>  startup script (repeatable)
                              --no-startup                                      don't run the default startup
                              --help, -h, -?                                    this help
                            Predefined startups: DEFAULT (common imports), PRINTING (print/println/printf),
                            TOOLING (javac/jar/javap/...), JAVASE (all Java SE imports).
                            Interactive Java shell: /help for commands.""");
                        return;
                    }
                    case "--no-startup" -> noStartup = true;
                    case "--startup" -> {
                        if (++i >= args.length) { System.err.println("--startup requires an argument"); System.exit(2); }
                        startupSpecs.add(args[i]);
                    }
                    default -> {
                        if (a.startsWith("-")) { System.err.println("Unknown argument: " + a); System.exit(2); }
                        loadFiles.add(a);
                    }
                }
            }
            if (startupSpecs.isEmpty() && !noStartup) startupSpecs.add("DEFAULT");
            for (String spec : startupSpecs) {
                try { repl.loadStartup(spec); }
                catch (IOException e) { System.err.println("Cannot read startup '" + spec + "': " + e.getMessage()); System.exit(2); }
            }
            for (String f : loadFiles) repl.runStartupSilently(Files.readString(Path.of(f), StandardCharsets.UTF_8));
            // External kill (SIGTERM) can't prompt; at least restore the terminal and
            // kill JShell's RemoteExecutionControl agent JVM on exit.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (repl.connection != null) repl.connection.close(); } catch (Exception ignored) {}
                try { repl.jshell.close(); } catch (Exception ignored) {}
            }));
            System.out.println("JBang REPL (aesh + JShell, Java " + Runtime.version().feature() + ")");
            System.out.println("PID " + ProcessHandle.current().pid());
            System.out.println("Enter Java or /help. Ctrl-D to exit.");
            repl.console.start();   // blocks until the console stops (e.g. Ctrl-D)
            System.exit(repl.exitCode); // force exit; JShell's agent thread can otherwise linger
        }
    }

    /** Bridge JShell completion into readline. */
    private static final List<String> COMMANDS = List.of(
            "exit", "quit", "help", "clear", "list", "vars", "methods", "types", "imports",
            "drop", "reset", "reload", "history", "save", "open", "set", "env", "edit", "deps");

    private static final String DEPS_ADD = "/deps add ";
    private final List<String> lastSearch = new ArrayList<>();

    private void depsList() {
        if (dependencies.isEmpty()) show("No added dependencies.");
        dependencies.forEach(this::show);
    }

    private void depsAdd(String arg) throws Exception {
        arg = arg.strip();
        if (arg.matches("\\d+")) {
            int n = Integer.parseInt(arg);
            if (n < 1 || n > lastSearch.size()) { show("No result #" + n + ". Run /deps search first."); return; }
            addDependency(lastSearch.get(n - 1));
        } else if (Maven.looksLikeGav(arg)) {
            addDependency(arg);
        } else {
            depsSearch(arg); // not a full GAV => search and offer a numbered list
        }
    }

    /** Search Maven Central and present a numbered list for /deps add <#>. */
    private void depsSearch(String query) {
        lastSearch.clear();
        lastSearch.addAll(gavSearch(query));
        if (lastSearch.isEmpty()) { show("No matches for " + query); return; }
        for (int i = 0; i < lastSearch.size(); i++) show("  " + (i + 1) + ") " + lastSearch.get(i));
        show("Add one with: /deps add <#>");
    }

    // Snippet-name arguments our completion fills in (aesh has no @Argument completer for these).
    private static final java.util.regex.Pattern SNIPPET_ARG =
            java.util.regex.Pattern.compile("/(drop|edit|list|vars|methods|types) [^ ]*");

    void complete(CompleteOperation co) {
        String prefix = pending.toString();
        // /command names, options and group sub-commands are completed natively by aesh's registry.
        // We only add what aesh can't: Maven coordinates, snippet names, and Java/JShell completion.
        if (prefix.isEmpty() && co.getBuffer().startsWith(DEPS_ADD)) {
            String query = co.getBuffer().substring(DEPS_ADD.length(), co.getCursor()).strip();
            if (query.isEmpty()) return;
            co.setOffset(DEPS_ADD.length());
            co.setAppendSeparator(false);
            gavSearch(query).forEach(co::addCompletionCandidate);
            return;
        }
        // Snippet-name completion for /drop, /edit, /list, /vars, /methods, /types.
        if (prefix.isEmpty() && SNIPPET_ARG.matcher(co.getBuffer()).matches()) {
            int sp = co.getBuffer().indexOf(' ');
            String word = co.getBuffer().substring(sp + 1, co.getCursor());
            co.setOffset(sp + 1);
            co.setAppendSeparator(false);
            jshell.snippets()
                    .filter(s -> s instanceof jdk.jshell.DeclarationSnippet)
                    .map(s -> ((jdk.jshell.DeclarationSnippet) s).name())
                    .filter(n -> n.startsWith(word)).distinct()
                    .forEach(co::addCompletionCandidate);
            return;
        }
        if (prefix.isEmpty() && co.getBuffer().startsWith("/")) return; // aesh handles command completion
        String code = prefix + co.getBuffer();
        int cursor = prefix.length() + co.getCursor();
        int[] anchor = new int[1];
        var suggestions = jshell.sourceCodeAnalysis().completionSuggestions(code, cursor, anchor);
        if (suggestions.isEmpty()) return; // documentation is handled by DocHandler.renderDoc
        co.setOffset(anchor[0] - prefix.length());
        co.setAppendSeparator(false); // no trailing space; allow typing '.' or completing further
        co.setSeparator('\0');
        suggestions.stream().map(SourceCodeAnalysis.Suggestion::continuation).distinct()
                .forEach(co::addCompletionCandidate);
    }

    private String lastDocKey;       // code+cursor of the last documentation request
    private int docStage;            // 0 = signatures, 1..N = javadoc page N (Tab advances, then wraps)

    // Shared context so DocHandler and the console build the same AeshCompleteOperation type.
    private final org.aesh.console.AeshContext aeshContext = new org.aesh.console.DefaultAeshContext();

    /** Custom handler: render jshell-style signature/doc help ourselves (ordered, formatted), else default.
     *  Extends AeshCompletionHandler so it creates AeshCompleteOperation (required by ReadlineConsole's
     *  internal AeshCompletion). */
    final class DocHandler extends org.aesh.command.impl.completer.AeshCompletionHandler {
        DocHandler() { super(aeshContext); }
        @Override public void complete(org.aesh.readline.InputProcessor ip) {
            if (renderDoc(ip.buffer())) return;
            if (renderCommandDocs(ip.buffer())) return;
            super.complete(ip);
        }
    }

    // ---- /command documentation (jshell-style second-Tab help, nicely rendered) ----
    static final class CmdDoc { String name = "", usage = "", desc = ""; final List<CmdDoc> subs = new ArrayList<>(); }
    private List<CmdDoc> commandDocs = new ArrayList<>();
    private String lastCmdDocKey;

    /** Introspect the command registry once so we can render per-command help. */
    private void introspectCommands() {
        try {
            var reg = createRegistry();
            List<CmdDoc> docs = new ArrayList<>();
            for (String name : new java.util.TreeSet<>(reg.getAllCommandNames()))
                docs.add(toCmdDoc(reg.getCommand(name, name).getParser()));
            commandDocs = docs;
        } catch (Exception ignored) {}
    }

    private CmdDoc toCmdDoc(org.aesh.command.impl.parser.CommandLineParser<?> parser) {
        var pc = parser.getProcessedCommand();
        CmdDoc d = new CmdDoc();
        d.name = pc.name();
        d.desc = pc.description() == null ? "" : pc.description();
        d.usage = usageOf(pc);
        if (parser.isGroupCommand())
            for (var child : parser.getAllChildParsers()) d.subs.add(toCmdDoc(child));
        return d;
    }

    private static String usageOf(org.aesh.command.impl.internal.ProcessedCommand<?, ?> pc) {
        StringBuilder sb = new StringBuilder();
        for (var o : pc.getOptions()) sb.append("[--").append(o.name()).append("] ");
        var arg = pc.getArgument() != null ? pc.getArgument() : pc.getArguments();
        if (arg != null) {
            String d = arg.description() == null ? "" : arg.description().strip().replaceAll("^<+", "").replaceAll(">+$", "");
            String ph = "<" + (d.isBlank() ? "arg" : d) + ">";
            sb.append(arg.isRequired() ? ph : "[" + ph + "]");
        }
        return sb.toString().strip();
    }

    private static final String C_CMD = "\u001b[1;36m", C_ARG = "\u001b[33m", C_DESC = "\u001b[90m", C_RST = "\u001b[0m";

    /** Rendered help lines for every command whose name starts with word. color=false for tests. */
    List<String> commandDocLines(String word, boolean color) {
        List<String> out = new ArrayList<>();
        for (CmdDoc d : commandDocs) {
            if (!d.name.startsWith(word)) continue;
            out.add(cmd(d.name, color) + (d.usage.isEmpty() ? "" : " " + arg(d.usage, color)));
            if (!d.desc.isBlank()) out.add("      " + dim(d.desc, color));
            if (!d.subs.isEmpty()) {
                int w = d.subs.stream().mapToInt(s -> (s.name + (s.usage.isEmpty() ? "" : " " + s.usage)).length()).max().orElse(0);
                for (CmdDoc s : d.subs) {
                    String plain = s.name + (s.usage.isEmpty() ? "" : " " + s.usage);
                    String left = cmd(s.name, color) + (s.usage.isEmpty() ? "" : " " + arg(s.usage, color));
                    out.add("      " + left + " ".repeat(w - plain.length() + 2) + dim(s.desc, color));
                }
            }
        }
        return out;
    }
    private static String cmd(String s, boolean c) { return c ? C_CMD + s + C_RST : s; }
    private static String arg(String s, boolean c) { return c ? C_ARG + s + C_RST : s; }
    private static String dim(String s, boolean c) { return c ? C_DESC + s + C_RST : s; }

    /** Second Tab on a /command prefix: render per-command docs (first Tab lets aesh list names). */
    boolean renderCommandDocs(org.aesh.readline.ConsoleBuffer cb) {
        if (!pending.isEmpty()) return false;
        String line = cb.buffer().asString();
        int cur = cb.buffer().multiCursor();
        String word = line.substring(0, cur).strip(); // tolerate the trailing space aesh adds after a unique command
        if (!word.startsWith("/") || word.contains(" ")) return false; // only a bare command name
        if (commandDocLines(word, false).isEmpty()) return false;
        String key = "cmd:" + word;
        if (!key.equals(lastCmdDocKey)) { lastCmdDocKey = key; return false; } // first Tab: let aesh list names
        StringBuilder sb = new StringBuilder("\r\n");
        for (String l : commandDocLines(word, true)) sb.append(l).append("\r\n");
        cb.writeOut(sb.toString());
        cb.drawLineForceDisplay();
        return true;
    }

    /**
     * jshell-style doc disclosure. At an argument position (`(`/`,`) the first Tab shows the
     * signature; otherwise the first Tab lets aesh complete and a *repeated* Tab on the unchanged
     * buffer shows the signature of the resolved class/field/method; each further Tab pages the
     * javadoc, then wraps back. Returns true if it rendered (and aesh completion is skipped).
     */
    boolean renderDoc(org.aesh.readline.ConsoleBuffer cb) {
        String prefix = pending.toString();
        String line = cb.buffer().asString();
        int cur = cb.buffer().multiCursor();
        if (prefix.isEmpty() && line.stripLeading().startsWith("/")) return false; // slash command
        String code = prefix + line;
        int cursor = prefix.length() + cur;
        int c = cursor - 1;
        while (c >= 0 && code.charAt(c) == ' ') c--;
        boolean argPos = c >= 0 && (code.charAt(c) == '(' || code.charAt(c) == ',');
        var docs = jshell.sourceCodeAnalysis().documentation(code, cursor, true);
        if (docs.isEmpty()) { lastDocKey = null; return false; }
        String key = cursor + ":" + code;
        if (!key.equals(lastDocKey)) {
            lastDocKey = key;
            if (argPos) { docStage = 0; renderDocStage(cb, docs); return true; } // show signature right away
            docStage = -1; // first Tab elsewhere: let aesh complete; the next identical Tab shows docs
            return false;
        }
        docStage++;                 // repeated Tab on the same buffer: advance a stage
        renderDocStage(cb, docs);
        return true;
    }

    /** Render documentation stage docStage: 0 = signature(s)+hint, 1..N = javadoc pages (wraps at the end). */
    private void renderDocStage(org.aesh.readline.ConsoleBuffer cb, List<SourceCodeAnalysis.Documentation> docs) {
        List<String> out = new ArrayList<>();   // already-coloured lines
        if (docStage <= 0) {
            docStage = 0;
            for (var d : docs) out.add(DocFormat.colorSignature(d.signature()));
            out.add(DocFormat.colorLine(DocFormat.HINT));
        } else {
            List<String> full = new ArrayList<>();
            for (var d : docs) {
                full.add(DocFormat.colorSignature(d.signature()));
                if (d.javadoc() != null && !d.javadoc().isBlank()) {
                    full.add("");
                    for (String jl : DocFormat.javadoc(d.javadoc())) full.add(DocFormat.colorLine(jl));
                }
                full.add("");
            }
            int height = 24;
            try { height = Math.max(6, cb.size().getHeight() - 3); } catch (Exception ignored) {}
            var pages = DocFormat.paginate(full, height);
            int page = docStage - 1;
            if (page >= pages.size()) { // past the last page -> wrap back to the signature
                docStage = 0;
                for (var d : docs) out.add(DocFormat.colorSignature(d.signature()));
                out.add(DocFormat.colorLine(DocFormat.HINT));
            } else {
                out.addAll(pages.get(page));
                if (pages.size() > 1) out.add(DocFormat.colorLine(DocFormat.pageFooter(page + 1, pages.size())));
            }
        }
        StringBuilder sb = new StringBuilder("\r\n");
        for (String l : out) sb.append(l).append("\r\n");
        cb.writeOut(sb.toString());
        cb.drawLineForceDisplay();
    }

    /** Signature(s) and, when javadoc=true, a jshell-style formatted javadoc body. */
    List<String> documentationLines(String code, int cursor, boolean javadoc) {
        var docs = jshell.sourceCodeAnalysis().documentation(code, cursor, javadoc);
        List<String> lines = new ArrayList<>();
        for (var d : docs) {
            lines.add(d.signature());
            if (javadoc && d.javadoc() != null && !d.javadoc().isBlank()) {
                lines.add("");
                lines.addAll(DocFormat.javadoc(d.javadoc()));
            }
        }
        if (!lines.isEmpty() && !javadoc) lines.add(DocFormat.HINT);
        return lines;
    }

    /** Live-colorize the editable buffer on cursor movement (aesh 3.14 only fires this on moveCursor). */
    private void colorize(Line line) {
        String src = line.toString();
        if (src.isEmpty()) return;
        int[] styles = syntaxHighlighter.styles(src);
        var tx = line.newCursorTransactionBuilder();
        for (int i = 0; i < src.length() && i < styles.length; i++) {
            if (src.charAt(i) == '\n') continue;
            Color c = switch (styles[i]) {
                case 1 -> Color.MAGENTA;   // keyword
                case 2 -> Color.GREEN;     // string
                case 3 -> Color.BLACK;     // comment (bright => grey)
                case 4 -> Color.RED;       // deprecated
                case 5 -> Color.CYAN;      // command
                default -> null;
            };
            if (c != null) tx.colorize(i, c, Color.DEFAULT, styles[i] == 3);
        }
        tx.build().run();
    }

    void accept(String line) throws Exception {
        String stripped = line.strip();
        if (!stripped.isBlank()) history.add(line);
        // A '/' line is a command when at a fresh prompt, or mid-buffer only if it names a known command.
        // '//' and '/*' are Java comments, not commands.
        boolean comment = stripped.startsWith("//") || stripped.startsWith("/*");
        if (stripped.startsWith("/") && !comment && (pending.isEmpty() || looksLikeCommand(stripped.substring(1)))) {
            command(stripped.substring(1));
            return;
        }
        if (pending.isEmpty() && stripped.isBlank()) return;
        pending.append(line).append('\n');
        drain();
    }

    private boolean looksLikeCommand(String text) {
        String t = text.strip();
        if (t.equals("!") || t.startsWith("?") || t.matches("-?\\d+(-\\d+)?")) return true;
        String first = t.split("\\s+", 2)[0];
        return COMMANDS.contains(first);
    }

    /** Consume complete snippets, keeping any unfinished trailing statement. */
    private void drain() {
        while (!pending.isEmpty()) {
            String source = pending.toString();
            SourceCodeAnalysis.CompletionInfo info = jshell.sourceCodeAnalysis().analyzeCompletion(source);
            if (!info.completeness().isComplete()) return;
            String code = info.source();
            if (code.isBlank()) { pending.setLength(0); return; }
            List<SnippetEvent> events = jshell.eval(code);
            for (SnippetEvent event : events) {
                if (event.exception() != null) {
                    show("Exception: " + event.exception());
                }
                if (event.status() == Snippet.Status.REJECTED) {
                    jshell.diagnostics(event.snippet()).forEach(d -> show("Error: " + d.getMessage(Locale.getDefault())));
                } else if (event.value() != null && event.causeSnippet() == null) {
                    show((event.snippet() instanceof jdk.jshell.DeclarationSnippet d) ? d.name() + " ==> " + event.value() : event.value());
                    // Alias $_ to the latest result (like Python's _); hide the alias snippet from /list.
                    if (event.snippet() instanceof jdk.jshell.VarSnippet vs && !vs.name().equals("$_")) {
                        for (SnippetEvent ae : jshell.eval("var $_ = " + vs.name() + ";"))
                            if (ae.snippet() != null) hiddenSnippetIds.add(ae.snippet().id());
                    }
                }
            }
            // Retain only accepted, active snippets for /save.
            if (events.stream().anyMatch(e -> e.status().isActive() && e.causeSnippet() == null)) acceptedSnippets.add(code);
            String remaining = info.remaining();
            pending.setLength(0);
            pending.append(remaining);
            if (remaining.isBlank()) { pending.setLength(0); return; }
        }
    }

    void command(String text) throws Exception {
        String t = text.strip();
        // Rerun forms and '?' aren't valid aesh command names; handle them first.
        if (t.equals("!")) { rerunLast(); return; }
        if (t.matches("-\\d+")) { rerunPrevious(Integer.parseInt(t.substring(1))); return; }
        if (t.matches("\\d+(-\\d+)?")) { rerunIds(t); return; }
        if (t.startsWith("?")) { helpTopic(t.substring(1).strip()); return; }
        try {
            runtime.executeCommand("/" + t);   // command names are slash-prefixed
        } catch (CommandNotFoundException e) {
            show("Unknown command /" + t.split("\\s+", 2)[0] + ". Try /help");
        } catch (Exception e) {
            show(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ---- aesh @Command wrappers (parsing/validation/help) delegating to the helpers below ----

    @CommandDefinition(name = "/exit", aliases = {"/quit"}, description = "exit the REPL")
    public final class ExitCmd implements Command<CommandInvocation> {
        @Argument(description = "exit code")
        Integer code;
        public CommandResult execute(CommandInvocation ci) { doExit(code); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/help", description = "get information about commands")
    public final class HelpCmd implements Command<CommandInvocation> {
        @Argument(description = "command")
        String topic;
        public CommandResult execute(CommandInvocation ci) { helpTopic(topic == null ? "" : topic); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/clear", description = "clear the pending input buffer")
    public final class ClearCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { pending.setLength(0); show("Input buffer cleared."); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/list", description = "list source you have typed")
    public final class ListCmd implements Command<CommandInvocation> {
        @Option(shortName = 'a', name = "all", hasValue = false, description = "include startup/failed/overwritten snippets")
        boolean all;
        @Argument(description = "snippet name or id")
        String name;
        public CommandResult execute(CommandInvocation ci) { listSnippets(all, name); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/vars", description = "list declared variables")
    public final class VarsCmd implements Command<CommandInvocation> {
        @Argument(description = "variable name")
        String name;
        public CommandResult execute(CommandInvocation ci) { listVars(name); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/methods", description = "list declared methods")
    public final class MethodsCmd implements Command<CommandInvocation> {
        @Argument(description = "method name")
        String name;
        public CommandResult execute(CommandInvocation ci) { listMethods(name); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/types", description = "list declared types")
    public final class TypesCmd implements Command<CommandInvocation> {
        @Argument(description = "type name")
        String name;
        public CommandResult execute(CommandInvocation ci) { listTypes(name); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/imports", description = "list imported items")
    public final class ImportsCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { jshell.imports().forEach(i -> show("  " + i.source().strip())); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/drop", description = "delete a snippet")
    public final class DropCmd implements Command<CommandInvocation> {
        @Argument(description = "snippet name or id", required = true)
        String target;
        public CommandResult execute(CommandInvocation ci) { dropSnippets(target); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/reset", description = "reset the REPL state")
    public final class ResetCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { doReset(); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/reload", description = "reset and replay history")
    public final class ReloadCmd implements Command<CommandInvocation> {
        @Option(shortName = 'q', name = "quiet", hasValue = false, description = "don't report replay")
        boolean quiet;
        public CommandResult execute(CommandInvocation ci) { doReload(quiet); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/history", description = "history of what you have typed")
    public final class HistoryCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { history.forEach(repl.this::show); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/save", description = "save source to a file")
    public final class SaveCmd implements Command<CommandInvocation> {
        @Option(name = "all", hasValue = false, description = "save all snippets incl. failed/overwritten")
        boolean all;
        @Option(name = "history", hasValue = false, description = "save the input history")
        boolean histMode;
        @Option(name = "start", hasValue = false, description = "save the startup definitions")
        boolean start;
        @Argument(description = "file", required = true)
        String file;
        public CommandResult execute(CommandInvocation ci) {
            if (file == null) { show("Usage: /save [--all|--history|--start] <file>"); return CommandResult.SUCCESS; }
            try { doSave(all, histMode, start, file); } catch (IOException e) { show("Error: " + e.getMessage()); return CommandResult.FAILURE; }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "/open", description = "open a file as source input")
    public final class OpenCmd implements Command<CommandInvocation> {
        @Argument(description = "file", required = true)
        String file;
        public CommandResult execute(CommandInvocation ci) {
            try { doOpen(file); } catch (Exception e) { show("Error: " + e.getMessage()); return CommandResult.FAILURE; }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "/set", description = "set configuration")
    public final class SetCmd implements org.aesh.command.GroupCommand<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { show("Settings: feedback " + feedbackMode); return CommandResult.SUCCESS; }
        public List<Command<CommandInvocation>> getCommands() {
            return List.of(new SetFeedbackCmd());
        }
    }

    @CommandDefinition(name = "feedback", description = "show or set the feedback mode")
    public final class SetFeedbackCmd implements Command<CommandInvocation> {
        @Argument(description = "mode")
        String mode;
        public CommandResult execute(CommandInvocation ci) {
            if (mode != null) feedbackMode = mode.strip();
            show("Feedback mode: " + feedbackMode);
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "/env", description = "view or change the eval context")
    public final class EnvCmd implements Command<CommandInvocation> {
        @Option(name = "class-path", description = "add to the classpath")
        String classPath;
        public CommandResult execute(CommandInvocation ci) { doEnv(classPath); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/edit", description = "show snippet source")
    public final class EditCmd implements Command<CommandInvocation> {
        @Argument(description = "snippet name or id")
        String target;
        public CommandResult execute(CommandInvocation ci) { doEdit(target); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "/deps", description = "manage JBang dependencies")
    public final class DepsCmd implements org.aesh.command.GroupCommand<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) {
            show("Usage: /deps add <GAV|query|#>, /deps search <query>, /deps list");
            return CommandResult.SUCCESS;
        }
        public List<Command<CommandInvocation>> getCommands() {
            return List.of(new DepsAddCmd(), new DepsSearchCmd(), new DepsListCmd());
        }
    }

    @CommandDefinition(name = "add", description = "resolve and add a dependency (GAV, search query, or result #)")
    public final class DepsAddCmd implements Command<CommandInvocation> {
        @Arguments(description = "<GAV|query|#>")
        List<String> value;
        public CommandResult execute(CommandInvocation ci) {
            if (value == null || value.isEmpty()) { show("Usage: /deps add <GAV|query|#>"); return CommandResult.SUCCESS; }
            try { depsAdd(String.join(" ", value)); }
            catch (Exception e) { show("Error: " + e.getMessage()); return CommandResult.FAILURE; }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "search", description = "search Maven Central")
    public final class DepsSearchCmd implements Command<CommandInvocation> {
        @Arguments(description = "<query>")
        List<String> value;
        public CommandResult execute(CommandInvocation ci) {
            if (value == null || value.isEmpty()) { show("Usage: /deps search <query>"); return CommandResult.SUCCESS; }
            try { depsSearch(String.join(" ", value)); }
            catch (Exception e) { show("Error: " + e.getMessage()); return CommandResult.FAILURE; }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "list", description = "list added dependencies")
    public final class DepsListCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { depsList(); return CommandResult.SUCCESS; }
    }

    // ---- jshell command implementations ----

    private void runSource(String src) {
        if (src == null || src.isBlank()) return;
        pending.append(src);
        if (!src.endsWith("\n")) pending.append('\n');
        drain();
    }

    // Snippets hidden from /list etc.: startup scripts and the internal $_ alias (matches jshell).
    private final Set<String> hiddenSnippetIds = new HashSet<>();

    private List<Snippet> allSnippets() { return jshell.snippets().collect(Collectors.toList()); }

    /** Active, user-entered snippets (startup + internal snippets are hidden from /list, like jshell). */
    private List<Snippet> activeSnippets() {
        return jshell.snippets()
                .filter(s -> jshell.status(s).isActive() && !hiddenSnippetIds.contains(s.id()))
                .collect(Collectors.toList());
    }

    /** Resolve a snippet selector (id or declared name) to matching snippets. */
    private List<Snippet> select(String key) {
        return allSnippets().stream().filter(s -> s.id().equals(key)
                || (s instanceof jdk.jshell.DeclarationSnippet d && d.name().equals(key)))
                .collect(Collectors.toList());
    }

    private void listSnippets(boolean all, String name) {
        List<Snippet> snippets = all ? allSnippets() : activeSnippets();
        if (!all && name != null) {
            snippets = select(name);
            if (snippets.isEmpty()) { show("No such snippet: " + name); return; }
        }
        for (Snippet s : snippets) show("  " + s.id() + " : " + s.source().strip());
    }

    private void listVars(String name) {
        jshell.variables()
                .filter(v -> name == null || v.name().equals(name))
                .forEach(v -> show("  " + v.typeName() + " " + v.name() + " = " + jshell.varValue(v)));
    }

    private void listMethods(String name) {
        jshell.methods()
                .filter(m -> name == null || m.name().equals(name))
                .forEach(m -> show("  " + m.name() + " " + m.signature()));
    }

    private void listTypes(String name) {
        jshell.types()
                .filter(ty -> name == null || ty.name().equals(name))
                .forEach(ty -> show("  " + ty.subKind().name().replace("_SUBKIND", "").toLowerCase(Locale.ROOT) + " " + ty.name()));
    }

    private void dropSnippets(String target) {
        List<Snippet> targets = select(target);
        if (targets.isEmpty()) { show("No such snippet: " + target); return; }
        for (Snippet s : targets) {
            jshell.drop(s);
            show("Dropped " + s.id());
        }
    }

    private void freshJShell() {
        jshell.close();
        jshell = JShell.builder().out(System.out).err(System.err).build();
        syntaxHighlighter = new ReplSyntaxHighlighter(jshell);
        hiddenSnippetIds.clear(); // ids belong to the old session
        for (String jar : resolvedJars) jshell.addToClasspath(jar);
    }

    private void doReset() {
        freshJShell();
        acceptedSnippets.clear();
        pending.setLength(0);
        show("Resetting state.");
    }

    private void doReload(boolean quiet) {
        List<String> replay = new ArrayList<>(acceptedSnippets);
        freshJShell();
        acceptedSnippets.clear();
        pending.setLength(0);
        if (!quiet) show("Restarting and replaying " + replay.size() + " snippet(s).");
        for (String src : replay) runSource(src);
    }

    private void doSave(boolean all, boolean hist, boolean start, String fileName) throws IOException {
        Path file = Path.of(fileName);
        List<String> lines = new ArrayList<>();
        if (hist) lines.addAll(history);
        else if (all) allSnippets().forEach(s -> lines.add(s.source().strip()));
        else if (start) { /* no startup script configured */ }
        else {
            for (String gav : dependencies) lines.add("//DEPS " + gav);
            lines.addAll(acceptedSnippets);
        }
        Files.writeString(file, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
        show("Saved to " + file);
    }

    private void doOpen(String fileName) throws Exception {
        Path file = Path.of(fileName);
        if (!Files.exists(file)) { show("No such file: " + file); return; }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) accept(line);
    }

    private void rerunLast() {
        if (acceptedSnippets.isEmpty()) { show("No snippet to rerun."); return; }
        runSource(acceptedSnippets.get(acceptedSnippets.size() - 1));
    }

    private void rerunPrevious(int n) {
        if (n < 1 || n > acceptedSnippets.size()) { show("No such snippet: -" + n); return; }
        runSource(acceptedSnippets.get(acceptedSnippets.size() - n));
    }

    private void rerunIds(String token) {
        int dash = token.indexOf('-');
        int from = Integer.parseInt(dash < 0 ? token : token.substring(0, dash));
        int to = dash < 0 ? from : Integer.parseInt(token.substring(dash + 1));
        boolean any = false;
        for (int id = from; id <= to; id++) {
            List<Snippet> s = select(String.valueOf(id));
            if (!s.isEmpty()) { any = true; runSource(s.get(0).source()); }
        }
        if (!any) show("No such snippet: " + token);
    }

    private void doEnv(String classPath) {
        if (classPath == null) {
            if (resolvedJars.isEmpty()) show("No additional classpath.");
            else resolvedJars.forEach(j -> show("  --class-path " + j));
            return;
        }
        for (String jar : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!jar.isBlank()) { jshell.addToClasspath(jar); resolvedJars.add(jar); }
        }
        show("Setting new options and restoring state.");
    }

    private void doEdit(String target) {
        // Headless REPL: no external editor; show the snippet source instead.
        List<Snippet> snippets = target != null ? select(target) : activeSnippets();
        if (snippets.isEmpty()) { show("No such snippet" + (target != null ? ": " + target : ".")); return; }
        for (Snippet s : snippets) show(s.source().strip());
    }

    private void doExit(Integer code) {
        exitRequested = true;
        if (code != null) exitCode = code;
        // /exit runs on the console's reader thread; stopping the console from there doesn't
        // reliably unblock start(), and JShell's agent thread keeps the JVM alive. Hard-exit
        // after best-effort cleanup (the shutdown hook restores the terminal + closes JShell).
        if (connection == null) return;   // headless/tests: just record intent
        try { if (console != null) console.stop(); } catch (Exception ignored) {}
        System.exit(exitCode);
    }

    private void helpTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            show("Available commands:");
            show("  /list [--all|<name>]  list source you have typed");
            show("  /vars [<name>]        list declared variables");
            show("  /methods [<name>]     list declared methods");
            show("  /types [<name>]       list declared types");
            show("  /imports              list imported items");
            show("  /drop <name|id>       delete a snippet");
            show("  /save [--all|--history|--start] <file>  save source to a file");
            show("  /open <file>          open a file as source input");
            show("  /reset                reset the REPL state");
            show("  /reload [--quiet]     reset and replay history");
            show("  /history              history of what you have typed");
            show("  /set [feedback <m>]   set configuration");
            show("  /env [--class-path p] view or change the eval context");
            show("  /edit <name|id>       show snippet source");
            show("  /deps add <GAV>|list  manage JBang dependencies");
            show("  /clear                clear the pending input buffer");
            show("  /! | /<id> | /-<n>    rerun snippets");
            show("  /exit [<code>]        exit the REPL");
            show("  /help [<command>]     this help");
            return;
        }
        String key = topic.startsWith("/") ? topic.substring(1) : topic;
        show(switch (key) {
            case "list" -> "/list lists the snippets you have entered. /list --all (-a) includes failed/overwritten ones.";
            case "vars" -> "/vars lists declared variables and their current values.";
            case "methods" -> "/methods lists declared methods and their signatures.";
            case "types" -> "/types lists declared classes and interfaces.";
            case "drop" -> "/drop <name or id> deletes a snippet.";
            case "save" -> "/save [--all|--history|--start] <file> writes source to a file.";
            case "open" -> "/open <file> reads and evaluates a file.";
            case "reset" -> "/reset discards all snippets and restarts the engine.";
            case "reload" -> "/reload restarts the engine and replays accepted snippets.";
            case "history" -> "/history prints every line you have entered.";
            case "exit", "quit" -> "/exit [<code>] leaves the REPL.";
            case "deps" -> "/deps add <group:artifact:version> resolves and adds a dependency; /deps list shows them.";
            default -> "No help for /" + key + ". Try /help.";
        });
    }

    /** Reuse jbang's gavsearch alias to look up Maven coordinates for completion. */
    private List<String> gavSearch(String query) {
        try {
            Process p = new ProcessBuilder("jbang", "gavsearch@jbangdev", "-q", Maven.solrQuery(query))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            List<String> gavs;
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                gavs = Maven.parseGavLines(reader.lines(), 25);
            }
            p.waitFor();
            return gavs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void addDependency(String gav) throws Exception {
        if (!Maven.isCoordinate(gav)) {
            show("Expected Maven coordinates group:artifact:version");
            return;
        }
        if (dependencies.contains(gav)) { show("Already added: " + gav); return; }
        // Invoke the same JBang dependency resolver used for ordinary //DEPS scripts.
        Path probe = Files.createTempFile("jbang-repl-resolve-", ".java");
        String cp;
        int exit;
        try {
            Files.writeString(probe, "class DependencyProbe {}\n", StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("jbang", "info", "classpath", "--deps-only",
                    "--deps=" + gav, probe.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                cp = reader.lines().collect(Collectors.joining("\n")).strip();
            }
            exit = process.waitFor();
        } finally {
            Files.deleteIfExists(probe);
        }
        if (exit != 0 || cp.isBlank()) {
            show("JBang dependency resolution failed (exit " + exit + ")");
            return;
        }
        // JShell classpath updates are additive; removal/version swaps require a fresh session.
        for (String jar : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!jar.isBlank()) { jshell.addToClasspath(jar); resolvedJars.add(jar); }
        }
        dependencies.add(gav);
        show("Added " + gav + " to JShell classpath");
    }

    private void show(String message) { sink.accept(message); }
    boolean isExitRequested() { return exitRequested; }
    int exitCode() { return exitCode; }
    @Override public void close() { jshell.close(); }
}
