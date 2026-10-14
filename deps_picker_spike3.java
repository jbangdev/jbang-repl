///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//REPOS central
//REPOS sonatype-snapshots=https://central.sonatype.com/repository/maven-snapshots/
//DEPS org.aesh:readline:3.14
//DEPS org.aesh:terminal-tty:3.14
//DEPS dev.tamboui:tamboui-widgets:0.7.0-SNAPSHOT
//DEPS dev.tamboui:tamboui-tui:0.7.0-SNAPSHOT
//DEPS dev.tamboui:tamboui-aesh-backend:0.7.0-SNAPSHOT
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED

import org.aesh.readline.Readline;
import org.aesh.readline.ReadlineBuilder;
import org.aesh.terminal.tty.TerminalConnection;

import dev.tamboui.backend.aesh.AeshBackend;
import dev.tamboui.terminal.AbstractBackend;
import dev.tamboui.layout.Size;
import dev.tamboui.layout.Position;
import dev.tamboui.tui.InlineTuiRunner;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.style.Style;
import dev.tamboui.style.Color;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Spike 3: single-reader design.
 * One AeshBackend owns the only connection reader. aesh Readline and the picker
 * share it by swapping the connection's stdinHandler. No second reader => no crash.
 */
public class deps_picker_spike3 {
    static TerminalConnection conn;
    static Readline readline;
    static AeshBackend out;                 // THE single reader + output path
    static final CountDownLatch done = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        conn = new TerminalConnection();
        out = new AeshBackend(conn);        // starts the single reader (openNonBlocking)
        readline = ReadlineBuilder.builder().build();
        loop();
        done.await();                       // keep alive; no openBlocking here
        System.out.println("exited cleanly");
    }

    static void loop() {
        // readline sets conn's stdinHandler to its decoder; out's reader feeds it.
        readline.readline(conn, "query> ", line -> {
            if (line == null || line.strip().equals("/exit")) { conn.close(); done.countDown(); return; }
            List<String> candidates = List.of(
                    line + ":artifact-a:1.0",
                    line + ":artifact-b:2.0",
                    line + ":artifact-c:3.0");
            // Run the picker on a worker thread and RETURN now, so the reader thread
            // (this thread) is free to keep decoding input and feeding the picker queue.
            startPickerAsync(candidates);
        });
    }

    static void startPickerAsync(List<String> candidates) {
        BlockingQueue<Integer> q = new LinkedBlockingQueue<>();
        conn.setStdinHandler(ints -> { for (int cp : ints) q.offer(cp); });
        Thread worker = new Thread(() -> {
            String picked;
            try {
                picked = runPicker(candidates, q);
            } catch (Exception e) {
                conn.write("picker error: " + e + "\r\n");
                loop();
                return;
            }
            conn.write("picked: " + (picked == null ? "(cancelled)" : picked) + "\r\n");
            loop(); // re-arms readline, which re-points stdinHandler to aesh's decoder
        }, "deps-picker");
        worker.setDaemon(true);
        worker.start();
    }

    static String runPicker(List<String> candidates, BlockingQueue<Integer> q) throws Exception {
        String[] result = {null};
        int height = Math.min(candidates.size(), 8);
        QueueBackend qb = new QueueBackend(out, q);
        InlineTuiRunner runner = InlineTuiRunner.create(qb, InlineTuiConfig.defaults(height));
        ListState state = new ListState();
        state.select(0);
        ListWidget list = ListWidget.builder()
                .items(candidates.toArray(new String[0]))
                .highlightSymbol("> ")
                .highlightStyle(Style.EMPTY.fg(Color.BLACK).bg(Color.CYAN))
                .build();
        try {
            runner.run(
                (Event ev, InlineTuiRunner r) -> {
                    if (ev instanceof KeyEvent k) {
                        if (k.isDown()) { state.selectNext(candidates.size()); return true; }
                        if (k.isUp())   { state.selectPrevious(); return true; }
                        if (k.isKey(KeyCode.ENTER)) {
                            Integer sel = state.selected();
                            result[0] = candidates.get(sel == null ? 0 : sel);
                            r.quit(); return true;
                        }
                        if (k.isKey(KeyCode.ESCAPE) || k.isCtrlC()) { r.quit(); return true; }
                    }
                    return false;
                },
                (frame) -> frame.renderStatefulWidget(list, frame.area(), state)
            );
        } finally {
            runner.close();
        }
        return result[0];
    }

    /** Output delegates to the shared AeshBackend; input comes from our swappable queue. */
    static final class QueueBackend extends AbstractBackend {
        private final AeshBackend out;
        private final BlockingQueue<Integer> q;
        private int peeked = -2;
        QueueBackend(AeshBackend out, BlockingQueue<Integer> q) { this.out = out; this.q = q; }

        public void flush() throws IOException { out.flush(); }
        public void clear() throws IOException { out.clear(); }
        public Size size() throws IOException { return out.size(); }
        public void showCursor() throws IOException { out.showCursor(); }
        public void hideCursor() throws IOException { out.hideCursor(); }
        public Position getCursorPosition() throws IOException { return out.getCursorPosition(); }
        public void enterAlternateScreen() throws IOException { out.enterAlternateScreen(); }
        public void leaveAlternateScreen() throws IOException { out.leaveAlternateScreen(); }
        public void enableRawMode() throws IOException { out.enableRawMode(); }
        public void disableRawMode() throws IOException { out.disableRawMode(); }
        public void onResize(Runnable r) { out.onResize(r); }
        public void writeRaw(String s) throws IOException { out.writeRaw(s); }
        public void writeRaw(byte[] b) throws IOException { out.writeRaw(b); }
        public void writeRaw(CharSequence c) throws IOException { out.writeRaw(c.toString()); }
        public void moveCursorUp(int n) throws IOException { out.moveCursorUp(n); }
        public void moveCursorDown(int n) throws IOException { out.moveCursorDown(n); }
        public void moveCursorLeft(int n) throws IOException { out.moveCursorLeft(n); }
        public void moveCursorRight(int n) throws IOException { out.moveCursorRight(n); }
        public void eraseToEndOfLine() throws IOException { out.eraseToEndOfLine(); }
        public void carriageReturn() throws IOException { out.carriageReturn(); }
        public void insertLines(int n) throws IOException { out.insertLines(n); }
        public void deleteLines(int n) throws IOException { out.deleteLines(n); }
        public void scrollUp(int n) throws IOException { out.scrollUp(n); }
        public void scrollDown(int n) throws IOException { out.scrollDown(n); }
        public void beginSynchronizedUpdate() throws IOException { out.beginSynchronizedUpdate(); }
        public void endSynchronizedUpdate() throws IOException { out.endSynchronizedUpdate(); }

        public int read(int t) throws IOException { if (peeked != -2) { int v = peeked; peeked = -2; return v; } return poll(t); }
        public int peek(int t) throws IOException { if (peeked == -2) peeked = poll(t); return peeked; }
        private int poll(int t) {
            try {
                Integer c = t < 0 ? q.take() : t == 0 ? q.poll() : q.poll(t, TimeUnit.MILLISECONDS);
                return c == null ? -2 : c;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -2; }
        }
        public void close() { /* shared out is closed by the app, not here */ }
    }
}
