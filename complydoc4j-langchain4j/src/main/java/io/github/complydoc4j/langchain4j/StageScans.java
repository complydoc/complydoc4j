package io.github.complydoc4j.langchain4j;

import io.github.complydoc4j.chunks.ChunkInspector;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.report.Json;
import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The scans a trace makes of its stages' texts, each distinct text once per configuration
 * however many stages pass it on, and the identifiers they come to.
 */
final class StageScans {

    private final Map<Config, Map<String, List<SensitiveMatch>>> matches = new IdentityHashMap<>();
    private final Map<Config, Map<String, Integer>> hidden = new IdentityHashMap<>();
    /** Categories that could not be looked for, by id, with why. */
    final Map<String, String> unavailable = new LinkedHashMap<>();

    List<SensitiveMatch> matches(String text, Config config) {
        return matches.computeIfAbsent(config, c -> new HashMap<>()).computeIfAbsent(text, t -> {
            Scanner.TextScan scan = Scanner.scanText(t, config.sensitive(), false, false);
            unavailable.putAll(scan.unscanned());
            return scan.matches();
        });
    }

    /** Passages of {@code text} hidden or instruction-like at medium or high. */
    int hidden(String text, Config config) {
        return hidden.computeIfAbsent(config, c -> new HashMap<>()).computeIfAbsent(text,
                t -> (int) HiddenCheck.findHidden(t, config, false).stream()
                        .filter(f -> f.severity().equals("medium") || f.severity().equals("high")).count());
    }

    /** The matches as one identifier each, with how often it occurs: highest severity first. */
    static List<Report.StageIdentifier> byFingerprint(List<SensitiveMatch> found) {
        Map<String, Report.StageIdentifier> merged = new LinkedHashMap<>();
        for (SensitiveMatch m : found) {
            merged.merge(m.key(), new Report.StageIdentifier(m.key(), m.label(), m.masked(), m.severity(), 1,
                    m.evidence(), List.of()),
                    (seen, again) -> new Report.StageIdentifier(seen.fingerprint(), seen.label(), seen.masked(),
                            seen.severity(), seen.occurrences() + 1, seen.evidence(), seen.items()));
        }
        Map<String, Integer> rank = Map.of("high", 0, "medium", 1, "low", 2);
        List<Report.StageIdentifier> out = new ArrayList<>(merged.values());
        out.sort(Comparator.<Report.StageIdentifier>comparingInt(i -> rank.getOrDefault(i.severity(), 3))
                .thenComparing(Report.StageIdentifier::label).thenComparing(Report.StageIdentifier::masked));
        return out;
    }

    /** What a store kept: each item's text and the metadata it keeps beside it. */
    List<SensitiveMatch> stored(List<Item> items, Config config) {
        List<SensitiveMatch> found = new ArrayList<>();
        items.forEach(item -> found.addAll(matches(values(item.metadata()), config)));
        return found;
    }

    /**
     * The IDs a store kept its items under, in order. An ID that is a path, or in which a
     * pattern finds an identifier, is kept empty: the report never carries the value.
     */
    List<String> keptIds(Recording store, Config config) {
        if (!idsMatch(store)) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String id : store.ids) {
            boolean holds = id == null || ChunkInspector.ABSOLUTE_PATH.matcher(id).lookingAt()
                    || matches(id, config).stream().anyMatch(m -> !m.evidence().equals("model"));
            kept.add(holds ? "" : id);
        }
        return kept;
    }

    /** Each identifier with the positions of the stored items holding it. */
    List<Report.StageIdentifier> withItems(List<Report.StageIdentifier> identifiers, Recording store,
            Config config) {
        if (!idsMatch(store)) {
            return identifiers;
        }
        Map<String, Set<Integer>> holding = new HashMap<>();
        for (int position = 0; position < store.inputs.size(); position++) {
            Item item = store.inputs.get(position);
            for (String part : List.of(item.text(), values(item.metadata()))) {
                for (SensitiveMatch m : matches(part, config)) {
                    holding.computeIfAbsent(m.key(), k -> new TreeSet<>()).add(position);
                }
            }
        }
        return identifiers.stream().map(i -> new Report.StageIdentifier(i.fingerprint(), i.label(), i.masked(),
                i.severity(), i.occurrences(), i.evidence(),
                new ArrayList<>(holding.getOrDefault(i.fingerprint(), Set.of())))).toList();
    }

    /** Only when the store said under which ID it kept each item is an item named. */
    private static boolean idsMatch(Recording store) {
        return store.ids != null && !store.ids.isEmpty() && store.ids.size() == store.inputs.size();
    }

    /** A metadata's plain values as one text, to be scanned as a store keeps them. */
    private static String values(Map<String, Object> metadata) {
        List<String> parts = new ArrayList<>();
        metadata.values().forEach(v -> {
            if (v instanceof String || v instanceof Integer || v instanceof Long) {
                parts.add(String.valueOf(v));
            } else if (v instanceof Double || v instanceof Float) {
                parts.add(Json.pyFloat(((Number) v).doubleValue()));
            }
        });
        return String.join("\n", parts);
    }
}
