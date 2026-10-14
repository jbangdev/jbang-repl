///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.aesh:aesh:3.18.4

import org.aesh.command.Command;
import org.aesh.command.GroupCommand;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.CommandException;
import org.aesh.command.option.Option;
import org.aesh.command.option.Argument;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.CommandRuntime;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;

/** Spike: model /deps as an aesh @Command group with subcommands, options and auto-help. */
public class deps_command_spike {

    @CommandDefinition(name = "deps", description = "Manage JBang dependencies",
            groupCommands = { DepsAdd.class, DepsList.class })
    public static class Deps implements GroupCommand<CommandInvocation> {
        @Option(shortName = 'h', hasValue = false, overrideRequired = true)
        boolean help;
        public java.util.List<Command<CommandInvocation>> getCommands() {
            return java.util.List.of(new DepsAdd(), new DepsList());
        }
        public CommandResult execute(CommandInvocation ci) {
            ci.getShell().writeln("Usage: /deps <add|list> ...");
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "add", description = "Resolve and add a dependency")
    public static class DepsAdd implements Command<CommandInvocation> {
        @Option(name = "dry-run", hasValue = false, description = "only print, don't resolve")
        boolean dryRun;
        @Argument(description = "Maven coordinates group:artifact:version", required = true)
        String gav;
        public CommandResult execute(CommandInvocation ci) {
            if (gav == null) { ci.getShell().writeln("error: missing <gav>"); return CommandResult.FAILURE; }
            ci.getShell().writeln((dryRun ? "[dry-run] would add " : "added ") + gav);
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "list", description = "List added dependencies")
    public static class DepsList implements Command<CommandInvocation> {
        public CommandResult execute(CommandInvocation ci) {
            ci.getShell().writeln("(no dependencies)");
            return CommandResult.SUCCESS;
        }
    }

    public static void main(String[] args) throws Exception {
        CommandRuntime<?> runtime = AeshCommandRuntimeBuilder.builder()
                .commandRegistry(AeshCommandRegistryBuilder.builder().command(Deps.class).create())
                .build();

        for (String line : new String[]{
                "deps add org.example:foo:1.0",
                "deps add --dry-run org.example:bar:2.0",
                "deps list",
                "deps add",            // missing required arg -> parser/validator error
                "deps --help" }) {
            System.out.println("\n$ /" + line);
            try {
                runtime.executeCommand(line);
            } catch (Exception e) {
                System.out.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        System.out.println("\n--- generated help for 'deps add' ---");
        System.out.println(runtime.commandInfo("deps add"));
    }
}
