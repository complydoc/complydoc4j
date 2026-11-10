package io.github.complydoc4j.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The configuration files, loaded together: the same {@code sensitive.yaml} and
 * {@code hidden.yaml} the Python package ships.
 *
 * @param digest SHA-256 over the raw files, the first 16 hex characters
 */
public record Config(Sensitive sensitive, Hidden hidden, String sourceDir, String digest) {

    public record Masking(int revealTailChars, String maskChar, List<String> neverReveal) {
        public Masking {
            neverReveal = List.copyOf(neverReveal);
        }
    }

    public record Sensitive(int schemaVersion, Masking masking, Map<String, CategoryConfig> categories) {
        public Sensitive {
            categories = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(categories));
        }

        public Map<String, CategoryConfig> enabledCategories() {
            Map<String, CategoryConfig> enabled = new LinkedHashMap<>();
            categories.forEach((id, category) -> {
                if (category.enabled()) {
                    enabled.put(id, category);
                }
            });
            return enabled;
        }
    }

    /**
     * How hidden text is judged: what a page is drawn at, how little contrast under a glyph
     * means nothing is drawn there, and what counts as white, tiny, or a passage at all.
     */
    public record Visibility(int renderDpi, int minContrast, double minFontSizePt, int minCharacters,
            double minHiddenShare, double nearWhite, int zeroWidthRun, int maxPages) {}

    public record InstructionPattern(String id, String label, List<String> languages, List<String> regexes) {
        public InstructionPattern {
            languages = List.copyOf(languages);
            regexes = List.copyOf(regexes);
        }
    }

    public record Hidden(int schemaVersion, Visibility visibility, double classifierThreshold,
            List<InstructionPattern> patterns) {
        public Hidden {
            patterns = List.copyOf(patterns);
        }
    }

    /**
     * This configuration with the categories a model reads turned off: what a trace scans
     * every stage with but the documents loaded and what was sent or stored.
     */
    public Config patternsOnly() {
        Map<String, CategoryConfig> categories = new LinkedHashMap<>();
        sensitive.categories().forEach((id, category) ->
                categories.put(id, category.modelBacked() ? category.withEnabled(false) : category));
        return new Config(new Sensitive(sensitive.schemaVersion(), sensitive.masking(), categories),
                hidden, sourceDir, digest);
    }
}
