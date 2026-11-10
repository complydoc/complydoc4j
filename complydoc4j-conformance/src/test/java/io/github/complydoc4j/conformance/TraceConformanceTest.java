package io.github.complydoc4j.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.complydoc4j.langchain4j.Observation;
import io.github.complydoc4j.report.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The report of an observed LangChain4j ingestion, against the report Python's
 * {@code cd.observe} wrote for the same pipeline: split at blank lines, embedded by a fixed
 * local model, kept in an in-memory store.
 *
 * <p>Compared field by field, leaving out what differs on purpose: timings, versions, IDs,
 * component and module names, the stage order (LangChain embeds inside the store's call,
 * LangChain4j before it) and the reason a name category was not looked for.
 */
class TraceConformanceTest {

    /** Each paragraph a chunk, as the Python script's splitter. */
    static final class ParagraphSplitter implements DocumentSplitter {
        private static final Pattern BREAK = Pattern.compile("\\n\\s*\\n", Pattern.UNICODE_CHARACTER_CLASS);

        @Override
        public List<TextSegment> split(Document document) {
            List<TextSegment> out = new ArrayList<>();
            for (String part : BREAK.split(document.text(), -1)) {
                String text = io.github.complydoc4j.text.Py.strip(part);
                if (!text.isEmpty()) {
                    out.add(TextSegment.from(text, document.metadata().copy()));
                }
            }
            return out;
        }
    }

    /** Eight numbers for any text: what LangChain's DeterministicFakeEmbedding(size=8) stands for. */
    static final class FixedEmbedding implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream().map(s -> new Embedding(new float[8])).toList());
        }

        @Override
        public int dimension() {
            return 8;
        }
    }

    @TestFactory
    Stream<DynamicTest> theTraceMatchesPythons() {
        return Golden.fixtures().stream().flatMap(this::fixture);
    }

    @SuppressWarnings("unchecked")
    private Stream<DynamicTest> fixture(String fixture) {
        Map<String, Object> python = (Map<String, Object>) Golden.read(
                Golden.ROOT.resolve("reports").resolve(fixture + ".json"));
        String text = Golden.text(Golden.ROOT.resolve("texts").resolve(fixture + ".txt"));

        Observation observation = Observation.start(fixture,
                Observation.Options.defaults().withOut(null).withPrintSummary(false));
        try (observation) {
            Document document = Document.from(text, Metadata.from("source", fixture + ".txt"));
            observation.ingestor()
                    .documentSplitter(new ParagraphSplitter())
                    .embeddingModel(new FixedEmbedding())
                    .embeddingStore(new InMemoryEmbeddingStore<>())
                    .build()
                    .ingest(document);
        }
        assertNotNull(observation.report(), observation.error());
        // Kept for the checks only Python can make: the schema, the reader, the viewer.
        Json.write(observation.report(), java.nio.file.Path.of("target", "reports", fixture + ".json"));
        Map<String, Object> java = (Map<String, Object>) Json.normalise(observation.report());

        List<DynamicTest> tests = new ArrayList<>();
        tests.add(check(fixture, "documents", documents(python), documents(java)));
        tests.add(check(fixture, "chunks", chunks(python), chunks(java)));
        for (String kind : List.of("split", "embed", "store")) {
            tests.add(check(fixture, "the " + kind + " stage", stage(python, kind), stage(java, kind)));
        }
        tests.add(check(fixture, "aggregate", aggregate(python), aggregate(java)));
        tests.add(check(fixture, "what was not looked for",
                ((Map<String, Object>) ((Map<String, Object>) python.get("trace")).get("unscanned")).keySet(),
                ((Map<String, Object>) ((Map<String, Object>) java.get("trace")).get("unscanned")).keySet()));
        return tests.stream();
    }

    private static DynamicTest check(String fixture, String part, Object expected, Object actual) {
        return DynamicTest.dynamicTest(fixture + ": " + part,
                () -> assertEquals(Json.write(expected), Json.write(actual)));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> documents(Map<String, Object> report) {
        List<Object> out = new ArrayList<>();
        for (Object item : (List<Object>) report.get("documents")) {
            Map<String, Object> d = (Map<String, Object>) item;
            Map<String, Object> sensitive = (Map<String, Object>) d.get("sensitive");
            List<Object> unscanned = new ArrayList<>();
            for (Object u : (List<Object>) sensitive.get("unscanned_categories")) {
                unscanned.add(((Map<String, Object>) u).get("category"));
            }
            List<Object> pages = new ArrayList<>();
            for (Object p : (List<Object>) d.get("extracted_text")) {
                Map<String, Object> page = (Map<String, Object>) p;
                pages.add(pick(page, "number", "source", "characters", "text", "kept"));
            }
            Map<String, Object> kept = pick(d, "path", "relative_path", "sha256", "format", "page_count",
                    "page_count_known", "metadata_findings", "content_findings", "visibility_checked",
                    "visibility_note", "path_exposures");
            kept.put("matches", sensitive.get("matches"));
            kept.put("silent", sensitive.get("silent"));
            kept.put("unscanned_categories", unscanned);
            kept.put("pages_scanned", sensitive.get("pages_scanned"));
            kept.put("extracted_text", pages);
            kept.put("extractions", ((List<Object>) d.get("extractions")).stream()
                    .map(e -> pick((Map<String, Object>) e, "characters", "granularity")).toList());
            out.add(kept);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> chunks(Map<String, Object> report) {
        List<Object> out = new ArrayList<>();
        for (Object item : (List<Object>) report.get("chunks")) {
            out.add(pick((Map<String, Object>) item, "chunks", "stats", "flag_counts", "flag_meanings",
                    "repeated_identifiers", "token_encoding", "facts", "retrieval", "top_k"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stage(Map<String, Object> report, String kind) {
        for (Object item : (List<Object>) ((Map<String, Object>) report.get("trace")).get("stages")) {
            Map<String, Object> stage = (Map<String, Object>) item;
            if (stage.get("kind").equals(kind)) {
                return pick(stage, "documents_in", "documents_out", "characters_in", "characters_out", "sources",
                        "scanned", "identifiers", "hidden", "metadata_keys", "metadata_keys_added", "path_keys",
                        "hosts", "vectors", "dimensions", "tokens_in", "tokens_out", "usd", "usd_basis", "previews",
                        "documents", "warnings", "finished", "error");
            }
        }
        return Map.of("missing", kind);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> aggregate(Map<String, Object> report) {
        Map<String, Object> aggregate = pick((Map<String, Object>) report.get("aggregate"), "documents_audited",
                "pages_total", "pages_unreadable", "formats", "sensitive_by_category", "sensitive_by_severity",
                "sensitive_total", "documents_with_sensitive_data", "content_matrix", "content_findings_total",
                "content_findings_high", "documents_with_content_findings", "documents_visibility_unchecked");
        aggregate.put("categories_not_scanned",
                ((Map<String, Object>) ((Map<String, Object>) report.get("aggregate")).get("categories_not_scanned"))
                        .keySet());
        return aggregate;
    }

    private static Map<String, Object> pick(Map<String, Object> map, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            out.put(key, map.get(key));
        }
        return out;
    }
}
