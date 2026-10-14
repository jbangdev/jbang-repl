# jbang-repl (experimental)

A minimal Java REPL backed by the **public JShell API** and using **aesh-readline**, not JLine.

## Run

Requires **JBang on PATH** and **JDK 21+**.

```sh
jbang jbang_repl.java
```

From inside the REPL:

```text
java> 1 + 2
java> /deps add com.fasterxml.jackson.core:jackson-databind:2.19.0
java> import com.fasterxml.jackson.databind.ObjectMapper;
java> new ObjectMapper().readTree("{\"hello\":42}")
java> /vars
java> /deps list
java> /save scratch.java
java> /exit
```

## Install as a JBang command

```sh
jbang app install --name jbang-repl jbang_repl.java
jbang-repl
```

This is a **prototype**, not an upstream JBang command. Dependency resolution shells out to `jbang info classpath --deps-only --deps=...`; the JVM classpath updates are additive. It is not currently a complete replacement for JShell's frontend: no Java-specific Tab completion, snippet replay, Javadoc popup, or robust live output synchronization. `/save` records accepted source snippets, but does not yet rewrite implicit JShell expressions into a conventional executable Java class. `/clear` clears only an unfinished multiline input, not JShell session state.

Do not run untrusted Java code: snippets execute with the privileges of this process.
