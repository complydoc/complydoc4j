package io.github.complydoc4j.langchain4j;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.DocumentTransformer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.segment.TextSegmentTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.config.ConfigLoader;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.report.Json;
import io.github.complydoc4j.report.Report;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An open, then finished, observation of a LangChain4j ingestion.
 *
 * <pre>{@code
 * try (Observation observation = Observation.start("contracts-ingest")) {
 *     List<Document> documents = observation.load("FileSystemDocumentLoader",
 *             () -> FileSystemDocumentLoader.loadDocuments(folder, parser));
 *     observation.ingestor()
 *             .documentSplitter(DocumentSplitters.recursive(800, 80))
 *             .embeddingModel(model)
 *             .embeddingStore(store)
 *             .build()
 *             .ingest(documents);
 * }
 * }</pre>
 *
 * <p>Each component handed to {@link #ingestor()}, or wrapped by one of the methods here, is
 * a stage. Nothing is scanned while the pipeline runs: the scanning happens when the block
 * closes, so the timings in the trace are the pipeline's own. The report is then written to
 * {@code .complydoc/<name>-<time>.json}, where {@code complydoc ui} finds it.
 *
 * <p>Observing never breaks the pipeline. A failure in complydoc's own bookkeeping is
 * recorded and warned about; what the pipeline throws is recorded and thrown on.
 */
public final class Observation implements AutoCloseable {

    private static final ThreadLocal<Recording> INSIDE = new ThreadLocal<>();

    final String name;
    final Options options;
    final OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
    private final long startedNanos = System.nanoTime();
    final List<Recording> recordings = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean open = true;

    private Report report;
    private Path path;
    private String error;
    private double seconds;

    Observation(String name, Options options) {
        this.name = name;
        this.options = options;
    }

    /** Observe the ingestion run inside the block, with the default options. */
    public static Observation start(String name) {
        return start(name, Options.defaults());
    }

    public static Observation start(String name, Options options) {
        return new Observation(name, options);
    }

    // --- what the pipeline is built from ---------------------------------------------

    /** An {@link EmbeddingStoreIngestor} builder whose every component is observed. */
    public ObservedIngestorBuilder ingestor() {
        return new ObservedIngestorBuilder(this);
    }

    /** Documents loaded by {@code loader}, a load stage named {@code component}. */
    public List<Document> load(String component, Supplier<List<Document>> loader) {
        return stage("load", loader, component, "load", null, loader::get, Observation::documents, null);
    }

    /** One document loaded by {@code loader}, a load stage named {@code component}. */
    public Document loadOne(String component, Supplier<Document> loader) {
        return stage("load", loader, component, "load", null, loader::get, d -> documents(List.of(d)), null);
    }

    public DocumentSplitter splitter(DocumentSplitter splitter) {
        return splitter instanceof ObservedSplitter ? splitter : new ObservedSplitter(this, splitter);
    }

    public DocumentTransformer transformer(DocumentTransformer transformer) {
        return transformer instanceof ObservedDocumentTransformer ? transformer
                : new ObservedDocumentTransformer(this, transformer);
    }

    public TextSegmentTransformer segmentTransformer(TextSegmentTransformer transformer) {
        return transformer instanceof ObservedSegmentTransformer ? transformer
                : new ObservedSegmentTransformer(this, transformer);
    }

    public EmbeddingModel embeddingModel(EmbeddingModel model) {
        return model instanceof ObservedEmbeddingModel ? model : new ObservedEmbeddingModel(this, model);
    }

    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingStore<TextSegment> store) {
        return store instanceof ObservedEmbeddingStore ? store : new ObservedEmbeddingStore(this, store);
    }

    // --- after the block ---------------------------------------------------------------

    /** The report, once the block has closed; null if observing itself failed. */
    public Report report() {
        return report;
    }

    /** Where the report was written, or null if it was kept in memory or not written. */
    public Path path() {
        return path;
    }

    /** Why there is no report, when observing itself failed. */
    public String error() {
        return error;
    }

    /** A few lines on what each stage did. */
    public List<String> summary() {
        return TraceBuilder.summary(this, report, error, path);
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        seconds = (System.nanoTime() - startedNanos) / 1e9;
        try {
            report = new TraceBuilder(this, seconds, null).build();
            if (options.out() != null) {
                path = write(report, options.out());
            }
        } catch (RuntimeException | LinkageError e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("complydoc: the trace of " + name + " was not written: " + error);
        }
        if (options.printSummary()) {
            summary().forEach(System.err::println);
        }
    }

    private Path write(Report written, Path out) {
        String slug = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        String stem = (slug.isEmpty() ? "pipeline" : slug) + "-"
                + startedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = out.resolve(stem + ".json");
        for (int number = 2; Files.exists(target); number++) {
            target = out.resolve(stem + "-" + number + ".json");
        }
        return Json.write(written, target);
    }

    // --- recording -----------------------------------------------------------------------

    /**
     * Run {@code call} as a stage of {@code kind}. A call a component makes on itself, or any
     * call once the block has closed, is run without being recorded.
     */
    <T> T stage(String kind, Object owner, String component, String method, List<Item> inputs, Callable<T> call,
            Function<T, List<Item>> outputs, BiConsumer<Recording, T> after) {
        Recording inside = INSIDE.get();
        if (!open || (inside != null && inside.owner == owner)) {
            return unchecked(call);
        }
        Recording recording;
        try {
            Object target = owner instanceof Observed observed ? observed.delegate() : owner;
            Map<String, Object> parameters = Settings.parameters(target);
            recording = new Recording(recordings.size(), kind, owner, component != null ? component
                    : Recording.nameOf(target.getClass()), method, parameters,
                    inside != null ? inside.index : null, round((System.nanoTime() - startedNanos) / 1e9, 4));
            recording.module = target.getClass().getPackageName();
            recording.inputs = inputs;
            if (kind.equals("embed") || kind.equals("store")) {
                for (String endpoint : Settings.endpoints(target)) {
                    recording.connections.add(endpoint);
                }
            }
            recordings.add(recording);
        } catch (RuntimeException e) {
            System.err.println("complydoc: a stage was not recorded: " + e);
            return unchecked(call);
        }
        INSIDE.set(recording);
        long began = System.nanoTime();
        try {
            T result = call.call();
            recording.seconds = (System.nanoTime() - began) / 1e9;
            try {
                recording.outputs = outputs != null ? outputs.apply(result) : null;
                if (after != null) {
                    after.accept(recording, result);
                }
            } catch (RuntimeException e) {
                System.err.println("complydoc: what a stage returned was not recorded: " + e);
            }
            return result;
        } catch (Exception e) {
            recording.seconds = (System.nanoTime() - began) / 1e9;
            recording.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            recording.traceback = trace.toString();
            throw e instanceof RuntimeException r ? r : new RuntimeException(e);
        } finally {
            INSIDE.set(inside);
        }
    }

    private static <T> T unchecked(Callable<T> call) {
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static double round(double value, int places) {
        return new java.math.BigDecimal(value).setScale(places, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    static Item item(Document document) {
        return new Item(document.text() == null ? "" : document.text(), metadata(document.metadata()));
    }

    static Item item(TextSegment segment) {
        return new Item(segment.text() == null ? "" : segment.text(), metadata(segment.metadata()));
    }

    private static Map<String, Object> metadata(dev.langchain4j.data.document.Metadata metadata) {
        return metadata == null ? Map.of() : new LinkedHashMap<>(metadata.toMap());
    }

    static List<Item> documents(List<Document> documents) {
        return documents == null ? null : documents.stream().map(Observation::item).toList();
    }

    static List<Item> segments(List<TextSegment> segments) {
        return segments == null ? null : segments.stream().map(Observation::item).toList();
    }

    /**
     * How an observation runs.
     *
     * @param out the folder the report is written to; {@code .complydoc} by default, null to
     *     keep it in memory only
     * @param scan what is looked for in each stage's output: {@code patterns} (the default),
     *     {@code full}, or {@code off}. With no name model in the Java version, {@code full}
     *     looks for what {@code patterns} does.
     * @param reveal show identifiers in full, where the category allows it; the report says so
     * @param extractedText keep each loaded page's text, masked, so the viewer can show it with
     *     the chunks drawn over it
     * @param previews how many of each stage's items to keep, masked
     * @param config the configuration; the shipped one when null
     * @param printSummary write a few lines on what each stage did to standard error at the end
     */
    public record Options(Path out, String scan, boolean reveal, boolean extractedText, int previews, Config config,
            boolean printSummary) {

        public Options {
            if (!scan.equals("patterns") && !scan.equals("full") && !scan.equals("off")) {
                throw new IllegalArgumentException("scan is one of patterns, full, off, not " + scan);
            }
        }

        public static Options defaults() {
            return new Options(Path.of(".complydoc"), "patterns", false, true, 20, null, true);
        }

        public Options withOut(Path value) {
            return new Options(value, scan, reveal, extractedText, previews, config, printSummary);
        }

        public Options withScan(String value) {
            return new Options(out, value, reveal, extractedText, previews, config, printSummary);
        }

        public Options withReveal(boolean value) {
            return new Options(out, scan, value, extractedText, previews, config, printSummary);
        }

        public Options withConfig(Config value) {
            return new Options(out, scan, reveal, extractedText, previews, value, printSummary);
        }

        public Options withPrintSummary(boolean value) {
            return new Options(out, scan, reveal, extractedText, previews, config, value);
        }

        Config settings() {
            return config != null ? config : ConfigLoader.shipped();
        }
    }

    /** A decorator around one of the pipeline's components. */
    interface Observed {
        Object delegate();
    }
}
