///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.aesh:readline:3.14
//DEPS org.aesh:terminal-tty:3.14

import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.Diag;
import jdk.jshell.SourceCodeAnalysis;
import org.aesh.readline.Readline;
import org.aesh.readline.ReadlineBuilder;
import org.aesh.terminal.tty.TerminalConnection;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Interactive Java snippets via JShell, terminal input via aesh. */
public class jbang_repl implements AutoCloseable {
    private final JShell jshell = JShell.builder().out(System.out).err(System.err).build();
    private final Readline readline = ReadlineBuilder.builder().build();
    private final TerminalConnection connection;
    private final List<String> dependencies = new ArrayList<>();
    private final List<String> acceptedSnippets = new ArrayList<>();
    private final StringBuilder pending = new StringBuilder();

    public jbang_repl() throws IOException {
        this.connection = new TerminalConnection();
    }

    public static void main(String[] args) throws Exception {
        try (var repl = new jbang_repl()) {
            if (args.length > 0) {
                if (args.length == 1 && "--help".equals(args[0])) {
                    System.out.println("Usage: jbang jbang_repl.java [--help]\nInteractive Java shell: /help for commands.");
                    return;
                }
                System.err.println("Unknown arguments: " + String.join(" ", args));
                System.exit(2);
            }
            System.out.println("JBang REPL (aesh + JShell, Java " + Runtime.version().feature() + ")");
            System.out.println("Enter Java or /help. Ctrl-D to exit.");
            repl.next();
            repl.connection.openBlocking();
        }
    }

    private void next() {
        readline.readline(connection, pending.isEmpty() ? "java> " : "...> ", line -> {
            if (line == null) { connection.close(); return; }
            try {
                accept(line);
            } catch (Exception ex) {
                show("Error: " + ex.getMessage());
            }
            next();
        });
    }

    private void accept(String line) throws Exception {
        String stripped = line.strip();
        if (pending.isEmpty() && stripped.startsWith("/")) {
            command(stripped.substring(1));
            return;
        }
        if (pending.isEmpty() && stripped.isBlank()) return;
        pending.append(line).append('\n');
        drain();
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

    private void command(String text) throws Exception {
        String[] args = text.strip().split("\\s+", 3);
        switch (args[0]) {
            case "exit", "quit" -> connection.close();
            case "help" -> show("Commands: /deps add <GAV>, /deps list, /vars, /imports, /list, /save <file>, /clear, /exit");
            case "clear" -> { pending.setLength(0); show("Input buffer cleared."); }
            case "vars" -> jshell.variables().forEach(v -> show(v.typeName() + " " + v.name() + " = " + jshell.varValue(v)));
            case "imports" -> jshell.imports().forEach(i -> show(i.source()));
            case "list" -> jshell.snippets().forEach(s -> show(s.id() + " " + jshell.status(s) + " " + s.source().strip()));
            case "save" -> {
                if (args.length < 2) { show("Usage: /save <filename.java>"); break; }
                Path file = Path.of(text.substring(5).strip());
                List<String> lines = new ArrayList<>();
                for (String gav : dependencies) lines.add("//DEPS " + gav);
                lines.addAll(acceptedSnippets);
                Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
                show("Saved snippets to " + file);
            }
            case "deps" -> {
                if (args.length >= 2 && "list".equals(args[1])) {
                    if (dependencies.isEmpty()) show("No added dependencies.");
                    dependencies.forEach(this::show);
                } else if (args.length == 3 && "add".equals(args[1])) {
                    addDependency(args[2].strip());
                } else show("Usage: /deps add <group:artifact:version> | /deps list");
            }
            default -> show("Unknown command /" + args[0] + ". Try /help");
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
            if (!jar.isBlank()) jshell.addToClasspath(jar);
        }
        dependencies.add(gav);
        show("Added " + gav + " to JShell classpath");
    }

    private void show(String message) { connection.write(message + "\n"); }
    @Override public void close() { jshell.close(); }
}
