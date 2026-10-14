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

/** Spike 1: standalone inline picker over an aesh-backed TamboUI runner. */
public class deps_picker_spike {
    public static void main(String[] args) throws Exception {
        List<String> candidates = List.of(
                "com.fasterxml.jackson.core:jackson-databind:2.19.0",
                "tools.jackson.core:jackson-databind:3.0.0-rc4",
                "io.github.errantfiddle:jackson-databind:2.16.0",
                "org.openapitools:jackson-databind-nullable:0.2.6",
                "com.jwebmp.jackson.core:jackson-databind:0.66.0.1");

        String picked = pick(candidates);
        System.out.println();
        System.out.println(picked == null ? "cancelled" : "picked: " + picked);
    }

    static String pick(List<String> candidates) throws Exception {
        String[] result = {null};
        int height = Math.min(candidates.size(), 8);
        try (AeshBackend backend = new AeshBackend()) { // owns its own TerminalConnection
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
                            r.quit();
                            return true;
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
