package io.github.complydoc4j.sensitive;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.text.Py;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The single point at which a detected value can become readable text.
 *
 * <p>Masking is the default. Revealing is honoured only for categories not listed under
 * {@code masking.never_reveal}.
 */
public final class Masking {

    private Masking() {}

    /** Whether revealing may show this category at all. */
    public static boolean revealAllowed(String category, Config.Masking config) {
        return !config.neverReveal().contains(category);
    }

    /** Every character but the last few masked, separators kept so the shape stays legible. */
    public static String maskValue(String value, Config.Masking config) {
        int tail = Math.max(0, config.revealTailChars());
        int[] chars = value.codePoints().toArray();
        List<Integer> significant = new ArrayList<>();
        for (int i = 0; i < chars.length; i++) {
            if (Py.isAlnum(chars[i])) {
                significant.add(i);
            }
        }
        Set<Integer> keep = new HashSet<>();
        if (significant.size() > tail && tail > 0) {
            keep.addAll(significant.subList(significant.size() - tail, significant.size()));
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            int c = chars[i];
            if (Py.isAlnum(c)) {
                if (keep.contains(i)) {
                    out.appendCodePoint(c);
                } else {
                    out.append(config.maskChar());
                }
            } else {
                // A masked value goes into a table cell and must never carry layout of its own.
                out.appendCodePoint(Py.isPrintable(c) ? c : ' ');
            }
        }
        return out.toString();
    }

    /** The masked value, and the value itself when revealing is asked for and allowed. */
    public static String[] render(String value, String category, Config.Masking config, boolean reveal) {
        String masked = maskValue(value, config);
        if (reveal && revealAllowed(category, config)) {
            return new String[] {masked, Py.collapse(value)};
        }
        return new String[] {masked, null};
    }

    /** The text with each match replaced by its masked form, and how many were replaced. */
    public record Masked(String text, int replaced, int confirmed) {}

    /**
     * Replace each found identifier with its masked form, a character at a time, strongest
     * evidence first, never writing where something has already been masked: two detectors
     * can claim the same characters, and the weaker must not write back over the stronger.
     *
     * <p>A match is placed by line and column and may run on past its line, as an IBAN a
     * narrow column wrapped does. It is covered where it lies in the whole text, and the line
     * breaks inside it stay, so the lines are as they were.
     */
    public static Masked maskMatches(String text, List<SensitiveMatch> matches) {
        if (matches.isEmpty()) {
            return new Masked(text, 0, 0);
        }
        int[] chars = text.codePoints().toArray();
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\n') {
                starts.add(i + 1);
            }
        }
        Set<Integer> covered = new HashSet<>();
        int replaced = 0;
        int confirmed = 0;
        List<SensitiveMatch> ordered = new ArrayList<>(matches);
        ordered.sort(Comparator.<SensitiveMatch>comparingInt(m -> rank(m.evidence()))
                .thenComparingInt(SensitiveMatch::line)
                .thenComparingInt(SensitiveMatch::column));
        for (SensitiveMatch match : ordered) {
            int index = match.line() - 1;
            if (index < 0 || index >= starts.size() || match.column() < 0) {
                continue;
            }
            int start = starts.get(index) + match.column();
            int end = start + match.length();
            if (end > chars.length) {
                continue;
            }
            List<Integer> free = new ArrayList<>();
            for (int position = start; position < end; position++) {
                if (!covered.contains(position)) {
                    free.add(position);
                }
            }
            if (free.isEmpty()) {
                // Wholly inside something already masked.
                continue;
            }
            int[] masked = match.masked().codePoints().toArray();
            if (masked.length == match.length()) {
                for (int position : free) {
                    if (chars[position] != '\r' && chars[position] != '\n') {
                        chars[position] = masked[position - start];
                    }
                }
            } else {
                int[] joined = new int[chars.length - (end - start) + masked.length];
                System.arraycopy(chars, 0, joined, 0, start);
                System.arraycopy(masked, 0, joined, start, masked.length);
                System.arraycopy(chars, end, joined, start + masked.length, chars.length - end);
                chars = joined;
            }
            for (int position = start; position < end; position++) {
                covered.add(position);
            }
            replaced++;
            if ("confirmed".equals(match.evidence())) {
                confirmed++;
            }
        }
        return new Masked(new String(chars, 0, chars.length), replaced, confirmed);
    }

    private static int rank(String evidence) {
        int index = SensitiveMatch.EVIDENCE_ORDER.indexOf(evidence);
        return index < 0 ? 99 : index;
    }
}
