package io.github.complydoc4j.langchain4j;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.DocumentTransformer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.segment.TextSegmentTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

/**
 * {@link EmbeddingStoreIngestor#builder()}, with every component it is given observed. A
 * pipeline that builds its ingestor this way changes in one line.
 */
public final class ObservedIngestorBuilder extends EmbeddingStoreIngestor.Builder {

    private final Observation observation;

    ObservedIngestorBuilder(Observation observation) {
        this.observation = observation;
    }

    @Override
    public ObservedIngestorBuilder documentTransformer(DocumentTransformer transformer) {
        super.documentTransformer(transformer == null ? null : observation.transformer(transformer));
        return this;
    }

    @Override
    public ObservedIngestorBuilder documentSplitter(DocumentSplitter splitter) {
        super.documentSplitter(splitter == null ? null : observation.splitter(splitter));
        return this;
    }

    @Override
    public ObservedIngestorBuilder textSegmentTransformer(TextSegmentTransformer transformer) {
        super.textSegmentTransformer(transformer == null ? null : observation.segmentTransformer(transformer));
        return this;
    }

    @Override
    public ObservedIngestorBuilder embeddingModel(EmbeddingModel model) {
        super.embeddingModel(model == null ? null : observation.embeddingModel(model));
        return this;
    }

    @Override
    public ObservedIngestorBuilder embeddingStore(EmbeddingStore<TextSegment> store) {
        super.embeddingStore(store == null ? null : observation.embeddingStore(store));
        return this;
    }

    @Override
    public ObservedIngestorBuilder embeddingInputType(EmbeddingInputType type) {
        super.embeddingInputType(type);
        return this;
    }
}
