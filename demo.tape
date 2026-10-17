Output demo.gif
Require jbang

Set Shell bash
Set FontSize 16
Set Width 1120
Set Height 720
Set Theme "Catppuccin Mocha"
Set TypingSpeed 55ms
Set Margin 16
Set MarginFill "#1e1e2e"
Set BorderRadius 10

Hide
Type "jbang src/dev/jbang/repl/repl.java"
Enter
Sleep 12s
Show

Type 'var greeting = "hello ".repeat(3)'
Enter
Sleep 1.6s

Type '$_ + "world"'
Enter
Sleep 1.8s

Type "String.format("
Sleep 600ms
Tab
Sleep 1.8s
Tab
Sleep 2.6s
Backspace 14
Sleep 500ms

Type "/deps "
Tab
Sleep 1.2s
Tab
Sleep 2.4s
Backspace 6
Sleep 500ms

Type "/exit"
Enter
Sleep 1.5s
