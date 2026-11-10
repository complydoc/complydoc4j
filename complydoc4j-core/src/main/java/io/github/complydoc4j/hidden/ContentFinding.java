package io.github.complydoc4j.hidden;

import java.util.List;

/**
 * A passage that is hidden, reads as an instruction to a model, or both.
 *
 * @param visibility {@code confirmed}, {@code suspected}, {@code visible} or
 *     {@code not_measured}
 * @param instruction {@code confirmed}, {@code pattern}, {@code model} or {@code none}
 * @param excerpt the passage, identifiers masked unless the run revealed them
 * @param characters the passage's length in code points, its spacing collapsed
 * @param inLoaderOutput whether the text a loader handed over holds it; null without a loader
 */
public record ContentFinding(
        Integer page,
        String visibility,
        String instruction,
        String severity,
        String excerpt,
        int characters,
        List<String> hiddenReasons,
        List<String> instructionReasons,
        Double score,
        Boolean inLoaderOutput,
        String fingerprint) {

    public ContentFinding {
        hiddenReasons = List.copyOf(hiddenReasons);
        instructionReasons = List.copyOf(instructionReasons);
    }

    public ContentFinding onPage(Integer number) {
        return new ContentFinding(number, visibility, instruction, severity, excerpt, characters, hiddenReasons,
                instructionReasons, score, inLoaderOutput, fingerprint);
    }
}
