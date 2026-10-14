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
import dev.tamboui.tui.InlineTuiRunner;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.style.Style;
import dev.tamboui.style.Color;

import java.util.*;

/** Spike 2: aesh readline -> inline picker (same connection) -> aesh readline. Tests the handoff. */
public class deps_picker_spike2 {
    static TerminalConnection conn;
    static Readline readline;

    public static void main(String[] args) throws Exception {
        conn = new TerminalConnection();
        readline = ReadlineBuilder.builder().build();
        loop();
        conn.openBlocking();
        System.out.println("exited cleanly");
    }

    static void loop() {
        readline.readline(conn, "query> ", line -> {
            if (line == null || line.strip().equals("/exit")) { conn.close(); return; }
            try {
                List<String> candidates = List.of(
                        line + ":artifact-a:1.0",
                        line + ":artifact-b:2.0",
                        line + ":artifact-c:3.0");
                String picked = pick(candidates);
                conn.write("picked: " + (picked == null ? "(cancelled)" : picked) + "\r\n");
            } catch (Exception e) {
                conn.write("picker error: " + e + "\r\n");
            }
            loop();
        });
    }

    static String pick(List<String> candidates) throws Exception {
        String[] result = {null};
        int height = Math.min(candidates.size(), 8);
        try (AeshBackend backend = new AeshBackend(conn)) { // non-owning: won't close conn
            InlineTuiRunner runner = InlineTuiRunner.create(backend, InlineTuiConfig.defaults(height));
            ListState state = new ListState();
            state.select(0);
            ListWidget list = ListWidget.builder()
                    .items(candidates.toArray(new String[0]))
                    .highlightSymbol("> ")
                    .highlightStyle(Style.EMPTY.fg(Color.BLACK).bg(Color.CYAN))
                    .build();
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
        }
        return result[0];
    }
}
