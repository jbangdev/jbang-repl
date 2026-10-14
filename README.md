# jbang-repl (experimental) — highlighting spike

A Java REPL backed by the public JShell API and using **aesh-readline** (not JLine).

## Run

Requires JBang on `PATH` and JDK 21+.

```sh
jbang repl.java
```

Or install with `jbang app install --name jbang-repl repl.java` (the helper is also embedded in the single-file JBang source). This is an **experimental prototype**, not an upstream JBang command.

Use Java expressions, `/deps add`, `/vars`, `/list`, `/save`, `/exit`.

```text
java> var greeting = "hello";
java> /deps add com.fasterxml.jackson.core:jackson-databind:2.19.0
java> /highlight preview var x = "hello";
```

`/highlight preview` prints a highlighted sample **after Enter**. It does NOT color the editable buffer while typing. This is deliberate: aesh-readline 3.14 does not expose a supported highlighting/redraw extension point. Printing ANSI escape sequences using a cursor listener corrupts interactive redraw and is not an acceptable workaround.

## Highlighting implementation

`ReplSyntaxHighlighter.java` takes a `JShell` instance and returns SGR-colored text for the supplied buffer. It uses `SourceCodeAnalysis.highlights()` for semantic keyword/deprecation information, plus basic lexical styling for strings, char literals, text blocks and comments. Slash-prefixed commands are colored separately. The adapter preserves unstyled source text (no formatting changes).

Java highlighting currently uses UTF-16 indices supplied by JShell; ANSI rendering never enters the editable input buffer.

## Upstream aesh-readline work required for true live highlighting

See [AESh_HIGHLIGHTER_HOOK.md](AESh_HIGHLIGHTER_HOOK.md). The contract is render-only: the highlighter sees the full editable buffer and returns styled spans or styled text, with no mutation of cursor positions/history/undo. The renderer must avoid delta-only partial repaints while highlighting is active.

## Local smoke test

```sh
javac ReplSyntaxHighlighter.java HighlighterSmokeTest.java
java HighlighterSmokeTest
```

This smoke test ran successfully on OpenJDK 21. It checks that removing ANSI styling yields exactly the original input. The aesh + JBang runtime integration could not be tested here because JBang and its dependencies are not installed in the environment.

## Limitations

The current REPL still manually dispatches `/` commands; moving these to `aesh-command` is separate work. Runtime dependency resolution shells out to JBang. Full standard JShell command coverage and Java completion are not implemented. Do not run untrusted Java snippets.
