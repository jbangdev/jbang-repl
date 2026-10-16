# jbang-repl

A Java REPL that runs as a single [JBang](https://jbang.dev) script. It uses the
public **JShell** API for the language smarts and **aesh-readline** for the
terminal, then adds the things I always wished `jshell` had: live Maven
dependencies, colour everywhere, and documentation you can actually read.

## Try it

One command. No clone, no build, no install:

```sh
jbang repl@jbangdev/jbang-repl
```

(Requires JBang on your `PATH` and JDK 21+. JDK 25+ gets module imports.)

```text
jrepl> 40 + 2
$1 ==> 42
jrepl> $_ * 10            # $_ is the last result
$2 ==> 420
jrepl> /deps add jackson-databind     # pick from a live search, no restart
jrepl> var m = new com.fasterxml.jackson.databind.ObjectMapper();
jrepl> ProcessHandle.current().info()<TAB>   # signature, then paged javadoc
```

## Why not just jshell?

`jshell` is great. This leans on the same JShell engine but changes the parts
that live *around* it:

| | jshell | jbang-repl |
|---|---|---|
| **Add a library mid-session** | not possible — restart with a classpath | `/deps add <query>` searches Maven Central and adds it live |
| **Last result** | `$1`, `$2`, … (you track the number) | `$_` always points at the latest result |
| **Signature help** | monochrome text | types, method names and parameter names each coloured |
| **Javadoc on Tab** | staged, plain text | staged **and** paginated **and** colour-formatted (HTML/`{@link}` stripped) |
| **Command help** | `/help <cmd>` | double-Tab on any `/command` renders its options and args inline |
| **Input** | plain | live syntax highlighting as you type |
| **Prompt** | customisable but no colour, `%s` only | built from code — colour and anything dynamic |
| **Ctrl-C** | interrupts | asks first, confirms on a second press |
| **Startup scripts** | built in | the *real* `DEFAULT`/`PRINTING`/`TOOLING` loaded from the JDK, plus your own |

### `/deps` — the headline feature

Type part of a name and let it search:

```text
jrepl> /deps search jackson-databind
  1) com.fasterxml.jackson.core:jackson-databind:2.19.0
  2) tools.jackson.core:jackson-databind:3.0.0-rc4
  ...
Add one with: /deps add <#>
jrepl> /deps add 1
Added com.fasterxml.jackson.core:jackson-databind:2.19.0
```

Resolution goes through JBang itself, so the same `//DEPS` coordinates work, and
`/save` writes them back out as a runnable JBang script.

### Progressive, colourful docs

Tab once to complete, Tab again for the signature, keep tabbing to page through
the javadoc — types in cyan, names in magenta, parameters in yellow, section
headers in bold. Works on methods (`foo(`), closed calls (`foo()`), bare
classes (`System`) and fields (`Integer.MAX_VALUE`).

## Commands

Standard jshell-style set, implemented as proper [aesh](https://github.com/aeshell/aesh)
`@Command`s (so `--options` and group sub-commands get parsed and completed for you):

```
/list /vars /methods /types /imports /drop /edit
/save /open /reset /reload /history
/deps (add|search|list)   /set feedback   /env
/help [cmd]   /exit   /! /<id> /-<n>      # rerun
```

Startup selection mirrors jshell:

```sh
jbang repl@jbangdev/jbang-repl --startup PRINTING --startup TOOLING   # println(), javac(), ...
jbang repl@jbangdev/jbang-repl --no-startup
```

## How it works

- **Semantics** come from the public `jdk.jshell.SourceCodeAnalysis` — the exact
  engine `jshell` uses — so completions, signatures and javadoc are identical.
- **Presentation** (progressive docs, pagination, colour, the prompt loop) is
  custom, because jshell's own version lives in an encapsulated internal package
  with no public API.
- Startup scripts are read straight from the JDK's `jdk.jshell` module resources
  rather than copied.

## Status

Experimental prototype, not an official JBang command. A single source file
(`src/dev/jbang/repl/repl.java`) plus a few helpers; tests in `test/`.

```sh
jbang test/dev/jbang/repl/replTest.java      # run the test suite
```
