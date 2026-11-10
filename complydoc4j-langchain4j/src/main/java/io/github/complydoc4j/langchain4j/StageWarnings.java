package io.github.complydoc4j.langchain4j;

import io.github.complydoc4j.chunks.ChunkInspector;
import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.text.Py;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * What was wrong with what a stage passed on, though nothing threw: a document that loaded
 * as no text, chunks of a few tokens, an empty text embedded, a text longer than the
 * embedding model reads. Each is a warning on the stage whose output shows it.
 */
final class StageWarnings {

    /** An item's text, and the document it came from where that is known. */
    record Sourced(String text, String source) {}

    /** Tokens an embedding model reads of one text; the rest is cut or refused. */
    private static final Map<String, Integer> EMBEDDING_LIMITS = Map.of(
            "text-embedding-3-small", 8191, "text-embedding-3-large", 8191, "text-embedding-ada-002", 8191);
    private static final int TINY_TOKENS = 20;

    private StageWarnings() {}

    static List<Report.StageWarning> of(Recording recording, List<Sourced> items, ChunkInspector.ChunkReport chunks,
            ToIntFunction<String> tokens) {
        return switch (recording.kind) {
            case "load" -> loaded(items);
            case "split" -> chunks == null ? List.of() : split(chunks);
            case "embed", "store" -> sent(recording.kind, items, Measurer.model(recording), tokens);
            default -> List.of();
        };
    }

    static List<Report.StageWarning> loaded(List<Sourced> items) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        items.forEach(i -> grouped.computeIfAbsent(i.source() == null ? "" : i.source(), k -> new ArrayList<>())
                .add(i.text()));
        List<Report.StageWarning> warnings = new ArrayList<>();
        List<String> empty = grouped.entrySet().stream().filter(e -> e.getValue().stream().allMatch(Py::blank))
                .map(Map.Entry::getKey).toList();
        if (!empty.isEmpty()) {
            warnings.add(new Report.StageWarning("empty_document", count(empty.size(), "document") + " loaded no text",
                    named(empty)));
        }
        Map<String, Integer> partly = new LinkedHashMap<>();
        grouped.forEach((source, texts) -> {
            int blank = (int) texts.stream().filter(Py::blank).count();
            if (!empty.contains(source) && texts.size() > 1 && blank > 0) {
                partly.put(source, blank);
            }
        });
        if (!partly.isEmpty()) {
            int pages = partly.values().stream().mapToInt(Integer::intValue).sum();
            warnings.add(new Report.StageWarning("empty_pages", count(pages, "page") + " with no text, in "
                    + count(partly.size(), "document"), named(partly.keySet())));
        }
        return warnings;
    }

    private static List<Report.StageWarning> split(ChunkInspector.ChunkReport report) {
        Map<String, String> wording = new LinkedHashMap<>();
        wording.put("tiny", "under " + TINY_TOKENS + " tokens");
        wording.put("oversized", "over the maximum set");
        wording.put("duplicate", "repeating an earlier chunk");
        List<Report.StageWarning> warnings = new ArrayList<>();
        wording.forEach((flag, words) -> {
            List<ChunkInspector.InspectedChunk> flagged = report.chunks().stream()
                    .filter(c -> c.flags().contains(flag)).toList();
            if (!flagged.isEmpty()) {
                warnings.add(new Report.StageWarning(flag + "_chunks", count(flagged.size(), "chunk") + " " + words,
                        named(flagged.stream().map(ChunkInspector.InspectedChunk::document).toList())));
            }
        });
        return warnings;
    }

    private static List<Report.StageWarning> sent(String kind, List<Sourced> items, String model,
            ToIntFunction<String> tokens) {
        List<Report.StageWarning> warnings = new ArrayList<>();
        List<String> blank = items.stream().filter(i -> Py.blank(i.text())).map(Sourced::source).toList();
        if (!blank.isEmpty()) {
            warnings.add(new Report.StageWarning("empty_texts", count(blank.size(), "empty text") + " "
                    + (kind.equals("embed") ? "embedded" : "stored"), named(blank)));
        }
        Integer limit = model == null ? null : EMBEDDING_LIMITS.get(model);
        if (kind.equals("embed") && limit != null) {
            List<String> over = items.stream().filter(i -> tokens.applyAsInt(i.text()) > limit)
                    .map(Sourced::source).toList();
            if (!over.isEmpty()) {
                warnings.add(new Report.StageWarning("over_token_limit", count(over.size(), "text") + " over " + model
                        + "'s limit of " + String.format(Locale.US, "%,d", limit) + " tokens", named(over)));
            }
        }
        return warnings;
    }

    /** The documents named, once each, in order; items with no source are counted, not named. */
    private static List<String> named(java.util.Collection<String> sources) {
        return new ArrayList<>(new LinkedHashSet<>(sources.stream().filter(Objects::nonNull)
                .filter(s -> !s.isEmpty()).toList()));
    }

    /** "1 page", "3 pages", "1,204 chunks". */
    static String count(int quantity, String singular) {
        return String.format(Locale.US, "%,d %s", quantity, quantity == 1 ? singular : singular + "s");
    }
}
