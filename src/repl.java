///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.aesh:aesh:3.18.4
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED

import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.Diag;
import jdk.jshell.SourceCodeAnalysis;
import org.aesh.readline.Readline;
import org.aesh.readline.ReadlineBuilder;
import org.aesh.terminal.tty.TerminalConnection;
import org.aesh.readline.completion.Completion;
import org.aesh.readline.completion.CompleteOperation;
import org.aesh.readline.ReadlineRequest;
import org.aesh.readline.cursor.Line;
import org.aesh.terminal.formatting.Color;

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
    private final Readline readline;
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
        readline = ReadlineBuilder.builder().completionHandler(new DocHandler()).build();
        sink = s -> connection.write(s + "\n");
        buildCommandRuntime();
        // Ctrl-C arrives as SIGINT (native signals), not a raw byte. Confirm before quitting.
        connection.setSignalHandler(sig -> {
            if (sig == org.aesh.terminal.tty.Signal.INT && handleInterrupt(System.currentTimeMillis()))
                doExit(null);
        });
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

    /** Headless constructor for tests: no terminal, output captured via sink. */
    repl(java.util.function.Consumer<String> sink) {
        connection = null;
        readline = null;
        this.sink = sink;
        buildCommandRuntime();
    }

    public static void main(String[] args) throws Exception {
        try (var repl = new repl()) {
            if (args.length > 0) {
                if (args.length == 1 && "--help".equals(args[0])) {
                    System.out.println("Usage: jbang repl.java [--help]\nInteractive Java shell: /help for commands.");
                    return;
                }
                System.err.println("Unknown arguments: " + String.join(" ", args));
                System.exit(2);
            }
            // External kill (SIGTERM) can't prompt; at least restore the terminal on exit.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (repl.connection != null) repl.connection.close(); } catch (Exception ignored) {}
            }));
            System.out.println("JBang REPL (aesh + JShell, Java " + Runtime.version().feature() + ")");
            System.out.println("Enter Java or /help. Ctrl-D to exit.");
            repl.next();
            repl.connection.openBlocking();
            if (repl.exitRequested && repl.exitCode != 0) System.exit(repl.exitCode);
        }
    }

    /** Bridge JShell completion into readline. */
    private static final List<String> COMMANDS = List.of(
            "exit", "quit", "help", "clear", "list", "vars", "methods", "types", "imports",
            "drop", "reset", "reload", "history", "save", "open", "set", "env", "edit", "deps");

    private static final String DEPS_ADD = "/deps add ";
    private final List<String> lastSearch = new ArrayList<>();
    private static final String GAV_RE = "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^ ]+";

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
        } else if (arg.matches(GAV_RE)) {
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

    /** Custom handler: render jshell-style signature/doc help ourselves (ordered, formatted), else default. */
    final class DocHandler extends org.aesh.readline.completion.impl.SimpleCompletionHandler {
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
        for (String l : lines) sb.append(colorDocLine(l)).append("\r\n");
        cb.writeOut(sb.toString());
        cb.drawLineForceDisplay();
        lastDocKey = key;
        docJavadocNext = !javadoc;
        return true;
    }

    private static final String DOC_HDR = "\u001b[1;33m";   // bold yellow: section headers
    private static final String DOC_SIG = "\u001b[1;36m";   // bold cyan: method signatures
    private static final String DOC_DIM = "\u001b[90m";     // grey: the hint
    private static final String DOC_RST = "\u001b[0m";

    /** Apply ANSI colour to one documentation line based on its role. */
    static String colorDocLine(String l) {
        if (l.equals("Parameters:") || l.equals("Returns:") || l.equals("Thrown Exceptions:"))
            return DOC_HDR + l + DOC_RST;
        if (l.startsWith("<press tab")) return DOC_DIM + l + DOC_RST;
        if (!l.isEmpty() && !l.startsWith(" ") && l.contains("(") && l.contains(")"))
            return DOC_SIG + l + DOC_RST;   // signature line
        return l;
    }

    /** Signature(s) and, when javadoc=true, a jshell-style formatted javadoc body. */
    List<String> documentationLines(String code, int cursor, boolean javadoc) {
        var docs = jshell.sourceCodeAnalysis().documentation(code, cursor, javadoc);
        List<String> lines = new ArrayList<>();
        for (var d : docs) {
            lines.add(d.signature());
            if (javadoc && d.javadoc() != null && !d.javadoc().isBlank()) {
                lines.add("");
                lines.addAll(formatJavadoc(d.javadoc()));
            }
        }
        if (!lines.isEmpty() && !javadoc) lines.add("<press tab again to see documentation>");
        return lines;
    }

    /** Turn raw javadoc (inline + block tags) into readable text like jshell's formatter. */
    static List<String> formatJavadoc(String javadoc) {
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
                if (target.isEmpty()) target.add(l); else target.set(target.size() - 1, target.get(target.size() - 1) + " " + l);
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

    private final List<Completion> completions = List.<Completion>of(new Completion<CompleteOperation>() {
        public void complete(CompleteOperation co) { repl.this.complete(co); }
    });

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

    private void next() {
        var request = ReadlineRequest.builder()
                .connection(connection)
                .prompt(pending.isEmpty() ? "java> " : "...> ")
                .completions(completions)
                .cursorListener(this::colorize)
                .requestHandler(line -> {
                    if (line == null) { connection.close(); return; }
                    try {
                        accept(line);
                    } catch (Exception ex) {
                        show("Error: " + ex.getMessage());
                    }
                    if (!exitRequested) next(); // don't re-arm readline on a closed connection

                })
                .build();
        readline.readline(request);
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
        if (connection != null) connection.close();
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

    /** Reuse jbang's gavsearch alias to look up Maven coordinates for completion. */
    private List<String> gavSearch(String query) {
        try {
            Process p = new ProcessBuilder("jbang", "gavsearch@jbangdev", "-q", solrQuery(query))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            List<String> gavs;
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                gavs = reader.lines()
                        .filter(l -> l.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^ ]+"))
                        .distinct().limit(25).collect(Collectors.toList());
            }
            p.waitFor();
            return gavs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void addDependency(String gav) throws Exception {
        if (!gav.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\\-]+(?:[:@][A-Za-z0-9_.-]+)*")) {
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

