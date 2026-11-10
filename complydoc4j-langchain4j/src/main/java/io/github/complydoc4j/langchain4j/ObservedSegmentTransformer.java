package io.github.complydoc4j.langchain4j;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.segment.TextSegmentTransformer;
import java.util.List;

/** A segment transformer whose every call is a {@code transform} stage. */
final class ObservedSegmentTransformer implements TextSegmentTransformer, Observation.Observed {

    private final Observation observation;
    private final TextSegmentTransformer delegate;

    ObservedSegmentTransformer(Observation observation, TextSegmentTransformer delegate) {
        this.observation = observation;
        this.delegate = delegate;
    }

    @Override
    public Object delegate() {
        return delegate;
    }

    @Override
    public TextSegment transform(TextSegment segment) {
        return observation.stage("transform", this, null, "transform", Observation.segments(List.of(segment)),
                () -> delegate.transform(segment), s -> s == null ? List.of() : Observation.segments(List.of(s)),
                null);
    }

    @Override
    public List<TextSegment> transformAll(List<TextSegment> segments) {
        return observation.stage("transform", this, null, "transformAll", Observation.segments(segments),
                () -> delegate.transformAll(segments),
                out -> Observation.segments(out.stream().filter(s -> s != null).toList()), null);
    }
}
