package io.github.complydoc4j.langchain4j;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import java.util.List;

/** A splitter whose every call is a {@code split} stage. */
final class ObservedSplitter implements DocumentSplitter, Observation.Observed {

    private final Observation observation;
    private final DocumentSplitter delegate;

    ObservedSplitter(Observation observation, DocumentSplitter delegate) {
        this.observation = observation;
        this.delegate = delegate;
    }

    @Override
    public Object delegate() {
        return delegate;
    }

    @Override
    public List<TextSegment> split(Document document) {
        return observation.stage("split", this, null, "split", Observation.documents(List.of(document)),
                () -> delegate.split(document), Observation::segments, null);
    }

    @Override
    public List<TextSegment> splitAll(List<Document> documents) {
        return observation.stage("split", this, null, "splitAll", Observation.documents(documents),
                () -> delegate.splitAll(documents), Observation::segments, null);
    }
}
