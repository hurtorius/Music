package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * Vanilla's selection list with one-line rows: a name on the left, maybe a credit in grey after it,
 * and details in grey on the right. Where a row does not fit in full it may have a shorter form (its
 * credit left out, shorter details or none); the rows of a group all take theirs together, so rows
 * alike read alike, and what still does not fit is cut short with an ellipsis. A row not shown in full
 * reads in full in a tooltip. A click selects a row, a double click activates it, and so does Enter
 * once the player has reached the list with Tab (on the piano screen Enter otherwise opens chat).
 * When the list is empty it says so; a hint (not a row: it cannot be selected) can also stand in grey
 * in the room under the rows. Positions follow the list wherever a layout puts it.
 *
 * @param <T> what each row stands for
 */
final class TextList<T> extends ObjectSelectionList<TextList.Row<T>> {
    static final int ROW_HEIGHT = 12;
    /** Room between a row's name and its details. */
    static final int DETAIL_GAP = 6;
    /** Between a name and its credit, and between details. */
    static final String SEPARATOR = " · ";
    /** Vanilla's room above the first row and below the last. */
    private static final int EDGE = 2;
    /** A row's letters, from the top of a capital to the baseline. */
    private static final int LETTERS = 7;
    private static final int DETAIL = 0xFF9A9A9A, EMPTY = 0xFF8A8A8A;

    private final Consumer<T> onSelect, onActivate;
    private Component empty = Component.empty();
    private Component hint = Component.empty();
    /** Whether a group takes its shorter form for any row it shows more of, not only for one it then shows whole. */
    private boolean namesFirst;
    /** The groups whose rows show their shorter form at the list's width. */
    private Set<Integer> shortened = Set.of();

    TextList(Minecraft minecraft, Consumer<T> onSelect, Consumer<T> onActivate) {
        super(minecraft, 100, 40, 0, ROW_HEIGHT);
        this.onSelect = onSelect;
        this.onActivate = onActivate;
        this.centerListVertically = false;
    }

    /** What the list says when it has no rows. */
    TextList<T> empty(Component text) {
        empty = text;
        return this;
    }

    /** A line in grey under the rows, or nothing for an empty component. */
    void hint(Component text) {
        hint = text;
    }

    /**
     * Makes the rows give up what their shorter form leaves out wherever it cuts a name, even one
     * still cut without it: for details shown elsewhere too, as the playlist's lengths are in the
     * library beside it.
     */
    TextList<T> namesFirst() {
        namesFirst = true;
        fitRows();
        return this;
    }

    /**
     * The height, at most {@code available}, at which no row is cut across: the rows whose letters end
     * in it show whole, and the next one's start at its bottom or below, so it is not drawn.
     */
    static int wholeRows(int available) {
        return Math.min(available, rowsShown(available) * ROW_HEIGHT + 2 * EDGE);
    }

    /** How many rows a list {@code height} high shows whole. */
    static int rowsShown(int height) {
        // Row k starts EDGE + ROW_HEIGHT * k below the top, and its letters 2 further down, 8 tall: they
        // end ROW_HEIGHT * (k + 1) below the top.
        return Math.max(0, height) / ROW_HEIGHT;
    }

    /**
     * The groups with a row that does not fit {@code room} in full but does in its shorter form: each
     * of their rows takes its shorter form. A row too long either way does not decide for the others,
     * unless {@code namesFirst}: then any row its shorter form shows more of decides.
     */
    static <T> Set<Integer> shortened(List<Row<T>> rows, int room, ToIntFunction<String> width, boolean namesFirst) {
        Set<Integer> groups = new HashSet<>();
        for (Row<T> row : rows) {
            if (groups.contains(row.group)) continue;
            int full = row.width(false, width), brief = row.width(true, width);
            if (full > room && (brief <= room || namesFirst && brief < full)) groups.add(row.group);
        }
        return groups;
    }

    /**
     * One row. {@code colour} is the name's colour (white for ordinary rows); {@code credit}, when not
     * empty, follows the name in grey; {@code shorter} stands in for the credit and {@code detail}
     * where a row of its {@code group} does not fit in full ("" for nothing).
     */
    static final class Row<T> extends ObjectSelectionList.Entry<Row<T>> {
        final T value;
        final String name;
        final String credit;
        final String detail;
        final String shorter;
        final int group;
        final int colour;
        private @Nullable TextList<T> owner;

        /** A row whose details always show. */
        Row(T value, String name, String detail, int colour) {
            this(value, name, "", detail, detail, 0, colour);
        }

        Row(T value, String name, String credit, String detail, String shorter, int group, int colour) {
            this.value = value;
            this.name = name;
            this.credit = credit;
            this.detail = detail;
            this.shorter = shorter;
            this.group = group;
            this.colour = colour;
        }

        /** How wide the row's text is in full, or in its shorter form. */
        int width(boolean brief, ToIntFunction<String> width) {
            String right = brief ? shorter : detail;
            return width.applyAsInt(brief || credit.isEmpty() ? name : name + SEPARATOR + credit)
                + (right.isEmpty() ? 0 : DETAIL_GAP + width.applyAsInt(right));
        }

        /** The row in full, as its tooltip reads. */
        String full() {
            StringBuilder text = new StringBuilder(name);
            if (!credit.isEmpty()) text.append(SEPARATOR).append(credit);
            if (!detail.isEmpty()) text.append(SEPARATOR).append(detail);
            return text.toString();
        }

        /** Where the row's letters start. */
        int textY(Font font) {
            return getContentYMiddle() - font.lineHeight / 2;
        }

        @Override
        public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getContentX() + 1, width = getContentWidth() - 2;
            int y = textY(font);
            boolean brief = owner != null && owner.shortened.contains(group);
            String credit = brief ? "" : this.credit, detail = brief ? shorter : this.detail;
            // The details give way to a long name down to half the row, and the name is cut to what is
            // left; the credit shows only whole, after a name shown whole.
            String named = credit.isEmpty() ? name : name + SEPARATOR + credit;
            int detailWidth = detail.isEmpty() ? 0 : Math.min(font.width(detail), Math.max(width / 2, width - font.width(named) - DETAIL_GAP));
            String shownDetail = detail.isEmpty() ? "" : StatusLine.fit(font, detail, detailWidth);
            int nameRoom = Math.max(0, width - detailWidth - (detailWidth > 0 ? DETAIL_GAP : 0));
            String shownName = StatusLine.fit(font, name, nameRoom);
            boolean creditShown = !credit.isEmpty() && shownName.equals(name) && font.width(named) <= nameRoom;
            g.drawString(font, shownName, x, y, colour);
            if (creditShown) g.drawString(font, SEPARATOR + credit, x + font.width(name), y, DETAIL);
            if (!shownDetail.isEmpty()) g.drawString(font, shownDetail, x + width - font.width(shownDetail), y, DETAIL);
            boolean whole = shownName.equals(name) && creditShown == !this.credit.isEmpty() && shownDetail.equals(this.detail);
            if (hovered && !whole) g.setTooltipForNextFrame(Component.literal(full()), mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (owner != null) {
                owner.setSelected(this);
                owner.onSelect.accept(value);
                if (doubleClick) owner.onActivate.accept(value);
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (owner != null && event.isConfirmation()) {
                owner.onActivate.accept(value);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select", name);
        }
    }

    /** Replaces the rows, keeping the scroll position, and selects the row whose value equals {@code selected}. */
    void setRows(List<Row<T>> rows, @Nullable T selected) {
        double scroll = scrollAmount();
        for (Row<T> row : rows) row.owner = this;
        replaceEntries(rows);
        Row<T> chosen = null;
        for (Row<T> row : rows) if (selected != null && Objects.equals(row.value, selected)) chosen = row;
        setSelected(chosen);
        setScrollAmount(scroll);
        fitRows();
    }

    /** Chooses, for the list's width, which groups of rows take their shorter form. */
    private void fitRows() {
        // A row's text runs one pixel in from its content, which is vanilla's two in from the row.
        shortened = shortened(children(), getRowWidth() - 6, Minecraft.getInstance().font::width, namesFirst);
    }

    /** The selected row's value, or null. */
    @Nullable T selectedValue() {
        Row<T> row = getSelected();
        return row == null ? null : row.value;
    }

    /** Selects the row for {@code value} (or nothing) and scrolls it into view. */
    void select(@Nullable T value) {
        Row<T> chosen = null;
        for (Row<T> row : children()) if (value != null && Objects.equals(row.value, value)) chosen = row;
        setSelected(chosen);
        if (chosen != null) centerScrollOn(chosen);
    }

    /**
     * Selects the row for {@code value} (or nothing), scrolling the list only as far as it takes to
     * show that row whole, as vanilla does for a row it selects: for a row that moved a step.
     */
    void reveal(@Nullable T value) {
        Row<T> chosen = null;
        for (Row<T> row : children()) if (value != null && Objects.equals(row.value, value)) chosen = row;
        setSelected(chosen);
    }

    int rows() {
        return children().size();
    }

    @Override
    public int getRowLeft() {
        return getX() + 2;
    }

    @Override
    public int getRowWidth() {
        return Math.max(10, getWidth() - 10);
    }

    @Override
    protected int scrollBarX() {
        return getRight() - 6;
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        setScrollAmount(scrollAmount());
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        setScrollAmount(scrollAmount());
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        setScrollAmount(scrollAmount());
        fitRows();
    }

    /**
     * Draws the rows whose letters are in the list. A row whose letters are all outside is left out,
     * though its box reaches in: the accent of an é in the row below the last would show as a stray
     * speck under it.
     */
    @Override
    protected void renderListItems(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        for (Row<T> row : children()) {
            int top = row.textY(font);
            if (top < getBottom() && top + LETTERS > getY()) renderItem(g, mouseX, mouseY, partialTick, row);
        }
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(g, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        if (children().isEmpty() && getHeight() >= 12) {
            String text = StatusLine.fit(font, empty.getString(), getWidth() - 8);
            g.drawCenteredString(font, text, getX() + getWidth() / 2, getY() + (getHeight() - font.lineHeight) / 2, EMPTY);
        } else if (!hint.getString().isEmpty()) {
            // In the free room under the last row, if there is a line of it.
            int top = getNextY();
            int room = getBottom() - top;
            if (room >= font.lineHeight + 4) {
                String text = StatusLine.fit(font, hint.getString(), getWidth() - 8);
                g.drawCenteredString(font, text, getX() + getWidth() / 2, top + (room - font.lineHeight) / 2, EMPTY);
            }
        }
    }
}
