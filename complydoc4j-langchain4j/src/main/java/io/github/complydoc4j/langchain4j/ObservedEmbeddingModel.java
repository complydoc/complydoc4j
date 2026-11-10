package io.github.complydoc4j.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.request.EmbeddingInput;
import dev.langchain4j.model.embedding.request.EmbeddingParameter;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.request.EmbeddingRequestParameters;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.output.Response;
import io.github.complydoc4j.report.Item;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An embedding model whose every call is an {@code embed} stage: what it was sent, to
 * where, and how many vectors of what size came back.
 */
final class ObservedEmbeddingModel implements EmbeddingModel, Observation.Observed {

    private final Observation observation;
    private final EmbeddingModel delegate;

    ObservedEmbeddingModel(Observation observation, EmbeddingModel delegate) {
        this.observation = observation;
        this.delegate = delegate;
    }

    @Override
    public Object delegate() {
        return delegate;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return observation.stage("embed", this, null, "embedAll", texts(segments.stream().map(TextSegment::text).toList()),
                () -> delegate.embedAll(segments), r -> null, (recording, response) -> vectors(recording,
                        response == null ? null : response.content()));
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        List<String> sent = request.inputs().stream().map(EmbeddingInput::text).toList();
        return observation.stage("embed", this, null, "embed", texts(sent), () -> delegate.embed(request), r -> null,
                (recording, response) -> vectors(recording, response == null ? null : response.embeddings()));
    }

    @Override
    public Response<Embedding> embed(String text) {
        return observation.stage("embed", this, null, "embed", texts(List.of(text)), () -> delegate.embed(text),
                r -> null, (recording, response) -> vectors(recording,
                        response == null ? null : List.of(response.content())));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return observation.stage("embed", this, null, "embed", texts(List.of(segment.text())),
                () -> delegate.embed(segment), r -> null, (recording, response) -> vectors(recording,
                        response == null ? null : List.of(response.content())));
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public List<EmbeddingModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public EmbeddingRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<EmbeddingParameter<?>> supportedParameters() {
        return delegate.supportedParameters();
    }

    @Override
    public Set<ContentType> supportedContentTypes() {
        return delegate.supportedContentTypes();
    }

    /** What an embedding call is sent is bare text; its metadata stays behind. */
    private static List<Item> texts(List<String> texts) {
        return texts.stream().map(t -> new Item(t == null ? "" : t, Map.of())).toList();
    }

    private static void vectors(Recording recording, List<Embedding> embeddings) {
        if (embeddings == null) {
            return;
        }
        recording.vectors = embeddings.size();
        recording.dimensions = embeddings.isEmpty() || embeddings.get(0) == null ? null
                : embeddings.get(0).dimension();
    }
}
