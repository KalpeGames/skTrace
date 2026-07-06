package dev.sktrace.command;

/**
 * Pixel widths for Minecraft's default chat font. Minecraft chat is not monospace (i is 2px, m is 6px,
 * a space is 4px), so padding by character count never aligns columns. This pads by PIXEL width instead:
 * we measure a cell's width, then append spaces (4px each) to reach a target pixel width. Alignment is
 * therefore accurate to within one space (3px), which reads as clean columns.
 *
 * Widths are the advance (glyph width + 1px spacing) of each character in the vanilla font, the same
 * table other plugins use for chat alignment.
 */
public final class FontWidth {

    private FontWidth() {
    }

    // Advance width (including the 1px gap that follows each glyph) for printable ASCII.
    private static final int DEFAULT = 6;
    private static final int[] WIDTHS = new int[128];

    static {
        for (int i = 0; i < 128; i++) {
            WIDTHS[i] = DEFAULT;
        }
        put(' ', 4);
        put('!', 2);
        put('"', 5);
        put('\'', 3);
        put('(', 5);
        put(')', 5);
        put('*', 5);
        put('+', 6);
        put(',', 2);
        put('-', 6);
        put('.', 2);
        put(':', 2);
        put(';', 2);
        put('<', 5);
        put('>', 5);
        put('@', 7);
        put('I', 4);
        put('[', 4);
        put(']', 4);
        put('f', 5);
        put('i', 2);
        put('k', 5);
        put('l', 3);
        put('t', 4);
        put('{', 5);
        put('|', 2);
        put('}', 5);
        // digits are width 6 (the default), as are most letters.
    }

    private static void put(char c, int w) {
        WIDTHS[c] = w;
    }

    /** Pixel width of a single character in the default font. */
    public static int charWidth(char c) {
        if (c < 128) {
            return WIDTHS[c];
        }
        // Non ASCII (e.g. the warning glyph) is rare in aligned columns; assume default advance.
        return DEFAULT;
    }

    /** Pixel width of a plain (no formatting codes) string. */
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += charWidth(s.charAt(i));
        }
        return w;
    }

    private static final int SPACE = 4;

    /**
     * Pads {@code s} on the right with spaces so its pixel width reaches at least {@code targetPx}.
     * Rounds up to the nearest space (4px), so columns align to within one space.
     */
    public static String padRightPx(String s, int targetPx) {
        int deficit = targetPx - width(s);
        if (deficit <= 0) {
            return s;
        }
        int spaces = (deficit + SPACE - 1) / SPACE;
        return s + " ".repeat(spaces);
    }

    /** Left-pads (right-aligns) {@code s} to at least {@code targetPx} pixels. */
    public static String padLeftPx(String s, int targetPx) {
        int deficit = targetPx - width(s);
        if (deficit <= 0) {
            return s;
        }
        int spaces = (deficit + SPACE - 1) / SPACE;
        return " ".repeat(spaces) + s;
    }

    /** The max pixel width across a set of plain strings, for sizing a column. */
    public static int maxWidth(String[] cells) {
        int m = 0;
        for (String c : cells) {
            if (c != null) {
                int w = width(c);
                if (w > m) {
                    m = w;
                }
            }
        }
        return m;
    }
}
