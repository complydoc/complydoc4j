package io.github.complydoc4j.config;

import java.util.List;

/**
 * One kind of identifier, as {@code sensitive.yaml} describes it.
 *
 * @param modelBacked whether a statistical model reads this category rather than a
 *     pattern: it carries a model, or names the {@code ner} detector, itself or in a
 *     fallback. The Java version has no such model, so these categories are reported as
 *     not looked for, and their patterns only cover the text.
 */
public record CategoryConfig(
        boolean enabled,
        boolean silent,
        String label,
        String region,
        String detector,
        String severity,
        List<String> patterns,
        List<String> contextPatterns,
        List<String> validators,
        List<String> contextTerms,
        int contextWindowChars,
        double minConfidence,
        boolean modelBacked) {

    public CategoryConfig {
        patterns = List.copyOf(patterns);
        contextPatterns = List.copyOf(contextPatterns);
        validators = List.copyOf(validators);
        contextTerms = List.copyOf(contextTerms);
    }

    public CategoryConfig withEnabled(boolean value) {
        return new CategoryConfig(value, silent, label, region, detector, severity, patterns,
                contextPatterns, validators, contextTerms, contextWindowChars, minConfidence,
                modelBacked);
    }
}
