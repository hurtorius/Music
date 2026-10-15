package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * One tab of the piano screen: vanilla widgets in rows that fill the panel's width. A tab lists its
 * rows (each with its own height and the share of the width every widget takes) and the base class
 * sizes and stacks them; when they are taller than the panel they go into vanilla's scrolling
 * layout. Tabs are rebuilt whenever the screen is (a resize, a change of what they show), so they
 * keep nothing that matters: lasting state lives in {@link PianoSession} and the preferences.
 */
abstract class ScreenTab extends GridLayoutTab {
    /** Height of a row holding one line of text. */
    static final int TEXT_ROW = 10;

    protected final PianoScreen screen;
    protected final ScreenLayout geometry;
    protected final Minecraft minecraft = Minecraft.getInstance();
    private final List<Row> rows = new ArrayList<>();
    private final List<ValueSlider> sliders = new ArrayList<>();
    private int used;

    /** A row: its height, the share of the width each widget takes (see {@link ScreenLayout#split}), and the widgets. */
    record Row(int height, float[] cells, AbstractWidget[] widgets) {}

    ScreenTab(Component title, PianoScreen screen) {
        super(title);
        this.screen = screen;
        this.geometry = screen.geometry();
    }

    /** Builds the tab's rows; called once by the screen right after construction. */
    final void build() {
        rows.clear();
        addRows();
        ScreenRectangle area = geometry.content();
        int spacing = geometry.spacing();
        int total = 0;
        for (Row row : rows) total += row.height();
        total += spacing * Math.max(0, rows.size() - 1);
        boolean scroll = total > area.height() && scrollable() && area.width() > 60;
        used = scroll ? area.height() : Math.min(total, area.height());
        int width = scroll ? area.width() - 20 : area.width();
        LinearLayout column = LinearLayout.vertical().spacing(spacing);
        for (Row row : rows) {
            LinearLayout line = LinearLayout.horizontal().spacing(spacing);
            int[] widths = ScreenLayout.split(width, spacing, row.cells());
            for (int i = 0; i < row.widgets().length; i++) {
                AbstractWidget widget = row.widgets()[i];
                widget.setWidth(Math.max(1, widths[i]));
                widget.setHeight(row.height());
                if (widget instanceof EditBox box) scrollForWidth(box);
                line.addChild(widget);
            }
            column.addChild(line);
        }
        if (scroll) {
            ScrollableLayout scrolling = new ScrollableLayout(minecraft, column, area.height());
            scrolling.setMinWidth(area.width());
            layout.addChild(scrolling, 0, 0);
        } else {
            layout.addChild(column, 0, 0);
        }
    }

    /**
     * An edit box is made 100 wide and given its text before its row sizes it, which scrolls a long
     * text for that width; the scroll stays when the box widens, hiding the text's start in a box
     * with room for all of it. Scrolling to the start and back to the cursor puts it right for the
     * real width (moving the cursor this way calls no responder).
     */
    private static void scrollForWidth(EditBox box) {
        int cursor = box.getCursorPosition();
        box.setCursorPosition(0);
        box.setHighlightPos(0);
        box.setCursorPosition(cursor);
        box.setHighlightPos(cursor);
    }

    /** How much of the panel's height the tab's rows take (all of it when they scroll). */
    final int usedHeight() {
        return used;
    }

    /** Adds this tab's rows with {@link #row}. */
    protected abstract void addRows();

    /** False for tabs whose lists scroll on their own and must not sit inside another scrolling area. */
    protected boolean scrollable() {
        return true;
    }

    /** Adds a row of widgets {@code height} high. */
    protected final void row(int height, float[] cells, AbstractWidget... widgets) {
        if (cells.length != widgets.length) throw new IllegalArgumentException("Each widget needs a cell");
        rows.add(new Row(height, cells, widgets));
    }

    /** Adds a row of standard height. */
    protected final void row(float[] cells, AbstractWidget... widgets) {
        row(geometry.rowHeight(), cells, widgets);
    }

    protected static float[] cells(float... cells) {
        return cells;
    }

    /** Equal shares for {@code n} widgets. */
    protected static float[] even(int n) {
        float[] cells = new float[n];
        Arrays.fill(cells, 1f);
        return cells;
    }

    @Override
    public void doLayout(ScreenRectangle area) {
        layout.arrangeElements();
        layout.setPosition(area.left(), area.top());
    }

    // ------------------------------------------------------------------ widget helpers

    protected Button button(Component label, Button.OnPress action) {
        return Button.builder(label, action).build();
    }

    protected Button button(Component label, @Nullable Component tooltip, Button.OnPress action) {
        return Button.builder(label, action).tooltip(tooltip == null ? null : Tooltip.create(tooltip)).build();
    }

    /** A control's tooltip, saying below it that only the owner or an operator changes it when {@code locked}. */
    protected static Component locked(MutableComponent tooltip, boolean locked) {
        return locked ? tooltip.append("\n").append(Component.translatable("music.screen.access.locked")) : tooltip;
    }

    /** A line of text that keeps the width its row gives it, whatever text it shows later. */
    protected Label text(Component text) {
        return new Label(text, minecraft.font);
    }

    /**
     * Widths in proportion to what each widget's label needs, for rows that would cut text short if
     * they shared the width evenly.
     */
    protected float[] natural(AbstractWidget... widgets) {
        float[] cells = new float[widgets.length];
        for (int i = 0; i < widgets.length; i++) cells[i] = minecraft.font.width(widgets[i].getMessage()) + 10;
        return cells;
    }

    /**
     * Vanilla's text widget sets its width to its text's width whenever the text changes, which
     * would move the widgets after it the next time the tab is laid out; this one keeps its cell.
     * It can also be given its words in shorter forms, for the cell to show the longest that fits.
     */
    static final class Label extends StringWidget {
        /** The words in full as last given to {@link #show}, which the tooltip reads while they are not shown so. */
        private @Nullable Component full;

        Label(Component text, Font font) {
            super(0, 0, 10, TEXT_ROW, text, font);
        }

        @Override
        public void setMessage(Component message) {
            int keep = width;
            super.setMessage(message);
            width = keep;
        }

        /**
         * Shows the first of {@code forms} (the words in full, then shorter) that fits the label's
         * width, or the last, cut short with an ellipsis; while the full words are not shown, they
         * read in a tooltip.
         */
        void show(Component... forms) {
            Font font = getFont();
            Component shown = forms[forms.length - 1];
            for (Component form : forms) {
                if (font.width(form) <= getWidth()) {
                    shown = form;
                    break;
                }
            }
            if (shown.equals(getMessage()) && forms[0].equals(full)) return;
            setMessage(shown);
            full = forms[0];
            boolean whole = shown == forms[0] && font.width(shown) <= getWidth();
            setTooltip(whole ? null : Tooltip.create(forms[0]));
        }
    }

    /** A slider remembered by the tab so it can settle and follow outside values. */
    protected ValueSlider slider(double min, double max, double step, double initial, DoubleFunction<Component> label,
                                 DoubleConsumer onChange, DoubleConsumer onCommit) {
        ValueSlider slider = new ValueSlider(100, geometry.rowHeight(), min, max, step, initial, label, onChange, onCommit);
        sliders.add(slider);
        return slider;
    }

    /** Settles sliders and brings widgets up to date with the piano and the session. Every tick. */
    void tick() {
        for (ValueSlider slider : sliders) slider.tick();
        refresh();
    }

    /** Brings widget values up to date; called every tick. */
    protected abstract void refresh();

    /** Updates what changes every frame (positions, beats); called before each frame. */
    void frame() {
    }
}
