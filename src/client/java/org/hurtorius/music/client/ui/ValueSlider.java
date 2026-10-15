package org.hurtorius.music.client.ui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Vanilla's slider over a number range, snapped to a step. While the player drags it, every new
 * number goes to {@code onChange}; when it settles (the mouse is released, or a moment after the
 * last change made some other way) the final number goes to {@code onCommit}. Once clicked, the left
 * and right arrow keys move it exactly one step (vanilla moves it a pixel's worth, which is less than
 * a step on most of these sliders and so would do nothing). {@link #sync} moves it to a value from
 * elsewhere (the server, another player) unless the player is using it.
 */
class ValueSlider extends AbstractSliderButton {
    /** A change not made by dragging counts as settled after this long. */
    static final long SETTLE_NANOS = 300_000_000L;

    private final double min, max, step;
    private final DoubleFunction<Component> label;
    private final DoubleConsumer onChange, onCommit;
    private double number;
    private boolean held, unsettled;
    private long changedAt;

    ValueSlider(int width, int height, double min, double max, double step, double initial, DoubleFunction<Component> label,
                DoubleConsumer onChange, DoubleConsumer onCommit) {
        super(0, 0, width, height, Component.empty(), 0);
        this.min = min;
        this.max = Math.max(min + step, max);
        this.step = step;
        this.label = label;
        this.onChange = onChange;
        this.onCommit = onCommit;
        this.number = snap(initial);
        this.value = fraction(number);
        updateMessage();
    }

    /** The number the slider shows. */
    double number() {
        return number;
    }

    private double snap(double v) {
        double clamped = Math.max(min, Math.min(max, v));
        return Math.max(min, Math.min(max, min + Math.round((clamped - min) / step) * step));
    }

    private double fraction(double n) {
        return (n - min) / (max - min);
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(number));
    }

    @Override
    protected void applyValue() {
        double next = snap(min + value * (max - min));
        if (next == number) return;
        number = next;
        unsettled = true;
        changedAt = System.nanoTime();
        onChange.accept(number);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        held = active;
        super.onClick(event, doubleClick);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        if (!held) return;
        held = false;
        settle();
    }

    /** The arrow keys move a slider the player clicked one step; Enter and Space keep vanilla's meaning. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (canChangeValue && (event.isLeft() || event.isRight())) {
            step(event.isLeft() ? -1 : 1);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Moves the number one step down ({@code direction} -1) or up (+1), within the range; it settles a moment later. */
    void step(int direction) {
        if (!active) return;
        double next = snap(number + Integer.signum(direction) * step);
        if (next == number) return;
        number = next;
        value = fraction(next);
        unsettled = true;
        changedAt = System.nanoTime();
        onChange.accept(number);
        updateMessage();
    }

    /** Puts the handle on the snapped number after the mouse moved it in between steps. */
    @Override
    protected void setValue(double v) {
        super.setValue(v);
        value = fraction(number);
    }

    private void settle() {
        value = fraction(number);
        if (!unsettled) return;
        unsettled = false;
        onCommit.accept(number);
    }

    /** True while the player holds the handle or a change has not settled yet. */
    boolean busy() {
        return held || unsettled;
    }

    /** Commits a change that was not made by dragging once it has rested a moment. Every tick. */
    void tick() {
        if (unsettled && !held && System.nanoTime() - changedAt > SETTLE_NANOS) settle();
    }

    /** Shows {@code outside} unless the player is using the slider. */
    void sync(double outside) {
        if (busy()) return;
        double n = snap(outside);
        if (n == number) return;
        number = n;
        value = fraction(n);
        updateMessage();
    }

    /** Updates the label (for labels that show more than the number, like a song position). */
    void relabel() {
        updateMessage();
    }
}
