package sample;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.complydoc4j.langchain4j.Observation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A LangChain4j ingestion of three documents, observed: an invoice, a supplier review that
 * hides instructions to a model five ways, and a Portuguese contract. Run it, then
 * {@code complydoc ui} in this folder.
 */
public final class Ingest {

    public static void main(String[] args) {
        Path folder = Path.of(args.length > 0 ? args[0] : "documents");
        try (Observation observation = Observation.start("sample-ingest")) {
            List<Document> documents = new ArrayList<>();
            documents.addAll(observation.load("FileSystemDocumentLoader (text)",
                    () -> FileSystemDocumentLoader.loadDocuments(folder, path -> path.toString().endsWith(".txt"),
                            new TextDocumentParser())));
            DocumentParser pdf = new ApachePdfBoxDocumentParser();
            documents.addAll(observation.load("FileSystemDocumentLoader (PDF)",
                    () -> FileSystemDocumentLoader.loadDocuments(folder, path -> path.toString().endsWith(".pdf"),
                            pdf)));
            observation.ingestor()
                    .documentSplitter(DocumentSplitters.recursive(300, 30))
                    .embeddingModel(new AllMiniLmL6V2EmbeddingModel())
                    .embeddingStore(new InMemoryEmbeddingStore<TextSegment>())
                    .build()
                    .ingest(documents);
        }
    }
}
