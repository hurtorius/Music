package org.hurtorius.music.client.ui;

import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * The piano screen's line of news: the latest message, or, when there is none, a hint about what the
 * player can do right now. A message too long for the line wraps onto up to {@value #MAX_LINES}
 * lines, which follow on from the line as one block (below it, or above it while the panel is put
 * away), broken between words or after a folder in a path; one cut short even then ends in "…" and
 * shows whole in a tooltip under the pointer. Good news goes after a few seconds; an error stays
 * until the player does something after that, so it is not gone while they look away. News from
 * outside (the action bar, chat while the screen has no room for it) never takes the place of the
 * piano's own messages but waits behind them, and news the piano's own message interrupts comes back
 * for the rest of its time. News holds the line for a stretch of at most {@link #NEWS_RUN_NANOS},
 * fresher news taking the place of older within it (a friend's line is not pushed aside by the
 * action bar, which waits behind it); the piano's own message interrupting a stretch does not start a
 * new one. After a stretch the line rests from news for {@link #NEWS_REST_NANOS}, so that a server
 * sending news every second still leaves the hint its turn. An action bar a server keeps sending
 * unchanged shows once: once the line has shown it for {@link #SEEN_NANOS}, so a copy pushed aside
 * sooner (by a friend's line, which it then waits behind, or the piano's own message) is still news.
 * An empty one takes the action bar's news away. A hint is made of parts in order of importance, and
 * the least important ones are left out when they do not all fit. Client thread.
 */
final class StatusLine {
    static final long MESSAGE_NANOS = 6_000_000_000L;
    /** A short note about something the player just changed (velocity, the sustain pedal). */
    static final long BRIEF_NANOS = 1_500_000_000L;
    /**
     * The longest stretch news from outside holds the line (one message, or several that follow on),
     * and how long the line then rests from it.
     */
    static final long NEWS_RUN_NANOS = MESSAGE_NANOS, NEWS_REST_NANOS = 3_000_000_000L;
    /** The same action bar text again within this long of the last time is the server keeping it up: once the line has shown it, not news. */
    static final long REPEAT_NANOS = MESSAGE_NANOS;
    /** News on the line this long has been seen: an action bar that makes way sooner is still news. */
    static final long SEEN_NANOS = BRIEF_NANOS;
    /** Most lines a message takes. */
    static final int MAX_LINES = 3;
    /** Widest a tooltip with a whole message gets, in GUI units. */
    private static final int TOOLTIP_WIDTH = 300;
    /** Behind the hint's text, and behind what the screen shows in its place (why a piano is silent). */
    static final int HINT_BACKING = 0x80000000;
    /**
     * The hint's colour, behind a one-line message's text (darker: news must read over the desk's sheet
     * behind it), and behind a message of several lines, a block that hides what is under it.
     */
    private static final int HINT_COLOUR = 0xFFA0A0A0, MESSAGE_BACKING = 0xC0101010, BLOCK_BACKING = 0xF0101010;
    private static final String ELLIPSIS = "…";
    private static final Component SEPARATOR = Component.literal(" · ");

    private final LongSupplier clock;
    private @Nullable Component message;
    private Feedback.Kind kind = Feedback.Kind.INFO;
    private long until;
    /** An error stays past its time until the player does something. */
    private boolean waitsForPlayer;
    /** The message is news from outside, not the piano's own. */
    private boolean news;
    /** The message is a chat message, shown here because the screen had no room for chat. */
    private boolean fromChat;
    /**
     * When the stretch of news going on ends, or when the last one did: news may take the free line
     * before then, and again once the line has rested {@link #NEWS_REST_NANOS} after it.
     */
    private long stretchEnds;
    /**
     * The latest news that came while the line was not free for it (a friend's line before the action
     * bar), or news the piano's own message interrupted: it shows once the line is, if still fresh.
     */
    private @Nullable Component held;
    private Feedback.Kind heldKind = Feedback.Kind.INFO;
    private long heldUntil;
    private boolean heldFromChat;
    /**
     * What the action bar said last, when it last said it, whether the line has shown that text since
     * it began (it counts as not shown once it makes way within {@link #SEEN_NANOS}), and when the line
     * last began to show the action bar's news.
     */
    private String lastBar = "";
    private long lastBarAt;
    private boolean barSeen;
    private long barShownAt;
    /** The hint as it was last drawn (for tests), or empty. */
    private String drawnHint = "";

    StatusLine() {
        this(System::nanoTime);
    }

    /** A status line on {@code clock} (nanoseconds, as {@link System#nanoTime}); it starts out rested. */
    StatusLine(LongSupplier clock) {
        this.clock = clock;
        this.stretchEnds = clock.getAsLong() - NEWS_REST_NANOS;
    }

    void show(Component text, Feedback.Kind messageKind) {
        show(text, messageKind, MESSAGE_NANOS);
    }

    /**
     * Shows the piano's own {@code text} for at least {@code nanos}; an error stays longer, until the
     * player does something. News showing waits behind it for the rest of its time, its stretch going
     * on meanwhile.
     */
    void show(Component text, Feedback.Kind messageKind, long nanos) {
        long now = clock.getAsLong();
        expire(now);
        if (message != null && news) {
            barMakesWay(now);
            hold(message, kind, fromChat, until);
        }
        message = text;
        kind = messageKind;
        until = now + nanos;
        waitsForPlayer = messageKind == Feedback.Kind.ERROR;
        news = false;
        fromChat = false;
    }

    /**
     * News that did not come from the player's own doing: an action bar message, or chat
     * ({@code chat}). It takes the place of news showing (a friend's line only from more chat; an
     * action bar it pushes aside before it was seen waits behind it) until that stretch of news ends,
     * or takes the free line within the stretch going on or once the line has rested after the last;
     * otherwise it waits. An action bar saying again what the line already showed is the server
     * keeping it up and changes nothing; an empty one takes the action bar's news away.
     */
    void aside(Component text, Feedback.Kind messageKind, boolean chat) {
        long now = clock.getAsLong();
        settle(now);
        if (!chat) {
            String said = text.getString();
            boolean again = said.equals(lastBar) && now - lastBarAt < REPEAT_NANOS;
            lastBar = said;
            lastBarAt = now;
            if (said.isBlank()) {
                if (held != null && !heldFromChat) held = null;
                if (message != null && news && !fromChat) takeNewsAway(now);
                return;
            }
            if (!again) {
                barSeen = false;
            } else if (barSeen) {
                return;
            } else if (held != null && !heldFromChat && held.getString().equals(said)) {
                // Still what the server says: waiting unseen, it stays fresh.
                heldUntil = now + MESSAGE_NANOS;
                return;
            }
            // Otherwise a friend's line pushed it aside before it was seen: it is still news.
        }
        if (message != null && news && (chat || !fromChat)) {
            // A friend's line comes first; an action bar not yet seen waits behind it, as one would
            // that came while it showed.
            if (chat && barMakesWay(now)) hold(message, kind, false, until);
            showNews(text, messageKind, chat, now, Math.min(now + MESSAGE_NANOS, stretchEnds));
        } else if (message == null && newsMayShow(now)) {
            showNews(text, messageKind, chat, now, Math.min(now + MESSAGE_NANOS, stretchEnds));
        } else {
            hold(text, messageKind, chat, now + MESSAGE_NANOS);
        }
    }

    /**
     * True when news may have the free line at {@code now}: within the stretch going on (the piano's
     * own message interrupted it), or once the line has rested after the last stretch, which starts a
     * new one.
     */
    private boolean newsMayShow(long now) {
        if (now < stretchEnds) return true;
        if (now < stretchEnds + NEWS_REST_NANOS) return false;
        stretchEnds = now + NEWS_RUN_NANOS;
        return true;
    }

    /**
     * Keeps {@code text} to show once the line is free, until {@code end}, unless a friend's line
     * waits and this is the action bar.
     */
    private void hold(Component text, Feedback.Kind messageKind, boolean chat, long end) {
        if (held != null && heldFromChat && !chat) return;
        held = text;
        heldKind = messageKind;
        heldUntil = end;
        heldFromChat = chat;
    }

    private void showNews(Component text, Feedback.Kind messageKind, boolean chat, long now, long end) {
        message = text;
        kind = messageKind;
        until = end;
        waitsForPlayer = false;
        news = true;
        fromChat = chat;
        if (!chat) {
            barSeen = true;
            barShownAt = now;
        }
    }

    /**
     * The news showing makes way before its time. True when it is the action bar's and had not been
     * on the line for {@link #SEEN_NANOS}: then it was not seen, and the server keeping it up is news
     * again.
     */
    private boolean barMakesWay(long now) {
        if (fromChat || now - barShownAt >= SEEN_NANOS) return false;
        barSeen = false;
        return true;
    }

    /** Takes the news showing off the line before its time: its stretch ends, and the line rests from news from now. */
    private void takeNewsAway(long now) {
        stretchEnds = now;
        message = null;
    }

    /** A short note about something the player just changed; it does not push an error aside. */
    void note(Component text) {
        if (errorShowing()) return;
        show(text, Feedback.Kind.INFO, BRIEF_NANOS);
    }

    private boolean errorShowing() {
        return message() != null && !news && kind == Feedback.Kind.ERROR;
    }

    /** The player pressed a key, clicked or changed tabs: an error may go once its time is up. */
    void acted() {
        waitsForPlayer = false;
    }

    /** Takes away an error showing now (it went to chat, where it can be read again). */
    void dismissError() {
        if (message != null && !news && kind == Feedback.Kind.ERROR) message = null;
    }

    /** Takes away chat shown here, now that chat has room on the screen again. */
    void dropChat() {
        if (held != null && heldFromChat) held = null;
        if (message != null && news && fromChat) takeNewsAway(clock.getAsLong());
    }

    /** The message showing now, or null when the hint shows. */
    @Nullable Component message() {
        settle(clock.getAsLong());
        return message;
    }

    /** Lets the message go once its time is up, and puts the news that waited on the line once news may have it. */
    private void settle(long now) {
        expire(now);
        if (message != null || held == null) return;
        if (now >= heldUntil) {
            held = null;
        } else if (newsMayShow(now)) {
            // The news that waited, for what is left of its time.
            showNews(held, heldKind, heldFromChat, now, Math.min(heldUntil, stretchEnds));
            held = null;
        }
    }

    /** Lets the message go once its time is up (an error only once the player has done something); news leaving ends its stretch. */
    private void expire(long now) {
        if (message == null || now <= until || waitsForPlayer) return;
        if (news) stretchEnds = until;
        message = null;
    }

    Feedback.Kind kind() {
        return kind;
    }

    /** The hint as it was last drawn, or empty when a message showed instead. */
    String drawnHint() {
        return drawnHint;
    }

    /**
     * What the line shows this frame and where: the message (or the hint) on {@code line}, and a
     * message too long for it in more lines that follow on downward when {@code down}, else upward,
     * as far as {@code bound} (the lowest, or the highest, edge they may reach).
     */
    Shown layout(Font font, ScreenRectangle line, int bound, boolean down, List<Component> hint) {
        int textY = line.top() + (line.height() - font.lineHeight) / 2 + 1;
        int centre = line.left() + line.width() / 2;
        Component shown = message();
        if (line.width() <= 0 || line.height() <= 0) return new Shown(List.of(), new int[0], false, false, Component.empty(), 0, line);
        if (shown == null) {
            Component fitted = joinFitting(hint, line.width(), font::width);
            Component text = font.width(fitted) <= line.width() ? fitted : Component.literal(fit(font, fitted.getString(), line.width()));
            drawnHint = text.getString();
            return new Shown(List.of(text), new int[]{textY}, false, false, text, HINT_COLOUR, backing(font, text, centre, textY));
        }
        drawnHint = "";
        int step = font.lineHeight + 1;
        int room = room(textY, font.lineHeight, step, bound, down);
        Wrapped wrapped = wrap(font.getSplitter(), shown.getString(), line.width(), room);
        List<Component> lines = new ArrayList<>(wrapped.lines().size());
        for (String text : wrapped.lines()) lines.add(Component.literal(text));
        int[] ys = lineYs(textY, lines.size(), step, down);
        ScreenRectangle block = lines.size() == 1 ? backing(font, lines.getFirst(), centre, textY)
            : new ScreenRectangle(line.left() - 2, ys[0] - 1, line.width() + 4, ys[ys.length - 1] + font.lineHeight + 1 - (ys[0] - 1));
        return new Shown(lines, ys, true, wrapped.cut(), shown, kind.colour, block);
    }

    /** Draws what {@link #layout} placed; the whole message shows in a tooltip when it was cut short and the pointer is on it. */
    void render(GuiGraphics g, Font font, Shown shown, int mouseX, int mouseY) {
        if (shown.lines().isEmpty()) return;
        ScreenRectangle block = shown.block();
        int backing = !shown.message() ? HINT_BACKING : shown.lines().size() == 1 ? MESSAGE_BACKING : BLOCK_BACKING;
        g.fill(block.left(), block.top(), block.right(), block.bottom(), backing);
        int centre = block.left() + block.width() / 2;
        for (int i = 0; i < shown.lines().size(); i++) {
            Component text = shown.lines().get(i);
            g.drawString(font, text, centre - font.width(text) / 2, shown.ys()[i], shown.colour());
        }
        if (shown.cut() && block.containsPoint(mouseX, mouseY)) {
            List<FormattedCharSequence> whole = new ArrayList<>();
            for (String text : lines(font.getSplitter(), shown.whole().getString(), TOOLTIP_WIDTH)) whole.add(Component.literal(text).getVisualOrderText());
            g.setTooltipForNextFrame(whole, mouseX, mouseY);
        }
    }

    /** The backing just the size of one line of text centred on {@code centre}. */
    private static ScreenRectangle backing(Font font, Component text, int centre, int y) {
        int width = font.width(text);
        if (width <= 0) return new ScreenRectangle(centre, y - 1, 0, 0);
        int x = centre - width / 2;
        return new ScreenRectangle(x - 2, y - 1, width + 4, font.lineHeight + 1);
    }

    /**
     * The line's text this frame: its lines with their heights, whether it is a message (not the
     * hint) and was cut short, the whole of it, its colour, and the block it covers on the screen.
     */
    record Shown(List<Component> lines, int[] ys, boolean message, boolean cut, Component whole, int colour, ScreenRectangle block) {}

    /**
     * How many lines a message may take, the first at {@code textY}: as many as fit before
     * {@code bound}, going down (a bottom edge) or up (a top edge), and at most {@value #MAX_LINES}.
     */
    static int room(int textY, int lineHeight, int step, int bound, boolean down) {
        int beyond = down ? bound - (textY + lineHeight) : textY - bound;
        return Math.min(MAX_LINES, 1 + Math.max(0, beyond) / step);
    }

    /**
     * Where {@code count} lines go, read top to bottom, so that they follow on from the status line
     * at {@code textY} as one block: it holds the first line going down, the last going up.
     */
    static int[] lineYs(int textY, int count, int step, boolean down) {
        int[] ys = new int[count];
        for (int i = 0; i < count; i++) ys[i] = down ? textY + i * step : textY - (count - 1 - i) * step;
        return ys;
    }

    /** A message in lines; {@code cut} when it did not fit in them and the last one ends in "…". */
    record Wrapped(List<String> lines, boolean cut) {}

    /**
     * {@code text} in lines at most {@code width} wide, broken as {@link #lines} breaks it, at most
     * {@code maxLines} of them; when it needs more, the last line kept ends in "…".
     */
    static Wrapped wrap(StringSplitter splitter, String text, int width, int maxLines) {
        List<String> lines = lines(splitter, text, Math.max(1, width));
        int keep = Math.max(1, maxLines);
        if (lines.size() <= keep) return new Wrapped(lines, false);
        List<String> kept = new ArrayList<>(lines.subList(0, keep));
        String last = kept.getLast();
        float room = width - splitter.stringWidth(ELLIPSIS);
        String head = splitter.stringWidth(last) <= room ? last : splitter.plainHeadByWidth(last, (int) Math.max(0, room), Style.EMPTY);
        kept.set(keep - 1, head.stripTrailing() + ELLIPSIS);
        return new Wrapped(kept, true);
    }

    /**
     * {@code text} in lines at most {@code width} wide, each as long as fits: broken before a space
     * (which goes), or after a slash or backslash that more of a word follows (a folder's path has no
     * spaces, and would otherwise be cut through a folder's name; "1/2" and "//" stay whole), and
     * inside a word only when it is wider than a line even so. A line break in the text stays one.
     */
    static List<String> lines(StringSplitter splitter, String text, int width) {
        List<String> lines = new ArrayList<>();
        int start = skipSpaces(text, 0);
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            int cut = -1;
            for (int at = start + 1; at <= end; at++) {
                if (at < end && !breakBefore(text, at)) continue;
                if (splitter.stringWidth(text.substring(start, at).stripTrailing()) > width) break;
                cut = at;
            }
            if (cut < 0) {
                // A word wider than the line: as much of it as fits, and never less than a character.
                int fits = splitter.findLineBreak(text.substring(start, end), width, Style.EMPTY);
                cut = start + Math.max(fits, Character.charCount(text.codePointAt(start)));
            }
            lines.add(text.substring(start, cut).strip());
            start = skipSpaces(text, cut < text.length() && text.charAt(cut) == '\n' ? cut + 1 : cut);
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /** True when a line may end just before {@code at}: at a space, or after a slash or backslash that more of a word follows. */
    private static boolean breakBefore(String text, int at) {
        char next = text.charAt(at), previous = text.charAt(at - 1);
        if (next == ' ') return true;
        if ((previous != '/' && previous != '\\') || next == '/' || next == '\\') return false;
        return !(Character.isDigit(next) && at >= 2 && Character.isDigit(text.charAt(at - 2)));
    }

    private static int skipSpaces(String text, int from) {
        int at = from;
        while (at < text.length() && text.charAt(at) == ' ') at++;
        return at;
    }

    /**
     * The hint's parts joined with " · ", leaving out parts from the end (the least important) until
     * it fits {@code width}; the first part always stays.
     */
    static Component joinFitting(List<Component> parts, int width, ToIntFunction<Component> widthOf) {
        for (int count = parts.size(); count > 0; count--) {
            MutableComponent joined = Component.empty();
            for (int i = 0; i < count; i++) {
                if (i > 0) joined.append(SEPARATOR);
                joined.append(parts.get(i));
            }
            if (count == 1 || widthOf.applyAsInt(joined) <= width) return joined;
        }
        return Component.empty();
    }

    /** {@code text}, cut short with an ellipsis so it is at most {@code width} wide. */
    static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        int room = Math.max(0, width - font.width(ELLIPSIS));
        return font.plainSubstrByWidth(text, room) + ELLIPSIS;
    }
}
