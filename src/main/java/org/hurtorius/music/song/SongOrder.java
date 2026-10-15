package org.hurtorius.music.song;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The order a world's songs are listed in: the songs its players added first, then the starter pieces
 * (the songs credited to their composers, {@link StarterSongs#isComposer}), each by name as people
 * read names, then by id. Numbers in a name count by their value ("Prelude No. 4" before "Prelude
 * No. 20", "Take 9" before "Take 10"), and case and accents only tell apart names that are otherwise
 * the same.
 */
public final class SongOrder {
    /** Letters only, for the text between numbers. */
    private static final Collator LETTERS = collator(Collator.PRIMARY);
    /** Letters, then accents, then case: the last word on names that read the same. */
    private static final Collator EXACT = collator(Collator.TERTIARY);

    /** The Songs tab's order, with the starter pieces after the world's own songs. */
    public static final Comparator<SongInfo> LIBRARY = library(s -> StarterSongs.isComposer(s.author()));

    private SongOrder() {}

    /** The order for a library whose starter pieces {@code starter} tells. */
    static Comparator<SongInfo> library(Predicate<SongInfo> starter) {
        return Comparator.comparing((SongInfo s) -> starter.test(s))
            .thenComparing(SongInfo::name, SongOrder::compareNames)
            .thenComparing(SongInfo::id);
    }

    /**
     * Compares two names as a reader orders them: runs of digits by their value, the text between them
     * by its letters (the root locale's collation, so case and accents aside); names equal that way by
     * their accents and case.
     */
    static int compareNames(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int iEnd = run(a, i), jEnd = run(b, j);
            String x = a.substring(i, iEnd), y = b.substring(j, jEnd);
            int order = isDigit(x.charAt(0)) && isDigit(y.charAt(0)) ? compareNumbers(x, y) : LETTERS.compare(x, y);
            if (order != 0) return order;
            i = iEnd;
            j = jEnd;
        }
        if (i < a.length() || j < b.length()) return i < a.length() ? 1 : -1;
        return EXACT.compare(a, b);
    }

    /** Where the run of digits, or of other characters, that starts at {@code from} ends. */
    private static int run(String s, int from) {
        boolean digits = isDigit(s.charAt(from));
        int end = from + 1;
        while (end < s.length() && isDigit(s.charAt(end)) == digits) end++;
        return end;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Two runs of digits by value, however long they are ("007" is 7). */
    private static int compareNumbers(String a, String b) {
        String x = withoutLeadingZeros(a), y = withoutLeadingZeros(b);
        return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
    }

    private static String withoutLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') start++;
        return digits.substring(start);
    }

    private static Collator collator(int strength) {
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(strength);
        return collator;
    }
}
