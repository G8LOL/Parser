package dev.g8lol.parser;

/**
 * @author G8LOL
 * @since 11/11/2022
 */
public class PrintUtil {

    public static void print(String message) {
        System.out.println(PaletteUtil.ANSI_RED + "[" + Main.NAME + "] >> " + PaletteUtil.ANSI_YELLOW + message + PaletteUtil.ANSI_RESET);
    }

    public static void printQuestion(String message) {
        System.out.print(PaletteUtil.ANSI_BLUE + message + PaletteUtil.ANSI_RESET);
    }

}
