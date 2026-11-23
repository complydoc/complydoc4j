package io.github.complydoc4j;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.sensitive.Masking;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import io.github.complydoc4j.text.Py;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Asking a person to see one masked value, as {@code complydoc.unmask} does in Python.
 *
 * <p>Each masked value carries a number, as {@code [#153 IBAN ••••4521]}, and a model asks for
 * it by that number and says why. The person is shown the number, what kind of value it is,
 * the sentence around it, still masked, and the reason, and allows or declines it. Only that
 * one value is given. A category under {@code masking.never_reveal} is never given, allowed or
 * not. Every request is logged, without the value.
 */
public final class Unmask {

    private Unmask() {}

    /** Where each request to see a masked value is recorded, the same file the Python package writes. */
    public static final Path REVEAL_LOG = Path.of(System.getProperty("user.home"), ".complydoc", "reveals.jsonl");

    /** Characters of text shown on each side of a value, for the person deciding. */
    static final int CONTEXT = 90;

    /**
     * One masked value, as a model and a person may see it: never the value itself.
     *
     * @param id the number it carries in the text, {@code #id}
     * @param label what kind of value it is, such as {@code IBAN}
     * @param page the page it is on, for a document; null for text
     * @param context the text around it, masked, with it shown as its marker
     * @param revealable false for a category that is never revealed
     */
    public record MaskedSpan(int id, String label, Integer page, String context, boolean revealable) {
        public String marker() {
            return "#" + id;
        }
    }

    /** What a person is asked about, before one masked value is given. */
    public record RevealRequest(MaskedSpan span, String reason, String source) {

        /** The request in plain words, for a prompt shown to the person; the Python package's words. */
        public String question() {
            String where = source != null ? " in " + Path.of(source).getFileName() : "";
            String page = span.page() != null ? ", page " + span.page() : "";
            String why = reason == null || Py.blank(reason) ? "(no reason given)" : Py.strip(reason);
            // The question and the reason come first: a client may show only the first lines.
            return "Allow the assistant to see " + span.marker() + " (" + span.label() + page + ")" + where
                    + "? Only this value is revealed.\n"
                    + "Reason given: " + why + "\n"
                    + "“" + span.context() + "”";
        }
    }

    /** A request that cannot be put to the person: no such number, or a value never revealed. */
    public static final class UnmaskException extends RuntimeException {
        public UnmaskException(String message) {
            super(message);
        }
    }

    /** A masked stretch of one page's text, in code points, with the original characters under it. */
    record Stretch(Integer page, int start, int end, String value, List<String> labels, List<String> categories) {}

    static List<Stretch> stretches(Integer page, String original, List<Masking.Placed> placed) {
        List<Stretch> found = new ArrayList<>();
        for (Masking.Placed p : placed) {
            LinkedHashSet<String> labels = new LinkedHashSet<>();
            LinkedHashSet<String> categories = new LinkedHashSet<>();
            for (SensitiveMatch match : p.matches()) {
                labels.add(match.label());
                categories.add(match.category());
            }
            found.add(new Stretch(page, p.start(), p.end(), Py.slice(original, p.start(), p.end()),
                    List.copyOf(labels), List.copyOf(categories)));
        }
        found.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return found;
    }

    /** The masked values of one reading, by number. Kept in memory, never written. */
    static final class Values {
        private final Map<Integer, MaskedSpan> spans = new LinkedHashMap<>();
        private final Map<Integer, Stretch> stretches = new LinkedHashMap<>();

        /** Number the stretches 1, 2, 3… in the order given (reading order), with the text around each. */
        static Values number(List<Stretch> ordered, Map<Integer, String> texts, Config.Masking config) {
            Values values = new Values();
            int id = 0;
            for (Stretch stretch : ordered) {
                id++;
                String text = texts.getOrDefault(stretch.page() == null ? 0 : stretch.page(), "");
                boolean revealable = stretch.categories().stream().allMatch(c -> Masking.revealAllowed(c, config));
                values.spans.put(id, new MaskedSpan(id, stretch.labels().get(0), stretch.page(),
                        context(text, stretch, id), revealable));
                values.stretches.put(id, stretch);
            }
            return values;
        }

        List<MaskedSpan> spans() {
            return List.copyOf(spans.values());
        }

        boolean isEmpty() {
            return spans.isEmpty();
        }

        MaskedSpan span(int number) {
            MaskedSpan found = spans.get(number);
            if (found == null) {
                if (spans.isEmpty()) {
                    throw new UnmaskException("Nothing in this text is masked.");
                }
                throw new UnmaskException("There is no masked value #" + number + "; they run from #1 to #"
                        + spans.size() + ".");
            }
            return found;
        }

        /** The value itself, its line breaks made spaces. Only for a request allowed. */
        String value(int number) {
            return Py.collapse(stretches.get(number).value());
        }

        /** A page's masked text with each value shown as {@code [#n LABEL •••]}. */
        String marked(Integer page, String text) {
            StringBuilder out = new StringBuilder();
            int position = 0;
            for (Map.Entry<Integer, Stretch> entry : stretches.entrySet()) {
                Stretch stretch = entry.getValue();
                if (!java.util.Objects.equals(stretch.page(), page)) {
                    continue;
                }
                out.append(Py.slice(text, position, stretch.start()));
                out.append(marker(entry.getKey(), stretch.labels().get(0), Py.slice(text, stretch.start(), stretch.end())));
                position = stretch.end();
            }
            out.append(Py.slice(text, position, Py.len(text)));
            return out.toString();
        }
    }

    static String marker(int id, String label, String masked) {
        return "[#" + id + " " + label + " " + masked + "]";
    }

    private static String context(String text, Stretch stretch, int id) {
        int length = Py.len(text);
        String before = Py.slice(text, Math.max(0, stretch.start() - CONTEXT), stretch.start());
        String after = Py.slice(text, stretch.end(), Math.min(length, stretch.end() + CONTEXT));
        if (stretch.start() > CONTEXT && before.contains(" ")) {
            before = "…" + before.substring(before.indexOf(' ') + 1);
        }
        if (stretch.end() + CONTEXT < length && after.contains(" ")) {
            after = after.substring(0, after.lastIndexOf(' ')) + "…";
        }
        String shown = marker(id, stretch.labels().get(0), Py.slice(text, stretch.start(), stretch.end()));
        return Py.collapse(before + shown + after);
    }

    /**
     * The record of every request to see a masked value, one JSON object a line. It holds when,
     * where, which number, what kind of value, the reason and the answer; never the value.
     */
    public static final class RevealLog {
        private final Path path;

        public RevealLog(Path path) {
            this.path = path;
        }

        public Path path() {
            return path;
        }

        /** {@code decision} is allowed, declined, refused (never revealed) or unavailable. */
        public void record(RevealRequest request, String decision, String asker) {
            if (path == null) {
                return;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().replace("Z", "+00:00"));
            entry.put("document", request.source());
            entry.put("id", request.span().id());
            entry.put("label", request.span().label());
            entry.put("page", request.span().page());
            entry.put("reason", request.reason());
            entry.put("decision", decision);
            entry.put("asker", asker);
            try {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, line(entry) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String line(Map<String, Object> entry) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> field : entry.entrySet()) {
                out.append(first ? "" : ", ");
                first = false;
                string(field.getKey(), out);
                out.append(": ");
                Object value = field.getValue();
                if (value == null) {
                    out.append("null");
                } else if (value instanceof Number n) {
                    out.append(n);
                } else {
                    string(String.valueOf(value), out);
                }
            }
            return out.append('}').toString();
        }

        private static void string(String s, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }
}
