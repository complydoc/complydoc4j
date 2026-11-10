package io.github.complydoc4j.langchain4j;

import io.github.complydoc4j.report.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A stage as it was seen, before the block ends and it is scanned. */
final class Recording {

    final int index;
    final String kind;
    final String component;
    String module;
    final String method;
    final List<String> tags = List.of("LangChain4j");
    final Map<String, Object> parameters;
    final Integer parent;
    /** Seconds from the start of the block to when the stage was called. */
    final double started;
    /** The component itself, to tell a call it makes on itself from a call it makes out. */
    final Object owner;

    List<Item> inputs;
    List<Item> outputs;
    Integer vectors;
    Integer dimensions;
    /** For an embedding store, the IDs it stored what it was given under, in the same order. */
    List<String> ids;
    double seconds;
    /** Where the component sends what it is given, read off its settings. */
    final List<String> connections = new ArrayList<>();
    boolean finished = true;
    String error;
    String traceback;

    Recording(int index, String kind, Object owner, String component, String method, Map<String, Object> parameters,
            Integer parent, double started) {
        this.index = index;
        this.kind = kind;
        this.owner = owner;
        this.component = component != null ? component : nameOf(owner.getClass());
        this.module = owner.getClass().getPackageName();
        this.method = method;
        this.parameters = parameters;
        this.parent = parent;
        this.started = started;
    }

    static String nameOf(Class<?> type) {
        return type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
    }
}
