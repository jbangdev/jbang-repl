///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../src/repl.java

import jdk.jshell.JShell;
public class HighlighterSmokeTest {
    public static void main(String[] args) {
        try (JShell shell = JShell.create()) {
            var h = new ReplSyntaxHighlighter(shell);
            for (String code : new String[]{"var message = \"hello\";", "/deps add org.example:foo:1.0", "/* hello */ int i = 1;", "String emoji = \"😃\";", "String text = \"\"\"hello\"\"\";"}) {
                var highlighted = h.highlight(code);
                if (!highlighted.replaceAll("\\u001B\\[[0-9;]*m", "").equals(code))
                    throw new AssertionError("Content changed: " + code);
                System.out.println(highlighted);
            }
        }
    }
}
