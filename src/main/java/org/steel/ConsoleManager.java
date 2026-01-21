package org.steel;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.tinylog.Logger;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class ConsoleManager {
    private static int consoleWidth;
    private static final int PROGRESS_BAR_MAX_WIDTH;
    private static Terminal terminal;

    static {
        Logger.info("init console manager");

        int tmpPBWidth;
        try (InputStream is = FileSync.class.getResourceAsStream("/ConsoleManager.properties")) {
            Properties prop = new Properties();
            prop.load(is);
            tmpPBWidth = Integer.parseInt(prop.getProperty("progressBar.maxWidth"));
        } catch (Exception e) {
            Logger.warn(e);
            tmpPBWidth = 100;
        }
        PROGRESS_BAR_MAX_WIDTH = tmpPBWidth;

        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
            terminal.puts(InfoCmp.Capability.clear_screen);
        } catch (IOException e) {
            Logger.error(e);
        }
    }

    public static void writeLine(String line) {
        if (line.length() > consoleWidth){
            System.out.println(makeConsoleRow(line));
        } else{
            System.out.println(line);
        }

        Logger.info(line);
    }

    public static void writeLine(){
        System.out.println();
    }

    public static void rewriteLines(String[] lines) {
        if (lines.length < 1)
            return;

        checkResize();

        terminal.puts(InfoCmp.Capability.cursor_address,
                Math.max(0, terminal.getCursorPosition(c -> {
                }).getY() - (lines.length - 1)),
                0);

        for (int i = 0; i < lines.length; i++) {
            lines[i] = makeConsoleRow(lines[i]);

            if (i == lines.length - 1)
                System.out.print(lines[i]);
            else
                System.out.println(lines[i]);
        }

        Logger.info(String.join("\n\t", lines));
    }

    public static void rewriteLinesWithProgress(String[] lines, int percentage) {
        if (lines.length < 1 || percentage < 0 || percentage > 100)
            return;

        checkResize();

        terminal.puts(InfoCmp.Capability.cursor_address,
                Math.max(0, terminal.getCursorPosition(c -> {
                }).getY() - lines.length),
                0);

        for (int i = 0; i < lines.length; i++) {
            lines[i] = makeConsoleRow(lines[i]);
            System.out.println(lines[i]);
        }

        Logger.info(String.join("\n\t", lines));

        System.out.print(getProgressBar(percentage));
    }

    private static String getProgressBar(int percentage) {
        String begining = "[";
        String ending = String.format("] %s%%", percentage);

        int length = Math.min(PROGRESS_BAR_MAX_WIDTH, consoleWidth);
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

    private static String makeConsoleRow(String str) {
        char[] newStr = new char[consoleWidth];
        for (int i = 0; i < consoleWidth; i++) {
            if (i < str.length())
                newStr[i] = (i >= consoleWidth - 3) && (str.length() > consoleWidth) ? '.' : str.charAt(i);
            else
                newStr[i] = ' ';
        }

        return new String(newStr);
    }

    private static void checkResize() {
        int width = terminal.getWidth();

        if (consoleWidth != width)
            consoleWidth = width;
    }
}
