package io.github.complydoc4j.sensitive;

import io.github.complydoc4j.config.CategoryConfig;
import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.text.PyRegex;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based detection. {@code patterns} are reported on sight; {@code context_patterns}
 * only with one of the category's labels within a short window.
 *
 * <p>A pattern may name the part that is the identifier with a group called {@code value},
 * and the label it was found by with a group called {@code label}.
 */
public final class RegexDetector {

    private static final double CONTEXT_CONFIDENCE = 0.8;

    /** A candidate span in one page's text, in UTF-16 offsets. Carries no value. */
    public record Finding(int start, int end, Double confidence, String contextTerm) {}

    private RegexDetector() {}

    public static List<Finding> find(String text, CategoryConfig config) {
        List<Finding> findings = new ArrayList<>();
        for (String python : config.patterns()) {
            Pattern compiled = PyRegex.compile(python, PyRegex.IGNORECASE);
            boolean valued = python.contains("(?P<value>");
            boolean labelled = python.contains("(?P<label>");
            Matcher match = compiled.matcher(text);
            while (match.find()) {
                int start = valued ? match.start("value") : match.start();
                int end = valued ? match.end("value") : match.end();
                if (start < 0 || start == end || insideANumber(text, start, end)) {
                    continue;
                }
                String label = labelled ? match.group("label") : null;
                findings.add(new Finding(start, end, 1.0, label != null ? Py.lower(Py.strip(label)) : null));
            }
        }
        if (!config.contextPatterns().isEmpty()) {
            for (String python : config.contextPatterns()) {
                Matcher match = PyRegex.compile(python, PyRegex.IGNORECASE).matcher(text);
                while (match.find()) {
                    String term = nearbyTerm(text, match.start(), match.end(), config.contextTerms(),
                            config.contextWindowChars());
                    if (term == null || insideANumber(text, match.start(), match.end())) {
                        continue;
                    }
                    findings.add(new Finding(match.start(), match.end(), CONTEXT_CONFIDENCE, term));
                }
            }
        }
        return findings;
    }

    /**
     * The first configured label within {@code window} characters of the match.
     *
     * <p>The label has to be on the match's own line, or on the line above when the match's
     * line has no words before it: a value under its column header, or alone on the line
     * after its label. A term matches whole words only: as a substring, "ach" was found in
     * "Machado".
     */
    static String nearbyTerm(String text, int start, int end, List<String> terms, int window) {
        if (terms.isEmpty()) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        String before = text.substring(Math.max(lineStart, backBy(text, start, window)), start);
        if (lineStart > 0 && !WORDS.matcher(before).find()) {
            int above = lineStart - 1 > 0 ? text.lastIndexOf('\n', lineStart - 2) + 1 : 0;
            before = text.substring(Math.max(above, backBy(text, start, window)), lineStart - 1) + " " + before;
        }
        String haystack = Py.lower(before) + " "
                + Py.lower(text.substring(end, Math.min(lineEnd, forwardBy(text, end, window))));
        for (String term : terms) {
            if (termPattern(Py.lower(term)).matcher(haystack).find()) {
                return term;
            }
        }
        return null;
    }

    private static final Pattern WORDS = PyRegex.compile("[^\\W\\d_]{2,}");

    private static final java.util.Map<String, Pattern> TERMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A context term as whole words. A term that ends in punctuation, {@code zip:}, ends there. */
    private static Pattern termPattern(String term) {
        return TERMS.computeIfAbsent(term, key -> {
            String core = Py.strip(key);
            boolean alnumTail = !core.isEmpty() && Py.isAlnum(core.codePointBefore(core.length()));
            boolean alnumHead = !core.isEmpty() && Py.isAlnum(core.codePointAt(0));
            String regex = (alnumHead ? "(?<![^\\W_])" : "") + Pattern.quote(core) + (alnumTail ? "(?![^\\W_])" : "");
            return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
        });
    }

    private static int backBy(String text, int from, int codePoints) {
        int at = from;
        for (int i = 0; i < codePoints && at > 0; i++) {
            at -= Character.charCount(text.codePointBefore(at));
        }
        return at;
    }

    private static int forwardBy(String text, int from, int codePoints) {
        int at = from;
        for (int i = 0; i < codePoints && at < text.length(); i++) {
            at += Character.charCount(text.codePointAt(at));
        }
        return at;
    }

    /** Whether a match made of digits is a piece of something longer: a decimal, a DOI. */
    static boolean insideANumber(String text, int start, int end) {
        if (start < text.length() && Py.isDigit(text.charAt(start)) && start >= 2) {
            char before = text.charAt(start - 1);
            char earlier = text.charAt(start - 2);
            if (((before == '.' || before == ',') && Py.isDigit(earlier))
                    || (before == '/' && Py.isAlnum(earlier))) {
                return true;
            }
        }
        int last = end - 1;
        return last >= 0 && Py.isDigit(text.charAt(last))
                && end + 1 < text.length()
                && (text.charAt(end) == '.' || text.charAt(end) == ',')
                && Py.isDigit(text.charAt(end + 1));
    }
}
