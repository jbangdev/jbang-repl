///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.aesh:aesh:3.18.5
//SOURCES DocFormat.java
//SOURCES Maven.java
//SOURCES ReplSyntaxHighlighter.java
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED

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
        try {
            // Drive the loop with aesh's ReadlineConsole (3.18.5 exposes cursorListener +
            // completionHandler seams). Commands are dispatched by our own accept() via the
            // commandNotFoundHandler, so the registry is empty and every line routes there.
            var settings = SettingsBuilder.<CommandInvocation>builder()
                    .connection(connection)
                    .aeshContext(aeshContext)
                    .commandRegistry(AeshCommandRegistryBuilder.<CommandInvocation>builder().create())
                    .enableOperatorParser(false)   // don't parse Java |, ;, >, && as shell operators
                    .enableAlias(false)
                    .persistHistory(false)
                    .redrawPromptOnInterrupt(true)
                    .promptSupplier(() -> new Prompt(pending.length() == 0 ? "java> " : "...> "))
                    .commandNotFoundHandler((line, output) -> {
                        try { accept(line); } catch (Exception e) { show("Error: " + e.getMessage()); }
                    })
                    .setInterruptHandler(v -> { if (handleInterrupt(System.currentTimeMillis())) doExit(null); })
                    .cursorListener(this::colorize)
                    .completionHandler(new DocHandler())
                    .build();
            console = new ReadlineConsole(settings);
            console.addCompletion(new Completion<CompleteOperation>() {
                public void complete(CompleteOperation co) { repl.this.complete(co); }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to build console", e);
        }
    }

    /** Register every slash command as an aesh @Command and build the runtime. */
    private void buildCommandRuntime() {
        try {
            var reg = AeshCommandRegistryBuilder.builder()
                    .command(new ExitCmd())
                    .command(new HelpCmd())
                    .command(new ClearCmd())
                    .command(new ListCmd())
                    .command(new VarsCmd())
                    .command(new MethodsCmd())
                    .command(new TypesCmd())
                    .command(new ImportsCmd())
                    .command(new DropCmd())
                    .command(new ResetCmd())
                    .command(new ReloadCmd())
                    .command(new HistoryCmd())
                    .command(new SaveCmd())
                    .command(new OpenCmd())
                    .command(new SetCmd())
                    .command(new EnvCmd())
                    .command(new EditCmd())
                    .command(new DepsCmd())
                    .create();
            runtime = AeshCommandRuntimeBuilder.builder().commandRegistry(reg).build();
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

    // Verbatim copies of jshell's predefined startup scripts (encapsulated in the jdk.jshell
    // module, not exposed by the public API). DEFAULT uses the portable import list so it works
    // on Java 21+ (jshell's DEFAULT.jsh uses `import module java.base;`, which needs Java 25).
    static final String DEFAULT_JSH = """
        import java.io.*;
        import java.math.*;
        import java.net.*;
        import java.nio.file.*;
        import java.util.*;
        import java.util.concurrent.*;
        import java.util.function.*;
        import java.util.prefs.*;
        import java.util.regex.*;
        import java.util.stream.*;
        """;
    static final String PRINTING_JSH = """
        void print(boolean b) { System.out.print(b); }
        void print(char c) { System.out.print(c); }
        void print(int i) { System.out.print(i); }
        void print(long l) { System.out.print(l); }
        void print(float f) { System.out.print(f); }
        void print(double d) { System.out.print(d); }
        void print(char s[]) { System.out.print(s); }
        void print(String s) { System.out.print(s); }
        void print(Object obj) { System.out.print(obj); }
        void println() { System.out.println(); }
        void println(boolean b) { System.out.println(b); }
        void println(char c) { System.out.println(c); }
        void println(int i) { System.out.println(i); }
        void println(long l) { System.out.println(l); }
        void println(float f) { System.out.println(f); }
        void println(double d) { System.out.println(d); }
        void println(char s[]) { System.out.println(s); }
        void println(String s) { System.out.println(s); }
        void println(Object obj) { System.out.println(obj); }
        void printf(java.util.Locale l, String format, Object... args) { System.out.printf(l, format, args); }
        void printf(String format, Object... args) { System.out.printf(format, args); }
        """;
    static final String TOOLING_JSH = """
        void run(String name, String... args) {
            var tool = java.util.spi.ToolProvider.findFirst(name);
            if (tool.isEmpty()) throw new RuntimeException("No such tool found: " + name);
            var code = tool.get().run(System.out, System.err, args);
            if (code != 0) System.err.println(name + " returned non-zero exit code: " + code);
        }
        void jar(String... args) { run("jar", args); }
        void javac(String... args) { run("javac", args); }
        void javadoc(String... args) { run("javadoc", args); }
        void javap(String... args) { run("javap", args); }
        void jdeps(String... args) { run("jdeps", args); }
        void jlink(String... args) { run("jlink", args); }
        void jmod(String... args) { run("jmod", args); }
        void jpackage(String... args) { run("jpackage", args); }
        void tools() {
            java.util.ServiceLoader.load(java.util.spi.ToolProvider.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .map(java.util.spi.ToolProvider::name).sorted().forEach(System.out::println);
        }
        """;

    static final Map<String, String> STARTUP_PRESETS = Map.of(
            "DEFAULT", DEFAULT_JSH,
            "PRINTING", PRINTING_JSH,
            "TOOLING", TOOLING_JSH,
            "JAVASE", "import module java.se;\n"); // needs Java 25+; best-effort

    /** Resolve a startup spec (preset name or file path) and evaluate it silently. */
    void loadStartup(String spec) throws IOException {
        String content = STARTUP_PRESETS.containsKey(spec) ? STARTUP_PRESETS.get(spec)
                : Files.readString(Path.of(spec), StandardCharsets.UTF_8);
        runStartupSilently(content);
    }

    /** Evaluate a block of snippets without echoing results or recording them. */
    void runStartupSilently(String content) {
        var sca = jshell.sourceCodeAnalysis();
        String src = content;
        while (src != null && !src.isBlank()) {
            var info = sca.analyzeCompletion(src);
            String snippet = info.source();
            if (snippet == null || snippet.isBlank()) break;
            jshell.eval(snippet);
            src = info.remaining();
        }
    }

    /** Headless constructor for tests: no terminal, output captured via sink. */
    repl(java.util.function.Consumer<String> sink) {
        connection = null;
        console = null;
        this.sink = sink;
        buildCommandRuntime();
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

    /** Candidate first-arguments for a /command: static flags/subcommands plus dynamic snippet names. */
    private List<String> commandArgs(String cmd) {
        List<String> out = new ArrayList<>(switch (cmd) {
            case "deps" -> List.of("add", "list", "search");
            case "save" -> List.of("-all", "-history", "-start");
            case "list" -> List.of("-all");
            case "reload" -> List.of("-quiet");
            case "set" -> List.of("feedback");
            case "env" -> List.of("--class-path");
            case "help" -> COMMANDS;
            default -> List.of();
        });
        // Snippet-targeting commands also complete declared names.
        if (List.of("drop", "edit", "list", "vars", "methods", "types").contains(cmd)) {
            jshell.snippets()
                    .filter(s -> s instanceof jdk.jshell.DeclarationSnippet)
                    .map(s -> ((jdk.jshell.DeclarationSnippet) s).name())
                    .forEach(out::add);
        }
        return out;
    }

    void complete(CompleteOperation co) {
        String prefix = pending.toString();
        // Complete Maven coordinates via jbang's gavsearch for /deps add <query>.
        if (prefix.isEmpty() && co.getBuffer().startsWith(DEPS_ADD)) {
            String query = co.getBuffer().substring(DEPS_ADD.length(), co.getCursor()).strip();
            if (query.isEmpty()) return;
            co.setOffset(DEPS_ADD.length());
            co.setAppendSeparator(false);
            gavSearch(query).forEach(co::addCompletionCandidate);
            return;
        }
        // Complete the first argument of a /command (subcommands, flags, or snippet names).
        if (prefix.isEmpty() && co.getBuffer().matches("/\\w+ [^ ]*")) {
            int sp = co.getBuffer().indexOf(' ');
            String cmd = co.getBuffer().substring(1, sp);
            String word = co.getBuffer().substring(sp + 1, co.getCursor());
            co.setOffset(sp + 1);
            co.setAppendSeparator(false);
            commandArgs(cmd).stream().filter(s -> s.startsWith(word)).distinct().forEach(co::addCompletionCandidate);
            return;
        }
        // Complete /commands when a slash command is being typed.
        if (prefix.isEmpty() && co.getBuffer().startsWith("/") && !co.getBuffer().contains(" ")) {
            String word = co.getBuffer().substring(1, co.getCursor());
            co.setOffset(0);
            co.setAppendSeparator(false);
            COMMANDS.stream().filter(c -> c.startsWith(word)).map(c -> "/" + c).forEach(co::addCompletionCandidate);
            return;
        }
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
    private boolean docJavadocNext;  // next Tab at the same spot shows javadoc

    // Shared context so DocHandler and the console build the same AeshCompleteOperation type.
    private final org.aesh.console.AeshContext aeshContext = new org.aesh.console.DefaultAeshContext();

    /** Custom handler: render jshell-style signature/doc help ourselves (ordered, formatted), else default.
     *  Extends AeshCompletionHandler so it creates AeshCompleteOperation (required by ReadlineConsole's
     *  internal AeshCompletion). */
    final class DocHandler extends org.aesh.command.impl.completer.AeshCompletionHandler {
        DocHandler() { super(aeshContext); }
        @Override public void complete(org.aesh.readline.InputProcessor ip) {
            if (renderDoc(ip.buffer())) return;
            super.complete(ip);
        }
    }

    /** If the cursor warrants signature help, render it (in order) and redraw. Returns true if shown. */
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
        int[] anchor = new int[1];
        boolean noCompletions = jshell.sourceCodeAnalysis().completionSuggestions(code, cursor, anchor).isEmpty();
        if (!argPos && !noCompletions) return false;
        String key = cursor + ":" + code;
        boolean javadoc = key.equals(lastDocKey) && docJavadocNext;
        List<String> lines = documentationLines(code, cursor, javadoc);
        if (lines.isEmpty()) { lastDocKey = null; return false; }
        StringBuilder sb = new StringBuilder("\r\n");
        for (String l : lines) sb.append(DocFormat.colorLine(l)).append("\r\n");
        cb.writeOut(sb.toString());
        cb.drawLineForceDisplay();
        lastDocKey = key;
        docJavadocNext = !javadoc;
        return true;
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
                    // Alias $_ to the latest result (like Python's _); ignore its own events.
                    if (event.snippet() instanceof jdk.jshell.VarSnippet vs && !vs.name().equals("$_")) {
                        jshell.eval("var $_ = " + vs.name() + ";");
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
            runtime.executeCommand(t);
        } catch (CommandNotFoundException e) {
            show("Unknown command /" + t.split("\\s+", 2)[0] + ". Try /help");
        } catch (Exception e) {
            show(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ---- aesh @Command wrappers (parsing/validation/help) delegating to the helpers below ----

    @CommandDefinition(name = "exit", aliases = {"quit"}, description = "exit the REPL")
    public final class ExitCmd implements Command<CommandInvocation> {
        @Argument(description = "exit code")
        Integer code;
        public CommandResult execute(CommandInvocation ci) { doExit(code); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "help", description = "get information about commands")
    public final class HelpCmd implements Command<CommandInvocation> {
        @Argument(description = "command")
        String topic;
        public CommandResult execute(CommandInvocation ci) { helpTopic(topic == null ? "" : topic); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "clear", description = "clear the pending input buffer")
    public final class ClearCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { pending.setLength(0); show("Input buffer cleared."); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "list", description = "list source you have typed (-all or <name>)")
    public final class ListCmd implements Command<CommandInvocation> {
        @Arguments(description = "-all | <name>")
        List<String> args;
        public CommandResult execute(CommandInvocation ci) {
            boolean all = args != null && args.contains("-all");
            String name = (args == null) ? null : args.stream().filter(a -> !a.startsWith("-")).findFirst().orElse(null);
            listSnippets(all, name);
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "vars", description = "list declared variables")
    public final class VarsCmd implements Command<CommandInvocation> {
        @Argument(description = "variable name")
        String name;
        public CommandResult execute(CommandInvocation ci) { listVars(name); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "methods", description = "list declared methods")
    public final class MethodsCmd implements Command<CommandInvocation> {
        @Argument(description = "method name")
        String name;
        public CommandResult execute(CommandInvocation ci) { listMethods(name); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "types", description = "list declared types")
    public final class TypesCmd implements Command<CommandInvocation> {
        @Argument(description = "type name")
        String name;
        public CommandResult execute(CommandInvocation ci) { listTypes(name); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "imports", description = "list imported items")
    public final class ImportsCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { jshell.imports().forEach(i -> show("  " + i.source())); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "drop", description = "delete a snippet")
    public final class DropCmd implements Command<CommandInvocation> {
        @Argument(description = "snippet name or id", required = true)
        String target;
        public CommandResult execute(CommandInvocation ci) { dropSnippets(target); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "reset", description = "reset the REPL state")
    public final class ResetCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { doReset(); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "reload", description = "reset and replay history (-quiet)")
    public final class ReloadCmd implements Command<CommandInvocation> {
        @Arguments(description = "-quiet")
        List<String> args;
        public CommandResult execute(CommandInvocation ci) { doReload(args != null && args.contains("-quiet")); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "history", description = "history of what you have typed")
    public final class HistoryCmd implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) { history.forEach(repl.this::show); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "save", description = "save source to a file ([-all|-history|-start] <file>)")
    public final class SaveCmd implements Command<CommandInvocation> {
        @Arguments(description = "[-all|-history|-start] <file>")
        List<String> args;
        public CommandResult execute(CommandInvocation ci) {
            if (args == null || args.isEmpty()) { show("Usage: /save [-all|-history|-start] <file>"); return CommandResult.SUCCESS; }
            boolean all = args.contains("-all"), hist = args.contains("-history"), start = args.contains("-start");
            String file = args.stream().filter(a -> !a.startsWith("-")).findFirst().orElse(null);
            if (file == null) { show("Usage: /save [-all|-history|-start] <file>"); return CommandResult.SUCCESS; }
            try { doSave(all, hist, start, file); } catch (IOException e) { show("Error: " + e.getMessage()); return CommandResult.FAILURE; }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "open", description = "open a file as source input")
    public final class OpenCmd implements Command<CommandInvocation> {
        @Argument(description = "file", required = true)
        String file;
        public CommandResult execute(CommandInvocation ci) {
            try { doOpen(file); } catch (Exception e) { show("Error: " + e.getMessage()); return CommandResult.FAILURE; }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "set", description = "set configuration")
    public final class SetCmd implements Command<CommandInvocation> {
        @Arguments(description = "<sub> [value]")
        List<String> args;
        public CommandResult execute(CommandInvocation ci) { doSet(args); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "env", description = "view or change the eval context")
    public final class EnvCmd implements Command<CommandInvocation> {
        @Option(name = "class-path", description = "add to the classpath")
        String classPath;
        public CommandResult execute(CommandInvocation ci) { doEnv(classPath); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "edit", description = "show snippet source")
    public final class EditCmd implements Command<CommandInvocation> {
        @Argument(description = "snippet name or id")
        String target;
        public CommandResult execute(CommandInvocation ci) { doEdit(target); return CommandResult.SUCCESS; }
    }

    @CommandDefinition(name = "deps", description = "manage JBang dependencies (add <GAV|query|#> | search <query> | list)")
    public final class DepsCmd implements Command<CommandInvocation> {
        @Arguments(description = "<add|search|list> ...")
        List<String> args;
        public CommandResult execute(CommandInvocation ci) {
            String usage = "Usage: /deps add <GAV|query|#>, /deps search <query>, /deps list";
            if (args == null || args.isEmpty()) { show(usage); return CommandResult.SUCCESS; }
            String sub = args.get(0);
            String rest = args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : "";
            try {
                switch (sub) {
                    case "list" -> depsList();
                    case "search" -> { if (rest.isBlank()) show("Usage: /deps search <query>"); else depsSearch(rest); }
                    case "add" -> { if (rest.isBlank()) show("Usage: /deps add <GAV|query|#>"); else depsAdd(rest); }
                    default -> show(usage);
                }
            } catch (Exception e) { show("Error: " + e.getMessage()); return CommandResult.FAILURE; }
            return CommandResult.SUCCESS;
        }
    }

    // ---- jshell command implementations ----

    private void runSource(String src) {
        if (src == null || src.isBlank()) return;
        pending.append(src);
        if (!src.endsWith("\n")) pending.append('\n');
        drain();
    }

    private List<Snippet> allSnippets() { return jshell.snippets().collect(Collectors.toList()); }

    private List<Snippet> activeSnippets() {
        return jshell.snippets().filter(s -> jshell.status(s).isActive()).collect(Collectors.toList());
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

    private void doSet(List<String> args) {
        if (args == null || args.isEmpty()) { show("Settings: feedback " + feedbackMode); return; }
        String sub = args.get(0);
        switch (sub) {
            case "feedback" -> {
                if (args.size() > 1) { feedbackMode = args.get(1).strip(); show("Feedback mode: " + feedbackMode); }
                else show("Feedback mode: " + feedbackMode);
            }
            case "start", "editor", "mode", "prompt", "truncation" -> show("/set " + sub + " is not configurable in this REPL.");
            default -> show("Usage: /set feedback <mode>");
        }
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
            show("  /list [-all|<name>]   list source you have typed");
            show("  /vars [<name>]        list declared variables");
            show("  /methods [<name>]     list declared methods");
            show("  /types [<name>]       list declared types");
            show("  /imports              list imported items");
            show("  /drop <name|id>       delete a snippet");
            show("  /save [-all|-history|-start] <file>  save source to a file");
            show("  /open <file>          open a file as source input");
            show("  /reset                reset the REPL state");
            show("  /reload [-quiet]      reset and replay history");
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
            case "list" -> "/list lists the snippets you have entered. /list -all includes failed/overwritten ones.";
            case "vars" -> "/vars lists declared variables and their current values.";
            case "methods" -> "/methods lists declared methods and their signatures.";
            case "types" -> "/types lists declared classes and interfaces.";
            case "drop" -> "/drop <name or id> deletes a snippet.";
            case "save" -> "/save [-all|-history|-start] <file> writes source to a file.";
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
