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

    /** The first configured label within {@code window} characters of the match. */
    static String nearbyTerm(String text, int start, int end, List<String> terms, int window) {
        if (terms.isEmpty()) {
            return null;
        }
        int left = backBy(text, start, window);
        int right = forwardBy(text, end, window);
        String haystack = Py.lower(text.substring(left, start)) + " " + Py.lower(text.substring(end, right));
        for (String term : terms) {
            if (haystack.contains(Py.lower(term))) {
                return term;
            }
        }
        return null;
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
