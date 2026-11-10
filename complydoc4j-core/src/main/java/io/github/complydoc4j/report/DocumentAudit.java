package io.github.complydoc4j.report;

import io.github.complydoc4j.chunks.ChunkInspector;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.read.DocumentReader;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.tokens.Tokens;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The documents a loader returned, audited as the Python package audits a loader's output:
 * grouped by the file they name, their pages scanned for identifiers and hidden text, their
 * metadata scanned, and their text kept masked.
 */
public final class DocumentAudit {

    /** Metadata values with fewer letters or digits than this are not scanned. */
    private static final int MIN_SCANNABLE = 4;

    private static final Map<String, String> FORMATS = Map.ofEntries(
            Map.entry(".html", "html"), Map.entry(".htm", "html"), Map.entry(".docx", "docx"),
            Map.entry(".eml", "email"), Map.entry(".png", "image"), Map.entry(".jpg", "image"),
            Map.entry(".jpeg", "image"), Map.entry(".tif", "image"), Map.entry(".tiff", "image"),
            Map.entry(".bmp", "image"), Map.entry(".pdf", "pdf"), Map.entry(".pptx", "pptx"),
            Map.entry(".txt", "text"), Map.entry(".md", "markdown"), Map.entry(".markdown", "markdown"),
            Map.entry(".xlsx", "xlsx"), Map.entry(".xlsm", "xlsx"));

    private DocumentAudit() {}

    /** The format a file is reported under, from its extension. */
    public static String formatOf(String path) {
        String name = Path.of(path).getFileName() == null ? path : Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? FORMATS.getOrDefault(name.substring(dot).toLowerCase(Locale.ROOT), "other") : "other";
    }

    public static List<Report.Document> audit(List<Item> items, String loaderName, Config config, boolean reveal,
            boolean keepText) {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item item : items) {
            String key = null;
            for (String k : ChunkInspector.SOURCE_KEYS) {
                if (item.metadata().get(k) instanceof String s) {
                    key = s;
                    break;
                }
            }
            groups.computeIfAbsent(key != null ? key : loaderName, k -> new ArrayList<>()).add(item);
        }
        Map<String, List<SensitiveMatch>> scannedValues = new HashMap<>();
        List<Report.Document> documents = new ArrayList<>();
        groups.forEach((key, members) -> documents.add(document(key, members, loaderName, config, reveal, keepText,
                scannedValues)));
        return documents;
    }

    private static Report.Document document(String key, List<Item> members, String loaderName, Config config,
            boolean reveal, boolean keepText, Map<String, List<SensitiveMatch>> scannedValues) {
        long started = System.nanoTime();
        TreeMap<Integer, List<String>> pages = new TreeMap<>();
        boolean numbered = false;
        for (Item member : members) {
            Integer number = member.page();
            if (number == null) {
                number = (pages.isEmpty() ? 0 : pages.lastKey()) + 1;
            } else {
                numbered = true;
            }
            pages.computeIfAbsent(number, n -> new ArrayList<>()).add(member.text());
        }
        List<String> texts = new ArrayList<>();
        pages.values().forEach(parts -> texts.add(String.join("\n\n", parts)));
        Path path = Path.of(key);
        Source source = new Source(key, key, path, digest(path, members), formatOf(key), numbered, loaderName, "loader",
                true, List.of(), metadataFindings(members, config, reveal, scannedValues), pathKeys(members));
        return fromPages(source, new ArrayList<>(pages.keySet()), texts, config, reveal, keepText, started, 0);
    }

    /** A file read by complydoc4j's own readers, audited as {@code complydoc audit} audits it. */
    public static Report.Document auditFile(Path file, Path root, Config config, boolean reveal, boolean keepText)
            throws IOException {
        long started = System.nanoTime();
        DocumentReader.Read read = DocumentReader.read(file);
        double readSeconds = (System.nanoTime() - started) / 1e9;
        List<Integer> numbers = new ArrayList<>();
        for (int n = 1; n <= read.pages().size(); n++) {
            numbers.add(n);
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        Source source = new Source(file.toString(), relative, file, digest(file, List.of()), read.format(),
                read.pageCountKnown(), DocumentReader.NAME, "native", false, read.warnings(), List.of(), List.of());
        return fromPages(source, numbers, read.pages(), config, reveal, keepText, started, readSeconds);
    }

    /** Where a document's pages came from, and what is known of it before they are read. */
    private record Source(String path, String relative, Path file, String sha256, String format, boolean pageCountKnown,
            String reader, String origin, boolean loaderText, List<String> warnings,
            List<Report.MetadataFinding> metadataFindings, List<String> pathExposures) {}

    private static Report.Document fromPages(Source source, List<Integer> numbers, List<String> texts, Config config,
            boolean reveal, boolean keepText, long started, double readSeconds) {
        long scanStart = System.nanoTime();
        Scanner.PageScan scan = scanNumbered(numbers, texts, config, reveal);
        List<HiddenCheck.Page> checked = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            checked.add(new HiddenCheck.Page(numbers.get(i), texts.get(i)));
        }
        HiddenCheck.Result hidden = HiddenCheck.check(source.file(), checked, config, reveal, source.loaderText());
        double scanSeconds = (System.nanoTime() - scanStart) / 1e9;

        List<Report.PageText> extracted = new ArrayList<>();
        int characters = 0;
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            characters += Py.len(Py.strip(text));
            String shown = reveal ? text : Scanner.maskText(text, config).text();
            Map<String, Map<String, Integer>> tokens = new TreeMap<>();
            tokens.put(source.reader(), Map.of(Tokens.ENCODING, Tokens.count(text)));
            extracted.add(new Report.PageText(numbers.get(i), source.origin(), Py.len(text), keepText ? shown : "", "",
                    false, Map.of(), source.reader(), Map.of(), Map.of(), tokens, Map.of(), null, null, null, null));
        }
        List<Report.Extraction> extractions = List.of(
                new Report.Extraction(source.reader(), characters, null, round(readSeconds, 3), "none", false, 1.0,
                        false));
        double total = (System.nanoTime() - started) / 1e9;
        return new Report.Document(
                source.path(), source.relative(), source.sha256(), source.format(), texts.size(),
                source.pageCountKnown(), source.warnings(), null, null,
                new Report.Sensitive(source.relative(), scan.matches(), scan.silent(), scan.unscannedCategories(),
                        scan.unreadablePages(), scan.pagesScanned(), reveal,
                        reveal ? revealBlocked(config) : List.of(), Map.of()),
                null,
                new Report.Timing(round(readSeconds, 3), round(Math.max(0, total - scanSeconds - readSeconds), 3),
                        round(scanSeconds, 3), round(total, 3), round(total / Math.max(1, texts.size()), 3), null),
                extracted, extractions, source.metadataFindings(), hidden.findings(), List.of(), 0, null,
                hidden.visibilityChecked(), hidden.note(), List.of(), source.pathExposures());
    }

    /** Scan pages whose numbers come from the loader, not from their position. */
    private static Scanner.PageScan scanNumbered(List<Integer> numbers, List<String> texts, Config config,
            boolean reveal) {
        Scanner.PageScan scan = Scanner.scanPages(texts, config.sensitive(), reveal);
        List<SensitiveMatch> matches = renumber(scan.matches(), numbers);
        List<SensitiveMatch> silent = renumber(scan.silent(), numbers);
        List<Integer> unreadable = scan.unreadablePages().stream().map(p -> numbers.get(p - 1)).toList();
        return new Scanner.PageScan(matches, silent, scan.unscannedCategories(), unreadable, scan.pagesScanned());
    }

    private static List<SensitiveMatch> renumber(List<SensitiveMatch> matches, List<Integer> numbers) {
        List<SensitiveMatch> out = new ArrayList<>();
        for (SensitiveMatch m : matches) {
            out.add(new SensitiveMatch(m.category(), m.label(), m.severity(), numbers.get(m.page() - 1), m.line(),
                    m.column(), m.length(), m.masked(), m.revealed(), m.evidence(), m.confidence(),
                    m.validatorsPassed(), m.contextTerm(), m.fingerprint()));
        }
        return out;
    }

    private static List<String> revealBlocked(Config config) {
        return config.sensitive().masking().neverReveal().stream()
                .filter(c -> config.sensitive().enabledCategories().containsKey(c)).toList();
    }

    private static List<Report.MetadataFinding> metadataFindings(List<Item> members, Config config, boolean reveal,
            Map<String, List<SensitiveMatch>> scanned) {
        Set<String> seen = new HashSet<>();
        List<Report.MetadataFinding> found = new ArrayList<>();
        for (Item member : members) {
            for (Map.Entry<String, Object> entry : member.metadata().entrySet()) {
                String rendered = asText(entry.getValue());
                if (!seen.add(entry.getKey() + "\0" + rendered)) {
                    continue;
                }
                if (rendered.codePoints().filter(Py::isAlnum).count() < MIN_SCANNABLE) {
                    continue;
                }
                List<SensitiveMatch> matches = scanned.computeIfAbsent(rendered,
                        r -> Scanner.scanText(r, config.sensitive(), reveal, false).matches());
                for (SensitiveMatch m : matches) {
                    found.add(new Report.MetadataFinding(entry.getKey(), m.category(), m.label(), m.severity(),
                            m.evidence(), m.masked(), m.revealed(), member.page()));
                }
            }
        }
        return found;
    }

    static String asText(Object value) {
        if (value == null || value instanceof Boolean) {
            return "";
        }
        if (value instanceof String || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        if (value instanceof Double || value instanceof Float) {
            return Json.pyFloat(((Number) value).doubleValue());
        }
        return String.valueOf(value);
    }

    static List<String> pathKeys(List<Item> members) {
        Set<String> keys = new TreeSet<>();
        for (Item member : members) {
            member.metadata().forEach((key, value) -> {
                if (value instanceof String s && ChunkInspector.ABSOLUTE_PATH.matcher(s).lookingAt()) {
                    keys.add(key);
                }
            });
        }
        return new ArrayList<>(keys);
    }

    /** The file's hash where the file is readable here, the text's otherwise. */
    private static String digest(Path path, List<Item> members) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        sha.update(buffer, 0, read);
                    }
                    return HexFormat.of().formatHex(sha.digest());
                } catch (IOException e) {
                    sha.reset();
                }
            }
            List<String> texts = members.stream().map(Item::text).toList();
            return HexFormat.of().formatHex(sha.digest(String.join("\n", texts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static double round(double value, int places) {
        return new java.math.BigDecimal(value).setScale(places, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }
}
