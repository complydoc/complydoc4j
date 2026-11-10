package io.github.complydoc4j.sensitive;

import java.util.List;

/**
 * One reported hit. Lines count from 1; the column and the length are in code points, as
 * the Python package reports them. {@code revealed} is null unless the run revealed values
 * and the category allows it.
 *
 * <p>{@code evidence} is one of {@code confirmed} (a checksum passed), {@code corroborated}
 * (a label sits beside it), {@code pattern} (the shape alone) and {@code model}.
 */
public record SensitiveMatch(
        String category,
        String label,
        String severity,
        int page,
        int line,
        int column,
        int length,
        String masked,
        String revealed,
        String evidence,
        Double confidence,
        List<String> validatorsPassed,
        String contextTerm,
        String fingerprint) {

    public SensitiveMatch {
        validatorsPassed = List.copyOf(validatorsPassed);
    }

    /** The order the security page sorts by within a severity. Strongest first. */
    public static final List<String> EVIDENCE_ORDER = List.of("confirmed", "corroborated", "pattern", "model");

    /** The fingerprint, or for a match from before fingerprints, its category and masked value. */
    public String key() {
        return fingerprint == null || fingerprint.isEmpty() ? category + ":" + masked : fingerprint;
    }

    SensitiveMatch at(int newLine, int newColumn, int newLength) {
        return new SensitiveMatch(category, label, severity, page, newLine, newColumn, newLength, masked,
                revealed, evidence, confidence, validatorsPassed, contextTerm, fingerprint);
    }
}
