package io.github.complydoc4j.chunks;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.tokens.Tokens;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Inspecting the chunks a splitter produced: each scanned for identifiers and hidden
 * content, and flagged when it is tiny, oversized, ends mid-sentence or mid-table, ends on a
 * heading, repeats an earlier chunk, or carries an absolute file path in its metadata.
 */
public final class ChunkInspector {

    public static final Map<String, String> FLAG_MEANINGS;

    static {
        Map<String, String> meanings = new LinkedHashMap<>();
        meanings.put("tiny", "fewer tokens than the minimum");
        meanings.put("oversized", "more tokens than the maximum");
        meanings.put("split_sentence", "ends mid-sentence and the next chunk continues it");
        meanings.put("split_table", "ends inside a table that the next chunk continues");
        meanings.put("heading_at_end", "ends on a heading, separated from its section");
        meanings.put("duplicate", "repeats an earlier chunk");
        meanings.put("path_metadata", "metadata holds an absolute file path");
        FLAG_MEANINGS = Collections.unmodifiableMap(meanings);
    }

    /** Metadata keys loaders name a document's file under, in order. */
    public static final List<String> SOURCE_KEYS = List.of("source", "file_path", "filename", "file_name");

    public static final Pattern ABSOLUTE_PATH = Pattern.compile("^(?:/[^/\\s]+){2,}|^[A-Za-z]:[\\\\/]|^~[/\\\\]");

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?:;)\\]\"'»”…]\\s*$");
    private static final Pattern TABLE_LINE = Pattern.compile("(\\|.*\\|)|(\\t.*\\t)");
    private static final int PREVIEW = 120;
    private static final int MIN_TOKENS = 20;
    private static final int TOP_K = 5;

    /**
     * @param start where the chunk starts in its page's text, in code points; null where the
     *     splitter changed the text, so it cannot be found there
     */
    public record InspectedChunk(int index, String document, Integer page, int characters, int tokens,
            List<String> identifiers, int hidden, List<String> flags, List<String> metadataKeys, String preview,
            Integer start, Integer end, Integer pageCharacters) {}

    public record ChunkStats(int count, int tokensTotal, int tokensMin, double tokensMedian, double tokensP95,
            int tokensMax) {}

    /** {@code facts} and {@code retrieval} are always empty here: evals are not ported yet. */
    public record ChunkReport(String chunker, List<InspectedChunk> chunks, ChunkStats stats, String tokenEncoding,
            String tokenFidelity, Map<String, Integer> flagCounts, Map<String, String> flagMeanings,
            Map<String, Integer> repeatedIdentifiers, List<Object> facts, List<Object> retrieval, int topK) {}

    private ChunkInspector() {}

    /**
     * Inspect {@code chunks}, the splitter's output; {@code given} is what it was given, so
     * each chunk can be placed in its page's text.
     */
    public static ChunkReport inspect(String name, List<Item> chunks, List<Item> given, Config config) {
        Map<String, String> pages = new HashMap<>();
        for (Item item : given) {
            pages.putIfAbsent(key(item.source(), item.page()), item.text());
        }
        Set<String> seen = new HashSet<>();
        Map<String, Integer> cursors = new HashMap<>();
        List<InspectedChunk> inspected = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            Item chunk = chunks.get(index);
            String text = chunk.text();
            String document = chunk.source();
            Item following = index + 1 < chunks.size() ? chunks.get(index + 1) : null;
            String nextText = following != null && Objects.equals(following.source(), document)
                    ? following.text() : null;
            int tokens = Tokens.count(text);
            List<SensitiveMatch> matches = Scanner.scanText(text, config.sensitive()).matches();
            List<String> flags = new ArrayList<>();
            if (tokens < MIN_TOKENS) {
                flags.add("tiny");
            }
            if (nextText != null && endsMidSentence(text, nextText)) {
                flags.add("split_sentence");
            }
            if (nextText != null && endsMidTable(text, nextText)) {
                flags.add("split_table");
            }
            if (endsOnHeading(text)) {
                flags.add("heading_at_end");
            }
            String normalised = Py.collapse(text);
            if (!normalised.isEmpty() && seen.contains(normalised)) {
                flags.add("duplicate");
            }
            seen.add(normalised);
            if (chunk.metadata().values().stream()
                    .anyMatch(v -> v instanceof String s && ABSOLUTE_PATH.matcher(s).lookingAt())) {
                flags.add("path_metadata");
            }
            int hidden = 0;
            for (ContentFinding finding : HiddenCheck.findHidden(text, config, false)) {
                if (finding.severity().equals("medium") || finding.severity().equals("high")) {
                    hidden++;
                }
            }
            String key = key(document, chunk.page());
            String pageText = pages.get(key);
            int[] place = placed(text, pageText, cursors.getOrDefault(key, 0));
            if (place != null) {
                cursors.put(key, place[0] + 1);
            }
            List<String> identifiers = new ArrayList<>();
            matches.forEach(m -> identifiers.add(m.label() + ": " + m.masked()));
            inspected.add(new InspectedChunk(index, document, chunk.page(), Py.len(text), tokens, identifiers, hidden,
                    flags, new ArrayList<>(new java.util.TreeSet<>(chunk.metadata().keySet())),
                    maskedPreview(text, config),
                    place != null ? place[0] : null,
                    place != null ? place[1] : null,
                    pageText != null && place != null ? Py.len(pageText) : null));
        }

        Map<String, Integer> counts = new TreeMap<>();
        for (InspectedChunk chunk : inspected) {
            for (String value : new HashSet<>(chunk.identifiers())) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        Map<String, Integer> repeated = new LinkedHashMap<>();
        counts.forEach((value, n) -> {
            if (n > 1) {
                repeated.put(value, n);
            }
        });
        Map<String, Integer> flagCounts = new LinkedHashMap<>();
        for (String flag : FLAG_MEANINGS.keySet()) {
            flagCounts.put(flag, (int) inspected.stream().filter(c -> c.flags().contains(flag)).count());
        }
        List<Integer> tokens = new ArrayList<>(inspected.stream().map(InspectedChunk::tokens).toList());
        Collections.sort(tokens);
        return new ChunkReport(name, inspected, stats(tokens), Tokens.ENCODING, Tokens.FIDELITY, flagCounts,
                FLAG_MEANINGS, repeated, List.of(), List.of(), TOP_K);
    }

    private static String key(String source, Integer page) {
        return source + "\0" + page;
    }

    /** Where {@code text} is in {@code page}, in code points, looking from {@code cursor} on. */
    private static int[] placed(String text, String page, int cursor) {
        if (page == null || text.isEmpty()) {
            return null;
        }
        int at = page.indexOf(text, Py.utf16(page, cursor));
        if (at < 0) {
            at = page.indexOf(text);
        }
        if (at < 0) {
            return null;
        }
        int start = Py.cp(page, at);
        return new int[] {start, start + Py.len(text)};
    }

    private static String maskedPreview(String text, Config config) {
        return Py.head(Py.collapse(Scanner.maskText(Py.head(text, PREVIEW * 2), config).text()), PREVIEW);
    }

    private static boolean endsMidSentence(String text, String following) {
        String stripped = Py.rstrip(text);
        String start = Py.lstrip(following);
        return !stripped.isEmpty() && !start.isEmpty() && !SENTENCE_END.matcher(stripped).find()
                && Character.isLowerCase(start.codePointAt(0));
    }

    private static String lastLine(String text) {
        List<String> lines = Py.splitlines(text).stream().filter(l -> !Py.blank(l)).toList();
        return lines.isEmpty() ? "" : Py.strip(lines.get(lines.size() - 1));
    }

    private static boolean endsMidTable(String text, String following) {
        String first = Py.splitlines(following).stream().filter(l -> !Py.blank(l)).findFirst().orElse("");
        return TABLE_LINE.matcher(lastLine(text)).find() && TABLE_LINE.matcher(first).find();
    }

    private static boolean endsOnHeading(String text) {
        List<String> lines = Py.splitlines(text).stream().map(Py::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.size() < 2) {
            return false;
        }
        String last = lines.get(lines.size() - 1);
        if (last.startsWith("#")) {
            return true;
        }
        int words = Py.split(last).size();
        return words > 0 && words <= 8 && Py.isUpper(last) && !SENTENCE_END.matcher(last).find();
    }

    private static ChunkStats stats(List<Integer> tokens) {
        if (tokens.isEmpty()) {
            return new ChunkStats(0, 0, 0, 0.0, 0.0, 0);
        }
        int n = tokens.size();
        int p95 = tokens.get(Math.min(n - 1, (int) Math.rint(0.95 * (n - 1))));
        double median = n % 2 == 1 ? tokens.get(n / 2) : (tokens.get(n / 2 - 1) + tokens.get(n / 2)) / 2.0;
        return new ChunkStats(n, tokens.stream().mapToInt(Integer::intValue).sum(), tokens.get(0), median, p95,
                tokens.get(n - 1));
    }
}
