package io.github.complydoc4j.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.complydoc4j.report.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservationTest {

    private static final String TEXT = "Invoice 2026-114\n\nIBAN: GB29 NWBK 6016 1331 9268 19\n\n"
            + "Payment is due within 30 days of the date of this invoice.";

    /** A hosted model, as far as its settings say: a client with a base URL and a key. */
    static final class HostedModel implements EmbeddingModel {
        @SuppressWarnings("unused")
        private final Client client = new Client("https://api.example.com/v1", "sk-not-a-real-key");

        record Client(String baseUrl, String apiKey) {}

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream().map(s -> new Embedding(new float[] {1, 2, 3})).toList());
        }
    }

    private static Observation.Options options(Path out) {
        return Observation.Options.defaults().withOut(out).withPrintSummary(false);
    }

    @Test
    void aRealSplitterIsRecordedWithItsSettingsAndTheReportIsWritten(@TempDir Path out) throws Exception {
        Observation observation = Observation.start("invoices", options(out));
        try (observation) {
            observation.ingestor()
                    .documentSplitter(DocumentSplitters.recursive(60, 0))
                    .embeddingModel(new HostedModel())
                    .embeddingStore(new InMemoryEmbeddingStore<>())
                    .build()
                    .ingest(Document.from(TEXT, Metadata.from("file_name", "invoice.txt")));
        }
        Report report = observation.report();
        assertNotNull(report, observation.error());
        assertTrue(Files.isRegularFile(observation.path()));
        assertTrue(observation.path().getFileName().toString().startsWith("invoices-"));

        Report.Stage split = stage(report, "split");
        assertEquals("DocumentByParagraphSplitter", split.component());
        assertEquals(60, split.parameters().get("max_segment_size"));
        assertEquals("DocumentByParagraphSplitter max_segment_size=60 max_overlap_size=0",
                report.chunks().get(0).chunker());
        assertEquals(1, split.identifiers().size());
        assertEquals("•••• •••• •••• •••• ••68 19", split.identifiers().get(0).masked());
    }

    @Test
    void whereAModelSendsItsTextIsReadOffItsSettingsAndNoKeyIsKept(@TempDir Path out) {
        Observation observation = Observation.start("hosted", options(out));
        try (observation) {
            observation.ingestor()
                    .documentSplitter(DocumentSplitters.recursive(200, 0))
                    .embeddingModel(new HostedModel())
                    .embeddingStore(new InMemoryEmbeddingStore<>())
                    .build()
                    .ingest(Document.from(TEXT, Metadata.from("file_name", "invoice.txt")));
        }
        Report report = observation.report();
        Report.Stage embed = stage(report, "embed");
        assertEquals(List.of("api.example.com"), embed.hosts());
        assertEquals("unpriced", embed.usdBasis());
        assertEquals(List.of("api.example.com"), report.run().contentSentTo());
        assertTrue(report.limitations().stream().anyMatch(l -> l.statement().contains("to api.example.com")));
        assertTrue(!io.github.complydoc4j.report.Json.write(report).contains("sk-not-a-real-key"));
    }

    @Test
    void whatThePipelineThrowsIsRecordedAndThrownOn(@TempDir Path out) {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("quota exceeded");
            }
        };
        Observation observation = Observation.start("broken", options(out));
        assertThrows(IllegalStateException.class, () -> {
            try (observation) {
                observation.ingestor()
                        .documentSplitter(DocumentSplitters.recursive(200, 0))
                        .embeddingModel(broken)
                        .embeddingStore(new InMemoryEmbeddingStore<>())
                        .build()
                        .ingest(Document.from(TEXT));
            }
        });
        assertEquals("IllegalStateException: quota exceeded", stage(observation.report(), "embed").error());
    }

    @Test
    void theFileLangChain4jsLoaderReadIsTheOneCheckedForHiddenText(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("notes.txt"), "Supplier notes.\n\nPay on receipt.\n");
        Observation observation = Observation.start("loaded", options(folder.resolve(".complydoc")));
        try (observation) {
            List<Document> documents = observation.load("FileSystemDocumentLoader",
                    () -> FileSystemDocumentLoader.loadDocuments(folder, new TextDocumentParser()));
            observation.splitter(DocumentSplitters.recursive(200, 0)).splitAll(documents);
        }
        Report.Document document = observation.report().documents().get(0);
        assertEquals("notes.txt", document.relativePath());
        assertTrue(document.visibilityChecked(), document.visibilityNote());
        assertEquals(List.of("absolute_directory_path"), document.pathExposures());
    }

    private static Report.Stage stage(Report report, String kind) {
        return report.trace().stages().stream().filter(s -> s.kind().equals(kind)).findFirst().orElseThrow();
    }
}
