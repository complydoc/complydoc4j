package io.github.complydoc4j.langchain4j;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentTransformer;
import java.util.ArrayList;
import java.util.List;

/** A document transformer whose every call is a {@code transform} stage. */
final class ObservedDocumentTransformer implements DocumentTransformer, Observation.Observed {

    private final Observation observation;
    private final DocumentTransformer delegate;

    ObservedDocumentTransformer(Observation observation, DocumentTransformer delegate) {
        this.observation = observation;
        this.delegate = delegate;
    }

    @Override
    public Object delegate() {
        return delegate;
    }

    @Override
    public Document transform(Document document) {
        return observation.stage("transform", this, null, "transform", Observation.documents(List.of(document)),
                () -> delegate.transform(document), d -> d == null ? List.of() : Observation.documents(List.of(d)),
                null);
    }

    @Override
    public List<Document> transformAll(List<Document> documents) {
        return observation.stage("transform", this, null, "transformAll", Observation.documents(documents),
                () -> delegate.transformAll(documents),
                // A transformer drops a document by returning null for it.
                out -> Observation.documents(new ArrayList<>(out.stream().filter(d -> d != null).toList())), null);
    }
}
