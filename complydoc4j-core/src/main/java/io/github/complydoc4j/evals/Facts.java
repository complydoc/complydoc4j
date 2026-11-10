package io.github.complydoc4j.evals;

import io.github.complydoc4j.config.ConfigLoader;
import io.github.complydoc4j.hidden.Smuggled;
import io.github.complydoc4j.sensitive.Masking;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.text.PyRegex;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether expected facts appear in extracted text. Matching ignores case, whitespace,
 * invisible characters and words split by a hyphen at a line break. A passage not found
 * exactly is scored from 0 to 1 by its most similar run of words, and a score at or above
 * the threshold is a fuzzy match. A value is a number checked as a number, however it is
 * grouped. The Python package's {@code extraction/facts.py}.
 */
public final class Facts {

    public static final double FUZZY_THRESHOLD = 0.9;
    /** Words after a value's label it may be found in: next to it, or a table cell or two along. */
    static final int LABEL_REACH = 12;
    /** A ceiling on the quadratic comparison: pages longer than this are compared this far. */
    static final int MAX_WORDS = 6000;
    private static final String MASK = "•";
    /** Share of a fact's characters a masked page must still show for the fact to be found in it. */
    private static final double MIN_SHOWN = 0.5;

    private static final Pattern HYPHEN_BREAK = PyRegex.compile("(\\w)-[ \\t]*\\n[ \\t]*(\\w)");
    static final Pattern NUMBER = PyRegex.compile("(?<![\\d.,])"
            + "(?:\\d{1,3}(?P<group>[   .,'’])\\d{3}(?:(?P=group)\\d{3})*(?:[.,]\\d+)?"
            + "|\\d+(?:[.,]\\d+)?)"
            + "(?![\\d])");
    private static final Pattern TAIL = PyRegex.compile("[.,](\\d+)$");
    private static final String SPACES = "   ";

    /** A page's number, null where a format has none, and its text. */
    public record Page(Integer number, String text) {}

    /**
     * @param kind {@code exact}, {@code fuzzy}, or null when not found
     * @param passage the run of words that came closest, when not found exactly
     */
    public record Match(String kind, double score, Integer page, String passage) {
        static final Match NONE = new Match(null, 0.0, null, null);
    }

    private Facts() {}

    /** Invisible characters, line-break hyphens and extra spaces removed, case kept. */
    static String tidy(String text) {
        String t = Normalizer.normalize(Smuggled.stripInvisible(text), Normalizer.Form.NFKC).replace("­", "");
        t = HYPHEN_BREAK.matcher(t).replaceAll("$1$2");
        return Py.collapse(t);
    }

    static String normalise(String text) {
        return Py.casefold(tidy(text));
    }

    /** Where {@code text} appears in {@code pages}. */
    public static Match findFact(List<Page> pages, String text, double threshold) {
        String target = normalise(text);
        int size = Py.split(target).size();
        Match best = Match.NONE;
        SequenceMatcher matcher = new SequenceMatcher();
        matcher.setSeq2(target);
        for (Page page : pages) {
            String tidy = tidy(page.text());
            String haystack = Py.casefold(tidy);
            if (haystack.contains(target)) {
                return new Match("exact", 1.0, page.number(), null);
            }
            List<String> words = head(Py.split(haystack));
            // The same words as the page wrote them, index for index.
            List<String> shown = head(Py.split(tidy));
            for (int width : new TreeSet<>(List.of(Math.max(1, size - 1), size, size + 1))) {
                for (int start = 0; start < Math.max(1, words.size() - width + 1); start++) {
                    matcher.setSeq1(String.join(" ", words.subList(start, Math.min(words.size(), start + width))));
                    if (matcher.realQuickRatio() <= best.score() || matcher.quickRatio() <= best.score()) {
                        continue;
                    }
                    double ratio = matcher.ratio();
                    if (ratio > best.score()) {
                        String passage = String.join(" ", shown.subList(Math.min(start, shown.size()),
                                Math.min(shown.size(), start + width)));
                        best = new Match(null, round4(ratio), page.number(), passage);
                    }
                }
            }
        }
        if (best.score() >= threshold) {
            return new Match("fuzzy", best.score(), best.page(), best.passage());
        }
        return new Match(null, best.score(), null, best.passage());
    }

    private static List<String> head(List<String> words) {
        return words.subList(0, Math.min(MAX_WORDS, words.size()));
    }

    /** Python's {@code round(x, 4)}: to the nearest, ties to even, on the double's exact value. */
    static double round4(double value) {
        return new BigDecimal(value).setScale(4, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    // --- values -------------------------------------------------------------------------

    /** The numbers {@code token} can be read as, each in its plainest form. */
    static Set<BigDecimal> readings(String token) {
        Matcher match = NUMBER.matcher(token);
        if (!match.matches()) {
            return Set.of();
        }
        String group = match.group("group");
        Set<BigDecimal> readings = new HashSet<>();
        Matcher tail = TAIL.matcher(token);
        boolean hasTail = tail.find();
        if (group == null) {
            if (!hasTail) {
                return Set.of(decimal(token));
            }
            String whole = token.substring(0, tail.start());
            readings.add(decimal(whole + "." + tail.group(1)));
            if (Py.len(tail.group(1)) == 3 && Py.len(whole) <= 3) {
                readings.add(decimal(whole + tail.group(1)));
            }
            return readings;
        }
        String body = token;
        String fraction = "";
        if (hasTail && !token.substring(tail.start(), tail.start() + 1).equals(group)) {
            body = token.substring(0, tail.start());
            fraction = tail.group(1);
        } else if (hasTail && Py.len(tail.group(1)) != 3) {
            return Set.of();
        }
        String digits = body.replace(group, "");
        readings.add(decimal(fraction.isEmpty() ? digits : digits + "." + fraction));
        if (fraction.isEmpty() && (group.equals(".") || group.equals(",")) && count(body, group) == 1) {
            String[] parts = body.split(Pattern.quote(group));
            readings.add(decimal(parts[0] + "." + parts[1]));
        }
        return readings;
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    /** Decimal digits of any script as ASCII, compared by value: 5100.00 and 5100 are one number. */
    private static BigDecimal decimal(String text) {
        StringBuilder ascii = new StringBuilder();
        text.codePoints().forEach(c -> ascii.append(Py.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : (char) c));
        BigDecimal value = new BigDecimal(ascii.toString());
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    /** What an expected value can be read as, with any currency or sign around it left out. */
    public static Set<BigDecimal> valueReadings(String value) {
        List<String> found = new ArrayList<>();
        Matcher m = NUMBER.matcher(value);
        while (m.find()) {
            found.add(m.group());
        }
        return found.size() == 1 ? readings(found.get(0)) : Set.of();
    }

    private record Number(int start, int end, Set<BigDecimal> read) {}

    /** Each number in {@code text}, in code points; digits grouped by spaces also part by part. */
    private static List<Number> numbers(String text) {
        List<Number> found = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            int start = Py.cp(text, m.start());
            found.add(new Number(start, Py.cp(text, m.end()), readings(m.group())));
            String group = m.group("group");
            if (group != null && SPACES.contains(group)) {
                Matcher part = Pattern.compile("\\S+").matcher(m.group());
                while (part.find()) {
                    int at = start + Py.cp(m.group(), part.start());
                    found.add(new Number(at, at + Py.len(part.group()), readings(part.group())));
                }
            }
        }
        return found;
    }

    private static String around(String text, int start, int end) {
        List<String> before = Py.split(Py.head(text, start));
        List<String> after = Py.split(Py.slice(text, end, Py.len(text)));
        List<String> parts = new ArrayList<>(before.subList(Math.max(0, before.size() - 6), before.size()));
        parts.add(Py.slice(text, start, end));
        parts.addAll(after.subList(0, Math.min(6, after.size())));
        return String.join(" ", parts);
    }

    /**
     * Where the number {@code value} appears, and whether by its label: exact within
     * {@link #LABEL_REACH} words after the label, or anywhere when there is no label; fuzzy
     * when the number is there but not by the label.
     */
    public static Match findValue(List<Page> pages, String value, String label) {
        Set<BigDecimal> wanted = valueReadings(value);
        String marker = label == null ? null : normalise(label);
        Match elsewhere = null;
        Match afterLabel = null;
        for (Page page : pages) {
            String tidy = tidy(page.text());
            String folded = Py.casefold(tidy);
            List<int[]> found = new ArrayList<>();
            for (Number n : numbers(tidy)) {
                if (n.read().stream().anyMatch(wanted::contains)) {
                    found.add(new int[] {n.start(), n.end()});
                }
            }
            if (marker == null) {
                if (!found.isEmpty()) {
                    return new Match("exact", 1.0, page.number(), null);
                }
                continue;
            }
            int at = find(folded, marker, 0);
            while (at != -1) {
                if (at > 0 && Py.isAlnum(Py.slice(folded, at - 1, at).codePointAt(0))) {
                    at = find(folded, marker, at + 1);
                    continue;
                }
                int reachEnd = at + Py.len(marker);
                List<String> next = Py.split(Py.slice(tidy, reachEnd, Py.len(tidy)));
                int limit = reachEnd + Py.len(String.join(" ", next.subList(0, Math.min(LABEL_REACH, next.size())))) + 1;
                for (int[] span : found) {
                    if (reachEnd <= span[0] && span[1] <= limit) {
                        return new Match("exact", 1.0, page.number(), null);
                    }
                }
                if (afterLabel == null) {
                    afterLabel = new Match(null, 0.0, page.number(), Py.strip(Py.slice(tidy, at, limit)));
                }
                at = find(folded, marker, at + 1);
            }
            if (!found.isEmpty() && elsewhere == null) {
                elsewhere = new Match("fuzzy", 0.5, page.number(), around(tidy, found.get(0)[0], found.get(0)[1]));
            }
        }
        if (elsewhere != null) {
            return elsewhere;
        }
        return afterLabel != null ? afterLabel : Match.NONE;
    }

    /** {@code str.find} in code points. */
    private static int find(String text, String part, int from) {
        int at = text.indexOf(part, Py.utf16(text, from));
        return at < 0 ? -1 : Py.cp(text, at);
    }

    // --- masked text ----------------------------------------------------------------------

    /** The fact as a report's masked page text would show it. */
    static String asReported(String text) {
        return Masking.maskMatches(text, Scanner.scanText(text, ConfigLoader.shipped().sensitive()).matches()).text();
    }

    /**
     * Where {@code text} is on a page that covers part of it with a mask: every character the
     * page still shows is the passage's own, and it shows at least half of them.
     */
    static Match findUnderMasks(List<Page> pages, String text) {
        int[] target = normalise(text).codePoints().toArray();
        if (target.length == 0) {
            return null;
        }
        int mask = MASK.codePointAt(0);
        for (Page page : pages) {
            int[] haystack = normalise(page.text()).codePoints().toArray();
            Set<Integer> tried = new HashSet<>();
            for (int i = 0; i < haystack.length; i++) {
                if (haystack[i] != mask || (i > 0 && haystack[i - 1] == mask)) {
                    continue;
                }
                int runEnd = i;
                while (runEnd < haystack.length && haystack[runEnd] == mask) {
                    runEnd++;
                }
                int first = Math.max(0, i - target.length + 1);
                int last = Math.min(runEnd - 1, haystack.length - target.length);
                for (int start = first; start <= last; start++) {
                    if (!tried.add(start)) {
                        continue;
                    }
                    int shown = 0;
                    boolean fits = true;
                    for (int k = 0; k < target.length && fits; k++) {
                        int have = haystack[start + k];
                        if (have != mask) {
                            shown++;
                        }
                        fits = have == target[k] || have == mask;
                    }
                    if (fits && shown >= MIN_SHOWN * target.length) {
                        return new Match("exact", 1.0, page.number(), null);
                    }
                }
            }
        }
        return null;
    }

    /** {@link #findFact} against page text that may be masked. */
    static Match findReported(List<Page> pages, String text, double threshold) {
        Match match = findFact(pages, text, threshold);
        if ("exact".equals(match.kind())) {
            return match;
        }
        Match under = findUnderMasks(pages, text);
        if (under != null) {
            return under;
        }
        String masked = asReported(text);
        if (masked.equals(text)) {
            return match;
        }
        Match other = findFact(pages, masked, threshold);
        return other.score() > match.score() ? other : match;
    }
}
