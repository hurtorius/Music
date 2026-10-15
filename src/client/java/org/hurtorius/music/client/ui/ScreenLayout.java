package org.hurtorius.music.client.ui;

import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * Where the parts of the piano screen go for a GUI size, top to bottom: the tab bar, the tab panel
 * (its widgets in {@code content} and a one-line {@code status} at its foot), the sheet strip, and
 * the keyboard along the bottom edge. The strip sits right on top of the keyboard and has the same
 * width, so falling notes line up with their keys. The panel never grows wider than
 * {@link #PANEL_MAX_WIDTH}, leaving the world visible beside it on wide screens; when hidden it
 * takes no room at all. Widget rows are 20 high when six of them fit, else 16. GUI units, pure.
 *
 * @param panel     translucent panel behind the tab's widgets (zero height while hidden)
 * @param content   where the current tab lays out its widgets
 * @param status    one line of messages and hints
 * @param strip     sheet music or falling notes (zero height in very small windows)
 * @param keyboard  the 88 keys
 * @param rowHeight height of buttons, sliders and edit boxes
 * @param spacing   gap between rows and between widgets in a row
 */
public record ScreenLayout(int width, int height, ScreenRectangle panel, ScreenRectangle content, ScreenRectangle status,
                           ScreenRectangle strip, ScreenRectangle keyboard, int rowHeight, int spacing) {
    /** Height of vanilla's tab navigation bar. */
    public static final int TAB_BAR = 24;
    public static final int PANEL_MAX_WIDTH = 520;
    public static final int PADDING = 4;
    public static final int STATUS_HEIGHT = 10;
    /** The strip is left out below this GUI height, so the panel keeps room to work. */
    static final int STRIP_MIN_SCREEN = 200;
    static final int TALL_ROW = 20, SHORT_ROW = 16;

    /** The layout for a screen of {@code width} x {@code height} GUI units. */
    public static ScreenLayout compute(int width, int height, boolean panelHidden) {
        int w = Math.max(1, width), h = Math.max(1, height);
        int margin = w >= 400 ? 6 : w >= 40 ? 4 : 0;
        int bottomGap = h >= 60 ? 2 : 0;
        int keyboardHeight = Math.min(h - bottomGap, clamp(Math.round(h * 0.17f), 22, 72));
        int stripHeight = h < STRIP_MIN_SCREEN ? 0 : clamp(Math.round(h * 0.14f), 24, 84);
        int fullWidth = Math.max(0, w - 2 * margin);
        ScreenRectangle keyboard = new ScreenRectangle(margin, h - bottomGap - keyboardHeight, fullWidth, keyboardHeight);
        ScreenRectangle strip = new ScreenRectangle(margin, Math.max(0, keyboard.top() - stripHeight), fullWidth, stripHeight);
        int panelTop = TAB_BAR + 2;
        int panelBottom = Math.max(panelTop, strip.top() - 3);
        int panelWidth = Math.min(fullWidth, PANEL_MAX_WIDTH);
        int panelX = (w - panelWidth) / 2;
        if (panelHidden) {
            ScreenRectangle status = new ScreenRectangle(panelX, Math.max(panelTop, panelBottom - STATUS_HEIGHT), panelWidth, STATUS_HEIGHT);
            ScreenRectangle none = new ScreenRectangle(panelX, panelTop, panelWidth, 0);
            return new ScreenLayout(w, h, none, none, status, strip, keyboard, SHORT_ROW, 3);
        }
        ScreenRectangle panel = new ScreenRectangle(panelX, panelTop, panelWidth, panelBottom - panelTop);
        int inner = Math.max(0, panel.height() - 2 * PADDING - STATUS_HEIGHT - 1);
        int rowHeight = inner >= 6 * TALL_ROW + 5 * 4 ? TALL_ROW : SHORT_ROW;
        int spacing = rowHeight == TALL_ROW ? 4 : 3;
        int side = Math.min(PADDING, panelWidth / 2);
        ScreenRectangle content = new ScreenRectangle(panelX + side, panelTop + PADDING, panelWidth - 2 * side, inner);
        ScreenRectangle status = new ScreenRectangle(content.left(), content.bottom() + 1, content.width(), STATUS_HEIGHT);
        return new ScreenLayout(w, h, panel, content, status, strip, keyboard, rowHeight, spacing);
    }

    /**
     * This layout with the panel cut down to what a tab uses: {@code used} units of content, then
     * the status line, so the world shows below short tabs. A hidden panel stays as it is.
     */
    public ScreenLayout fitTo(int used) {
        if (panel.height() == 0 || used >= content.height()) return this;
        int fitted = Math.max(0, used);
        ScreenRectangle c = new ScreenRectangle(content.left(), content.top(), content.width(), fitted);
        ScreenRectangle s = new ScreenRectangle(status.left(), c.bottom() + 1, status.width(), STATUS_HEIGHT);
        ScreenRectangle p = new ScreenRectangle(panel.left(), panel.top(), panel.width(), s.bottom() + PADDING - panel.top());
        return new ScreenLayout(width, height, p, c, s, strip, keyboard, rowHeight, spacing);
    }

    /** True when there is a sheet strip at this size. */
    public boolean hasStrip() {
        return strip.height() > 0;
    }

    /**
     * Splits {@code total} into cells separated by {@code spacing}. A negative entry is a fixed
     * width ({@code -20} is 20 wide); positive entries share what is left in proportion. The widths
     * always add up to exactly {@code total} minus the gaps, and none is negative.
     */
    public static int[] split(int total, int spacing, float... cells) {
        int n = cells.length;
        int[] widths = new int[n];
        if (n == 0) return widths;
        int free = Math.max(0, total - spacing * (n - 1));
        float weights = 0;
        for (int i = 0; i < n; i++) {
            if (cells[i] < 0) {
                widths[i] = Math.min(free, Math.round(-cells[i]));
                free -= widths[i];
            } else {
                weights += cells[i];
            }
        }
        if (weights <= 0) return widths;
        int last = -1;
        for (int i = 0; i < n; i++) if (cells[i] >= 0) last = i;
        float done = 0;
        int placed = 0;
        for (int i = 0; i < n; i++) {
            if (cells[i] < 0) continue;
            done += cells[i];
            int end = i == last ? free : Math.min(free, Math.round(free * (done / weights)));
            widths[i] = Math.max(0, end - placed);
            placed = Math.max(placed, end);
        }
        return widths;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
