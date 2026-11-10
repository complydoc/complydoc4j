package io.github.complydoc4j.hidden;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.text.PyRegex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether text reads as an instruction to a model, by the patterns in {@code hidden.yaml}.
 * Nothing here establishes intent: a match is reported at the tier of evidence it is.
 */
public final class InstructionMatcher {

    /** A match, in code points. */
    public record Match(String patternId, String label, int start, int end) {}

    private record Compiled(String id, String label, Pattern pattern) {}

    private final List<Compiled> compiled = new ArrayList<>();

    public InstructionMatcher(Config.Hidden config) {
        for (Config.InstructionPattern pattern : config.patterns()) {
            for (String regex : pattern.regexes()) {
                compiled.add(new Compiled(pattern.id(), pattern.label(),
                        PyRegex.compile(regex, PyRegex.IGNORECASE | PyRegex.MULTILINE)));
            }
        }
    }

    public List<Match> find(String text) {
        List<Match> found = new ArrayList<>();
        for (Compiled c : compiled) {
            Matcher m = c.pattern().matcher(text);
            while (m.find()) {
                found.add(new Match(c.id(), c.label(), text.codePointCount(0, m.start()),
                        text.codePointCount(0, m.end())));
            }
        }
        found.sort(Comparator.comparingInt(Match::start).thenComparingInt(Match::end));
        return found;
    }
}
