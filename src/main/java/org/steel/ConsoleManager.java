package org.steel;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;

public class ConsoleManager {
    private static int _consoleWidth;
    private static int _progressBarMaxWidth = 100;
    private static Terminal terminal;

    static{
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
            terminal.puts(InfoCmp.Capability.clear_screen);
        } catch (IOException e) { }
    }

    public static void writeLine(String string){
        System.out.println(string);
    }

    public static void rewriteLines(String[] lines)
    {
        if (lines.length < 1)
            return;

        checkResize();

        try
        {
            terminal.puts(InfoCmp.Capability.cursor_address,
                    Math.max(0, terminal.getCursorPosition(c -> {}).getY() - (lines.length - 1)),
                    0);

            for (int i = 0; i < lines.length; i++)
            {
                lines[i] = makeConsoleRow(lines[i]);

                if (i == lines.length - 1)
                    System.out.print(lines[i]);
                else
                    System.out.println(lines[i]);
            }
        }
        catch (Exception e){
            System.out.println(e.getLocalizedMessage());
        }
    }

    public static void rewriteLinesWithProgress(String[] lines, int percentage)
    {
        if (lines.length < 1 || percentage < 0 || percentage > 100)
            return;

        checkResize();

        try
        {
            terminal.puts(InfoCmp.Capability.cursor_address,
                    Math.max(0, terminal.getCursorPosition(c -> {}).getY() - lines.length),
                    0);

            for (int i = 0; i < lines.length; i++)
            {
                lines[i] = makeConsoleRow(lines[i]);
                System.out.println(lines[i]);
            }

            System.out.print(getProgressBar(percentage));
        }
        catch (Exception e) { }
    }

    private static String getProgressBar(int percentage)
    {
        String begining = "[";
        String ending = String.format("] %s%%", percentage);

        int length = Math.min(_progressBarMaxWidth, _consoleWidth);
        int pbLength = length - begining.length() - ending.length();

        StringBuilder result = new StringBuilder();
        result.append(begining);

        int filledCells = (pbLength * percentage) / 100;
        for (int i = 0; i < filledCells; i++)
            result.append('#');
        for (int i = 0; i < pbLength - filledCells; i++)
            result.append('-');

        result.append(ending);

        return result.toString();
    }

    private static String makeConsoleRow(String str)
    {
        char[] newStr = new char[_consoleWidth];
        for (int i = 0; i < _consoleWidth; i++)
        {
            if (i < str.length())
                newStr[i] = (i >= _consoleWidth - 3) && (str.length() > _consoleWidth) ? '.' : str.charAt(i);
            else
                newStr[i] = ' ';
        }

        return new String(newStr);
    }

    private static void checkResize()
    {
        int width = terminal.getWidth();

        if (_consoleWidth != width)
            _consoleWidth = width;
    }
}
