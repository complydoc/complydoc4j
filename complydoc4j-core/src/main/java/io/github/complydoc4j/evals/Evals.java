package io.github.complydoc4j.evals;

import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.text.Py;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Evals on your own documents: the passages a reading of them must contain, the values it
 * must hold however they are grouped, and the passages it must not hold. Read from
 * {@code .complydoc-evals.yaml} at the top of the folder, the file {@code complydoc ui} writes.
 */
public final class Evals {

    public static final String FILENAME = ".complydoc-evals.yaml";
    private static final List<String> KINDS = List.of("text", "value", "absent");

    /** A passage a document is expected to contain, or a value, or a passage it must not. */
    public record Fact(String text, String document, String kind, String label) {}

    /** What a fact came to for each reader: found, scored, where, and the nearest passage. */
    public record FactCheck(String fact, String document, Map<String, String> found, Map<String, Double> scores,
            Map<String, Integer> pages, Map<String, String> documents, Map<String, String> nearest, String kind,
            String label) {}

    /** The report's {@code evals}: the file, whose readings were checked, and each fact. */
    public record Summary(String file, List<String> readers, List<FactCheck> checks, String error) {}

    /** An evals file that cannot be read, or a fact in it that is not valid. */
    public static final class EvalError extends Exception {
        EvalError(String message) {
            super(message);
        }
    }

    private Evals() {}

    /** The evals file at the top of {@code target}, or null. */
    public static Path find(Path target) {
        Path folder = Files.isDirectory(target) ? target : target.toAbsolutePath().getParent();
        Path candidate = folder.resolve(FILENAME);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Fact> load(Path path) throws EvalError {
        Object data;
        try {
            data = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | YAMLException e) {
            throw new EvalError(path + " is not valid YAML: " + e.getMessage());
        }
        if (data == null) {
            return List.of();
        }
        if (!(data instanceof Map<?, ?> map)) {
            throw new EvalError(path + " must hold a mapping with a `facts` list");
        }
        List<Fact> facts = new ArrayList<>();
        Object listed = map.get("facts");
        for (Object entry : listed instanceof List<?> l ? l : List.of()) {
            if (!(entry instanceof Map<?, ?> fact)) {
                throw new EvalError(path + " is not a valid evals file: a fact is a mapping");
            }
            facts.add(fact((Map<String, Object>) fact, path));
        }
        return facts;
    }

    /** A fact as the file writes it, checked as the Python package checks it. */
    private static Fact fact(Map<String, Object> raw, Path path) throws EvalError {
        Map<String, Object> data = new HashMap<>(raw);
        String kind = data.containsKey("kind") ? String.valueOf(data.get("kind")) : "text";
        for (String keyed : List.of("value", "absent")) {
            if (data.containsKey(keyed)) {
                if (data.containsKey("text") || (data.containsKey("kind") && !kind.equals(keyed))) {
                    throw invalid(path, "a fact has one of text, value or absent; " + keyed + " is given");
                }
                data.put("text", data.remove(keyed));
                kind = keyed;
            }
        }
        for (String key : data.keySet()) {
            if (!List.of("text", "kind", "document", "label").contains(key)) {
                throw invalid(path, "a fact has no `" + key + "`");
            }
        }
        if (!KINDS.contains(kind)) {
            throw invalid(path, "a fact's kind is one of text, value, absent");
        }
        String text = Py.collapse(data.get("text") == null ? "" : String.valueOf(scalar(data.get("text"))));
        if (text.isEmpty()) {
            throw invalid(path, "a fact cannot be empty");
        }
        if (!kind.equals("value") && Py.len(text) < 3) {
            throw invalid(path, "a fact is at least three characters of a document's text");
        }
        String label = data.get("label") == null ? null : Py.collapse(String.valueOf(data.get("label")));
        label = label == null || label.isEmpty() ? null : label;
        String document = data.get("document") == null ? null : Py.strip(String.valueOf(data.get("document")));
        document = document == null || document.isEmpty() ? null : document;
        if (kind.equals("value") && Facts.valueReadings(text).isEmpty()) {
            throw invalid(path, "'" + text + "' is not a number");
        }
        if (label != null && !kind.equals("value")) {
            throw invalid(path, "only a value has a label");
        }
        return new Fact(text, document, kind, label);
    }

    /** A number in YAML as Python's {@code str()} writes it, since that is what the fact is. */
    private static Object scalar(Object value) {
        if (value instanceof Double d) {
            return io.github.complydoc4j.report.Json.pyFloat(d);
        }
        return value;
    }

    private static EvalError invalid(Path path, String why) {
        return new EvalError(path + " is not a valid evals file:\n" + why);
    }

    /** Each fact against the documents each reader read. */
    public static List<FactCheck> evaluate(Map<String, List<Report.Document>> readings, List<Fact> facts,
            double threshold) {
        List<FactCheck> checks = new ArrayList<>();
        for (Fact fact : facts) {
            Map<String, String> found = new LinkedHashMap<>();
            Map<String, Double> scores = new LinkedHashMap<>();
            Map<String, Integer> pages = new LinkedHashMap<>();
            Map<String, String> located = new LinkedHashMap<>();
            Map<String, String> nearest = new LinkedHashMap<>();
            readings.forEach((reader, documents) -> {
                Facts.Match best = Facts.Match.NONE;
                String where = null;
                for (Report.Document document : documents) {
                    if (!applies(document, fact.document())) {
                        continue;
                    }
                    List<Facts.Page> pairs = document.extractedText().stream()
                            .map(p -> new Facts.Page(p.number(), p.text())).toList();
                    Facts.Match match = fact.kind().equals("value")
                            ? Facts.findValue(pairs, fact.text(), fact.label())
                            : Facts.findReported(pairs, fact.text(), threshold);
                    boolean better = match.score() > best.score()
                            || ("exact".equals(match.kind()) && !"exact".equals(best.kind()));
                    // A value not found still says what follows its label: keep the first such.
                    if (better || (where == null && match.passage() != null && !match.passage().isEmpty()
                            && (best.passage() == null || best.passage().isEmpty()))) {
                        best = match;
                        where = document.relativePath();
                    }
                    if ("exact".equals(best.kind())) {
                        break;
                    }
                }
                String held = best.kind();
                if (fact.kind().equals("absent")) {
                    // Not finding it is the pass; a near copy of it is a near miss.
                    held = best.kind() == null ? "exact" : best.kind().equals("fuzzy") ? "fuzzy" : null;
                }
                found.put(reader, held);
                scores.put(reader, fact.kind().equals("absent") ? 1.0 - best.score() : best.score());
                pages.put(reader, best.kind() != null ? best.page() : null);
                located.put(reader, best.kind() != null ? where : null);
                nearest.put(reader, !"exact".equals(held) && !"exact".equals(best.kind()) ? best.passage() : null);
            });
            checks.add(new FactCheck(fact.text(), fact.document(), found, scores, pages, located, nearest, fact.kind(),
                    fact.label()));
        }
        return checks;
    }

    /** The facts in {@code file}, checked against the text the documents kept, as one reader read it. */
    public static Summary score(Path file, String reader, List<Report.Document> documents) {
        try {
            List<Fact> facts = load(file);
            return new Summary(file.toString(), List.of(reader),
                    evaluate(Map.of(reader, documents), facts, Facts.FUZZY_THRESHOLD), null);
        } catch (EvalError e) {
            return new Summary(file.toString(), List.of(reader), List.of(), e.getMessage());
        }
    }

    private static boolean applies(Report.Document document, String name) {
        if (name == null) {
            return true;
        }
        Path path = Path.of(document.path());
        return name.equals(document.relativePath()) || name.equals(Path.of(document.relativePath()).getFileName().toString())
                || name.equals(document.path()) || name.equals(path.getFileName().toString());
    }
}
