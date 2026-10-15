package org.hurtorius.music.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * How far some work has got, drawn like the game's own loading bar (a light frame filling from the
 * left) with a line of text over it. It only shows; it takes no clicks.
 */
final class ProgressBar extends AbstractWidget {
    private static final int FRAME = 0xFFE0E0E0, FILL = 0xFF6FA34A, BACK = 0xC0000000;

    private final DoubleSupplier progress;
    private final Supplier<Component> label;

    ProgressBar(DoubleSupplier progress, Supplier<Component> label) {
        super(0, 0, 100, 20, Component.empty());
        this.progress = progress;
        this.label = label;
        this.active = false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        double p = Math.max(0, Math.min(1, progress.getAsDouble()));
        int x0 = getX(), y0 = getY(), x1 = getRight(), y1 = getBottom();
        g.fill(x0, y0, x1, y1, BACK);
        g.renderOutline(x0, y0, getWidth(), getHeight(), FRAME);
        int fill = (int) Math.round((getWidth() - 4) * p);
        if (fill > 0) g.fill(x0 + 2, y0 + 2, x0 + 2 + fill, y1 - 2, FILL);
        Font font = Minecraft.getInstance().font;
        String text = StatusLine.fit(font, label.get().getString(), getWidth() - 6);
        g.drawCenteredString(font, text, x0 + getWidth() / 2, y0 + (getHeight() - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, label.get());
    }
}
