package org.hurtorius.music.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.hurtorius.music.client.ClientPrefs;
import org.hurtorius.music.client.play.Pianos;
import org.hurtorius.music.client.state.PianoClientState;
import org.hurtorius.music.client.state.PianoRuntime;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The piano's 88 keys along the bottom of the screen, drawn like Minecraft's own buttons: flat
 * colours, a light top edge, a dark front lip and 1-pixel black outlines. Keys go down whenever the
 * piano's keys do (the player's own playing, other players, the piano playing by itself), keys that
 * transposition pushes off the piano are greyed out, every C is labelled (see {@link OctaveNames};
 * a C whose line holds its computer key gets a mark at its front instead, middle C a darker one) and
 * so is each computer key that plays a note, with the key names of the player's own layout.
 *
 * <p>The mouse plays it: a click strikes the key under it, softly near the far end of the key and
 * loudly at its front; dragging glides from key to key; the note ends when the button comes up or
 * the mouse leaves the keyboard. Drawing happens in device pixels so all 52 white keys come out
 * evenly at every GUI scale.</p>
 */
final class KeyboardWidget extends AbstractWidget {
    private static final int OUTLINE = 0xFF000000;
    private static final int WHITE = 0xFFE8E8E8, WHITE_TOP = 0xFFFFFFFF, WHITE_LIP = 0xFF9C9C9C;
    private static final int WHITE_DOWN = 0xFFA9BEDF, WHITE_DIM = 0xFF8E8E8E;
    private static final int BLACK = 0xFF2C2C2C, BLACK_TOP = 0xFF565656, BLACK_LIP = 0xFF4A4A4A;
    private static final int BLACK_DOWN = 0xFF46628F, BLACK_DIM = 0xFF151515;
    private static final int C_LABEL = 0xFF707070, KEY_LABEL_ON_WHITE = 0xFF2A3F78, KEY_LABEL_ON_BLACK = 0xFFE0E0E0;
    /** Middle C (C4), where a beginner's right hand goes. */
    private static final int MIDDLE_C = 39;

    private final BlockPos piano;
    private final PlayingInput input;
    private @Nullable KeyboardLayout layout;
    private int layoutScale;

    KeyboardWidget(BlockPos piano, PlayingInput input) {
        super(0, 0, 0, 0, Component.translatable("music.screen.keyboard"));
        this.piano = piano;
        this.input = input;
    }

    private static int guiScale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** The keys in device pixels for the widget's current place and the current GUI scale. */
    KeyboardLayout layout() {
        int scale = guiScale();
        KeyboardLayout k = layout;
        if (k == null || layoutScale != scale || k.x() != getX() * scale || k.y() != getY() * scale
            || k.width() != getWidth() * scale || k.height() != getHeight() * scale) {
            k = new KeyboardLayout(getX() * scale, getY() * scale, getWidth() * scale, getHeight() * scale);
            layout = k;
            layoutScale = scale;
        }
        return k;
    }

    /** How far down the piano shows {@code key} now, 0 (up) .. 1 (down). */
    float depth(int key) {
        PianoRuntime runtime = PianoClientState.find(piano);
        return runtime == null ? 0f : runtime.keyDepth(key, System.nanoTime());
    }

    /** Centre of a key in GUI units, for clicking it ({@code along} 0 = far end, 1 = front edge). */
    double[] point(int key, double along) {
        KeyboardLayout k = layout();
        double scale = layoutScale;
        double y = k.y() + (k.bottom(key) - k.y()) * Math.max(0.02, Math.min(0.98, along));
        return new double[]{k.centreX(key) / scale, y / scale};
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        KeyboardLayout k = layout();
        int scale = layoutScale;
        int edge = Math.max(1, scale / 2);
        int transpose = Pianos.settings(piano).transpose();
        long now = System.nanoTime();
        PianoRuntime runtime = PianoClientState.find(piano);

        g.pose().pushMatrix();
        g.pose().scale(1f / scale, 1f / scale);
        g.fill(k.x() - scale, k.y() - scale, k.x() + k.width() + scale, k.y() + k.height() + scale, OUTLINE);
        for (int key = 0; key < KeyboardLayout.KEYS; key++) {
            if (KeyboardLayout.isBlack(key)) continue;
            float d = runtime == null ? 0f : runtime.keyDepth(key, now);
            boolean dim = key + transpose < 0 || key + transpose >= KeyboardLayout.KEYS;
            drawWhite(g, k, key, d, dim, scale, edge);
        }
        for (int key = 0; key < KeyboardLayout.KEYS; key++) {
            if (!KeyboardLayout.isBlack(key)) continue;
            float d = runtime == null ? 0f : runtime.keyDepth(key, now);
            boolean dim = key + transpose < 0 || key + transpose >= KeyboardLayout.KEYS;
            drawBlack(g, k, key, d, dim, scale, edge);
        }
        g.pose().popMatrix();
        drawLabels(g, k, scale, transpose);
    }

    private static void drawWhite(GuiGraphics g, KeyboardLayout k, int key, float depth, boolean dim, int scale, int edge) {
        int x0 = k.left(key), x1 = k.right(key) - scale, y0 = k.y(), y1 = k.bottom(key);
        if (x1 <= x0) x1 = x0 + 1;
        int body = dim ? WHITE_DIM : mix(WHITE, WHITE_DOWN, depth);
        g.fill(x0, y0, x1, y1, body);
        if (depth < 0.5f) {
            g.fill(x0, y0, x0 + edge, y1 - 2 * edge, dim ? WHITE_DIM : WHITE_TOP);
            g.fill(x0, y1 - 2 * edge, x1, y1, dim ? 0xFF6E6E6E : WHITE_LIP);
        } else {
            // Down: the front lip drops out of sight and the far end falls into shadow.
            g.fill(x0, y0, x1, y0 + edge, 0xFF7F8A9C);
        }
    }

    private static void drawBlack(GuiGraphics g, KeyboardLayout k, int key, float depth, boolean dim, int scale, int edge) {
        int x0 = k.left(key), x1 = k.right(key), y0 = k.y(), y1 = k.bottom(key);
        g.fill(x0 - scale / 2, y0, x1 + scale / 2, y1 + scale, OUTLINE);
        int body = dim ? BLACK_DIM : mix(BLACK, BLACK_DOWN, depth);
        g.fill(x0, y0, x1, y1, body);
        if (depth < 0.5f && !dim) {
            g.fill(x0, y0, x0 + edge, y1 - 3 * edge, BLACK_TOP);
            g.fill(x0, y1 - 3 * edge, x1, y1, BLACK_LIP);
        }
    }

    private void drawLabels(GuiGraphics g, KeyboardLayout k, int scale, int transpose) {
        Font font = Minecraft.getInstance().font;
        int base = ClientPrefs.get().keyboardBase;
        int bottom = (k.y() + k.height()) / scale;
        int whiteVisible = (k.height() - (k.bottom(1) - k.y())) / scale;
        boolean twoLines = whiteVisible >= 2 * font.lineHeight + 3;
        long now = System.nanoTime();
        PianoRuntime runtime = PianoClientState.find(piano);
        for (int key = 0; key < KeyboardLayout.KEYS; key++) {
            boolean black = KeyboardLayout.isBlack(key);
            int left = k.left(key) / scale, right = (k.right(key) - (black ? 0 : scale)) / scale;
            int room = right - left;
            String computer = keyLabel(key, base);
            boolean dim = key + transpose < 0 || key + transpose >= KeyboardLayout.KEYS;
            if (black) {
                // A letter may stand a pixel or two over the white keys beside a narrow black key; a plate
                // of the key's own colour behind it keeps its strokes from vanishing into the white.
                if (computer != null && !dim && font.width(computer) <= room + 3) {
                    int y = k.bottom(key) / scale - font.lineHeight - 1;
                    int x = left + (room - font.width(computer) + 1) / 2;
                    float depth = runtime == null ? 0f : runtime.keyDepth(key, now);
                    if (x < left || x + font.width(computer) - 1 > right) g.fill(x - 1, y - 1, x + font.width(computer), y + font.lineHeight - 1, mix(BLACK, BLACK_DOWN, depth));
                    g.drawString(font, computer, x, y, KEY_LABEL_ON_BLACK, false);
                }
                continue;
            }
            // With room for two lines, octave names take the bottom line (a name may run on over the
            // D beside it) and computer keys the line above; with one line, computer keys come first.
            int line = bottom - font.lineHeight;
            if (KeyboardLayout.isC(key)) {
                boolean sharesLine = computer != null && !twoLines;
                int nextRight = key + 2 < KeyboardLayout.KEYS ? (k.right(key + 2) - scale) / scale : -1;
                int previousLeft = key > 0 ? k.left(key - 1) / scale : left;
                OctaveNames.Placed name = OctaveNames.name(key, sharesLine, left, right, nextRight, previousLeft, font::width);
                if (name != null) g.drawString(font, name.text(), name.x(), line, C_LABEL, false);
                else if (sharesLine || key == MIDDLE_C) cMark(g, k, key, scale);
            }
            if (twoLines) line -= font.lineHeight + 1;
            if (computer != null && !dim && font.width(computer) <= room - 1 && line > k.bottom(1) / scale) {
                g.drawString(font, computer, left + (room - font.width(computer) + 1) / 2, line, KEY_LABEL_ON_WHITE, false);
            }
        }
    }

    /**
     * A C whose name is not printed gets a short bar on its front lip, under its letter, so the
     * octaves still show; middle C's fills the lip's height and is blue. Device pixels.
     */
    private static void cMark(GuiGraphics g, KeyboardLayout k, int key, int scale) {
        int edge = Math.max(1, scale / 2);
        int width = Math.max(2, (k.right(key) - k.left(key)) / 3);
        int height = key == MIDDLE_C ? 2 * edge : edge;
        int x = Math.round(k.centreX(key) - scale / 2f - width / 2f);
        int y = k.bottom(key) - 2 * edge;
        g.pose().pushMatrix();
        g.pose().scale(1f / scale, 1f / scale);
        g.fill(x, y, x + width, y + height, key == MIDDLE_C ? KEY_LABEL_ON_WHITE : C_LABEL);
        g.pose().popMatrix();
    }

    /**
     * The computer key shown on {@code key}: the lower row for the first octave, the upper row above
     * it (both rows play the five keys where they overlap; the upper row continues upward, so it is
     * the one shown there).
     */
    private static @Nullable String keyLabel(int key, int base) {
        List<Integer> keys = ComputerKeys.keysFor(key, base);
        if (keys.isEmpty()) return null;
        int glfw = keys.get(keys.size() - 1);
        String name = InputConstants.Type.KEYSYM.getOrCreate(glfw).getDisplayName().getString();
        return name.isEmpty() ? null : name;
    }

    private static int mix(int a, int b, float t) {
        float s = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - s) + ((b >> 16) & 0xFF) * s);
        int gr = Math.round(((a >> 8) & 0xFF) * (1 - s) + ((b >> 8) & 0xFF) * s);
        int bl = Math.round((a & 0xFF) * (1 - s) + (b & 0xFF) * s);
        return 0xFF000000 | r << 16 | gr << 8 | bl;
    }

    // ------------------------------------------------------------------ mouse

    private int keyAt(double guiX, double guiY) {
        KeyboardLayout k = layout();
        return k.keyAt(guiX * layoutScale, guiY * layoutScale);
    }

    private int velocityAt(int key, double guiY) {
        return layout().velocityAt(key, guiY * layoutScale);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int key = keyAt(event.x(), event.y());
        input.mouseKey(key, key < 0 ? 0 : velocityAt(key, event.y()));
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        int key = keyAt(event.x(), event.y());
        input.mouseKey(key, key < 0 ? 0 : velocityAt(key, event.y()));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        input.mouseKey(-1, 0);
    }

    /** Keys make their own sound; no button click. */
    @Override
    public void playDownSound(SoundManager soundManager) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
