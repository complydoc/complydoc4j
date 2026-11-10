package io.github.complydoc4j.report;

import io.github.complydoc4j.chunks.ChunkInspector;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One document, page or chunk as a stage passed it on: its text and its metadata.
 *
 * <p>Framework-free, so the core reads LangChain4j's documents and segments, or anyone
 * else's, the same way.
 */
public record Item(String text, Map<String, Object> metadata) {

    public Item {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** The file the item came from, by the first metadata key loaders use for it. */
    public String source() {
        for (String key : ChunkInspector.SOURCE_KEYS) {
            Object value = metadata.get(key);
            if (value != null && !(value instanceof String s && s.isEmpty())) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** The page, counted from 1: {@code page_number} as it is, {@code page} from 0. */
    public Integer page() {
        if (metadata.get("page_number") instanceof Integer n) {
            return n;
        }
        if (metadata.get("page") instanceof Integer n) {
            return n + 1;
        }
        return null;
    }
}
