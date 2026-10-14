# Proposed aesh-readline upstream API for live syntax highlighting

Target: `aeshell/aesh-readline` 3.14, source inspected on 2026-10-14.

## Why upstream?

`AeshConsoleBuffer.drawLine()` invokes `Buffer.print()`, while `AeshConsoleBuffer.writeChar()` invokes `Buffer.insert()`. Both operate directly on raw code points and optimized terminal deltas. `ReadlineRequest` exposes a `CursorListener`, **not** a redraw/highlighter callback. Applications cannot safely repaint aesh's editable buffer without breaking navigation and wrapped-line cursors.

## Minimal public API proposal

```java
@FunctionalInterface
public interface SyntaxHighlighter {
    // String is the unstyled, current editable buffer.
    // Return ANSI styled text with identical visible characters and layout.
    String highlight(String input);
}
```

Or better, use structured style spans rather than ANSI text to allow renderer-side invariant validation and color adaptation.

Expose `ReadlineRequest.Builder.syntaxHighlighter(SyntaxHighlighter)`, propagate into `Readline.AeshInputProcessor`, then `AeshConsoleBuffer`, and lastly `Buffer`.

### Required redraw invariants

1. Highlighting applies exclusively to displayed **buffer characters**, never stored characters, prompts, or ghost text.
2. When a highlighter is configured and syntax may change (e.g. adding a closing quote), **force full-line rendering**, bypassing delta insertion/deletion optimizations. Do not use `Buffer.insert()`'s direct-stdout delta paths for colored input.
3. Preserve logical cursor based on Unicode code points and terminal display cell width, independent of SGR escape lengths.
4. Apply highlights across all editable lines; never include ANSI escape length in wrap calculations.
5. Reset styles before ghost-text and right prompts are rendered.
6. Disable on masked/password input, and gracefully fall back to plain text when no ANSI color support.
7. Resizing, search-history recall, undo/redo, Vi mode, composition and paste must all recompute styles.
8. Keep terminal mutations within aesh's existing terminal write/locking lifecycle; callbacks must be fast and must not write to connection directly.

### Render change location

`readline/src/main/java/org/aesh/readline/Buffer.java`, `moveCursorToStartAndPrint(...)`, currently appends `getLine()` for unmasked buffers. That is the canonical place to add colored rendered text. The `printInsertedData(...)` and `printDeletedData(...)` fast paths must also be bypassed or adapted for correct recoloring. Prefer representing styling as zero-width SGR transitions in `IntArrayBuilder`; do not mutate the stored `line` code points.

### Tests needed upstream

- Type `"` then another quote, verify earlier characters recolor
- Insert/delete in middle of input, verify cursor and colors
- Multiline editing and wrapped prompts
- Tab completion, history search, Ctrl-L, undo, Vi editing
- Emoji/combining glyphs, CJK width, ANSI disabled, resize
- Slash command detection only at first character of fresh input

## Connect to JBang REPL

With such a highlighter extension, register a callback equivalent to:

```java
ReadlineRequest.builder()
    .connection(connection)
    .prompt(new Prompt("java> "))
    .requestHandler(this::accept)
    .syntaxHighlighter(syntaxHighlighter::highlight)
    .build();
```

The `.syntaxHighlighter(...)` method above is **proposed**, not present in aesh 3.14. This repository does not claim that live highlighting works yet.

## Interim workaround in 3.14: CursorListener + Line.colorize

aesh 3.14 already ships a partial path that needs no upstream change, wired in `repl.java` (`colorize(Line)`):

```java
ReadlineRequest.builder()
    .connection(connection)
    .prompt("java> ")
    .completions(completions)
    .cursorListener(this::colorize)   // CursorListener.moved(Line)
    .requestHandler(this::accept)
    .build();

private void colorize(Line line) {
    int[] styles = highlighter.styles(line.toString());
    var tx = line.newCursorTransactionBuilder();
    for (int i = 0; i < styles.length; i++)
        if (styles[i] != 0) tx.colorize(i, colorFor(styles[i]), Color.DEFAULT, bright);
    tx.build().run();
}
```

`Line.CursorTransactionBuilder.colorize(index, fg, bg, bright)` recolors one buffer character at an absolute index (`Line$ColorizeAction`: move to index, rewrite the char with a `TerminalColor`, move back). The transaction saves and restores the cursor, so Aesh's internal cursor/content state is untouched.

### Limitations (why this is not the real fix)

1. **No per-keystroke trigger.** `CursorListener.moved(Line)` is only invoked from `AeshConsoleBuffer.moveCursor(...)`. Character insertion goes `writeChar -> Buffer.insert(...)` (a direct terminal delta) and never calls `moveCursor`, so typing does **not** fire the listener. Coloring only repaints on cursor movement — arrow keys, Home/End, and on Enter (accept moves the cursor). A newly typed character stays uncolored until the next cursor move.
2. **8-color palette only.** `org.aesh.terminal.formatting.Color` is an enum (BLACK/RED/GREEN/YELLOW/BLUE/MAGENTA/CYAN/WHITE/DEFAULT), with `bright` mapped to BOLD. No 256-color or truecolor; the ANSI in `ReplSyntaxHighlighter` cannot be passed through directly, it must be mapped to the enum.
3. **Per-character writes.** Each styled char is an independent move+write action; fine for short REPL lines, not for large buffers.
4. **Fragile across the redraw invariants above** (wrapped lines, history recall, undo, resize) — those are exactly why the proposed `SyntaxHighlighter` hook should live inside `Buffer.moveCursorToStartAndPrint(...)` instead.

The real fix remains the upstream `SyntaxHighlighter` API. This workaround is a usable approximation that colorizes on cursor movement and on Enter.
