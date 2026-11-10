package io.github.complydoc4j.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import io.github.complydoc4j.report.Item;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * An embedding store whose every write is a {@code store} stage: what it kept, beside which
 * metadata, under which IDs. A search is no step of ingestion, and is passed on unrecorded.
 */
final class ObservedEmbeddingStore implements EmbeddingStore<TextSegment>, Observation.Observed {

    private final Observation observation;
    private final EmbeddingStore<TextSegment> delegate;

    ObservedEmbeddingStore(Observation observation, EmbeddingStore<TextSegment> delegate) {
        this.observation = observation;
        this.delegate = delegate;
    }

    @Override
    public Object delegate() {
        return delegate;
    }

    @Override
    public String add(Embedding embedding) {
        return observation.stage("store", this, null, "add", List.of(), () -> delegate.add(embedding), r -> null,
                (recording, id) -> stored(recording, List.of(embedding), id == null ? null : List.of(id)));
    }

    @Override
    public void add(String id, Embedding embedding) {
        observation.stage("store", this, null, "add", List.of(), () -> {
            delegate.add(id, embedding);
            return null;
        }, r -> null, (recording, nothing) -> stored(recording, List.of(embedding), List.of(id)));
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        return observation.stage("store", this, null, "add", items(List.of(segment)),
                () -> delegate.add(embedding, segment), r -> null,
                (recording, id) -> stored(recording, List.of(embedding), id == null ? null : List.of(id)));
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return observation.stage("store", this, null, "addAll", List.of(), () -> delegate.addAll(embeddings),
                r -> null, (recording, ids) -> stored(recording, embeddings, ids));
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        return observation.stage("store", this, null, "addAll", items(segments),
                () -> delegate.addAll(embeddings, segments), r -> null,
                (recording, ids) -> stored(recording, embeddings, ids));
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        observation.stage("store", this, null, "addAll", items(segments), () -> {
            delegate.addAll(ids, embeddings, segments);
            return null;
        }, r -> null, (recording, nothing) -> stored(recording, embeddings, ids));
    }

    @Override
    public List<String> generateIds(int n) {
        return delegate.generateIds(n);
    }

    @Override
    public void remove(String id) {
        delegate.remove(id);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        delegate.removeAll(ids);
    }

    @Override
    public void removeAll(Filter filter) {
        delegate.removeAll(filter);
    }

    @Override
    public void removeAll() {
        delegate.removeAll();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return delegate.search(request);
    }

    private static List<Item> items(List<TextSegment> segments) {
        List<Item> items = new ArrayList<>();
        for (TextSegment segment : segments == null ? List.<TextSegment>of() : segments) {
            items.add(segment == null ? new Item("", java.util.Map.of()) : Observation.item(segment));
        }
        return items;
    }

    private static void stored(Recording recording, List<Embedding> embeddings, List<String> ids) {
        recording.vectors = embeddings == null ? null : embeddings.size();
        recording.ids = ids == null ? null : new ArrayList<>(ids);
    }
}
