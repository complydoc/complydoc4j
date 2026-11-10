package io.github.complydoc4j.langchain4j;

import io.github.complydoc4j.chunks.ChunkInspector;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.report.Json;
import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.sensitive.Masking;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.tokens.Tokens;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a stage's items weigh, and the few of them the viewer shows, masked. */
final class Measurer {

    /** Settings an embedding class names its model under. */
    private static final List<String> MODEL_KEYS =
            List.of("model", "model_name", "deployment", "deployment_name", "model_id");
    private static final int TEXT = 600;
    private static final int VALUE = 120;
    private static final int KEYS = 12;

    private final Config settings;
    private final boolean reveal;
    private final int previews;
    private final Map<String, Integer> counted = new HashMap<>();
    private final Map<String, String> masked = new HashMap<>();
    /** Categories masking could not look for, by id, with why. */
    final Map<String, String> unscanned = new LinkedHashMap<>();

    Measurer(Config settings, boolean reveal, int previews) {
        this.settings = settings;
        this.reveal = reveal;
        this.previews = previews;
    }

    /** Tokens in {@code texts}, each distinct text counted once. */
    int tokens(List<String> texts) {
        int total = 0;
        for (String text : texts) {
            total += counted.computeIfAbsent(text, Tokens::count);
        }
        return total;
    }

    /** The model a stage names in its settings, such as an embedding model's. */
    static String model(Recording recording) {
        for (String key : MODEL_KEYS) {
            Object value = recording.parameters.get(key);
            if (value != null && !String.valueOf(value).isEmpty()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * What an embedding step cost, and on what basis. A step that reached no host ran here and
     * cost nothing; the Java version carries no price table, so one that did is unpriced.
     */
    Object[] cost(Recording recording) {
        if (!recording.kind.equals("embed")) {
            return new Object[] {null, null};
        }
        if (recording.connections.isEmpty()) {
            return new Object[] {0.0, "local"};
        }
        return new Object[] {null, "unpriced"};
    }

    List<Report.StagePreview> preview(List<Item> items) {
        List<Report.StagePreview> out = new ArrayList<>();
        for (Item item : items.subList(0, Math.min(previews, items.size()))) {
            Map<String, String> values = new LinkedHashMap<>();
            int keys = 0;
            for (Map.Entry<String, Object> entry : item.metadata().entrySet()) {
                if (keys++ >= KEYS) {
                    break;
                }
                values.put(entry.getKey(), Py.head(mask(path(asText(entry.getValue()))), VALUE));
            }
            out.add(new Report.StagePreview(item.source(), item.page(), Py.len(item.text()),
                    tokens(List.of(item.text())), Py.head(mask(Py.head(item.text(), TEXT * 2)), TEXT), values));
        }
        return out;
    }

    /** An absolute path as {@code /…/} and its file name: the folders it spells out are not kept. */
    private static String path(String value) {
        if (!ChunkInspector.ABSOLUTE_PATH.matcher(value).lookingAt()) {
            return value;
        }
        Path name = Path.of(value).getFileName();
        return "/…/" + (name == null ? "" : name.toString());
    }

    /** {@code text} with identifiers masked, unless the run revealed them. */
    String mask(String text) {
        if (reveal) {
            return text;
        }
        return masked.computeIfAbsent(text, t -> {
            Scanner.TextScan scan = Scanner.scanText(t, settings.sensitive(), false, true);
            unscanned.putAll(scan.unscanned());
            return Masking.maskMatches(t, scan.matches()).text();
        });
    }

    static String asText(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Double || value instanceof Float) {
            return Json.pyFloat(((Number) value).doubleValue());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        // json.dumps(value, default=str): anything else is written as its string, quoted.
        return Json.write(String.valueOf(value)).strip();
    }
}
