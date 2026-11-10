package io.github.complydoc4j;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.config.ConfigLoader;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.read.DocumentReader;
import io.github.complydoc4j.report.Aggregates;
import io.github.complydoc4j.report.DocumentAudit;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.report.Json;
import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * complydoc for Java, with no framework attached.
 *
 * <pre>{@code
 * Complydoc4j.scanText("IBAN: GB29 NWBK 6016 1331 9268 19")   // the identifiers, masked
 * Complydoc4j.maskText(prompt)                                 // the text with them masked
 * Complydoc4j.findHidden(retrievedChunk)                       // invisible text, planted instructions
 * Complydoc4j.audit(items, "my-loader")                        // a report complydoc ui opens
 * }</pre>
 *
 * <p>The same rules as the Python package, read from the same configuration files, and the
 * same report. Integrations, such as the one for LangChain4j, build on these.
 */
public final class Complydoc4j {

    /** The version a report records, as {@code run.tool_version}: {@code complydoc4j 0.1.0}. */
    public static final String VERSION = "0.1.0";

    public static final String TOOL = "complydoc4j " + VERSION;

    private Complydoc4j() {}

    /** Personal and financial identifiers in {@code text}, masked. */
    public static List<SensitiveMatch> scanText(String text) {
        return Scanner.scanText(text, ConfigLoader.shipped().sensitive()).matches();
    }

    /** {@code text} with each identifier masked, strongest evidence first. */
    public static String maskText(String text) {
        return Scanner.maskText(text, ConfigLoader.shipped()).text();
    }

    /** Invisible characters and instruction-like passages in {@code text}. */
    public static List<ContentFinding> findHidden(String text) {
        return HiddenCheck.findHidden(text, ConfigLoader.shipped(), false);
    }

    /**
     * Audit documents already read, by whatever reader: grouped by the file each names in its
     * metadata ({@code source}, {@code file_path}, {@code filename} or {@code file_name}), and
     * scanned for identifiers and hidden text.
     */
    public static Report audit(List<Item> documents, String reader) {
        return audit(documents, reader, ConfigLoader.shipped());
    }

    public static Report audit(List<Item> documents, String reader, Config config) {
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
        long began = System.nanoTime();
        List<Report.Document> audited = DocumentAudit.audit(documents, reader, config, false, true);
        double seconds = (System.nanoTime() - began) / 1e9;
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
        Config.Masking masking = config.sensitive().masking();
        Report.Run run = Report.Run.of(started.format(format),
                started.plusNanos((long) (seconds * 1e9)).format(format), seconds, reader, config, false, true,
                List.of());
        return new Report(run, audited, List.of(), null, Aggregates.of(audited), List.of(Report.Limitation.java()),
                List.of(), Map.of(), new Report.Masking(masking.revealTailChars(), masking.maskChar(),
                        masking.neverReveal()), null, List.of(), null, null, null, null, null, null, null, null, null,
                null, Report.Thresholds.shipped());
    }

    /** Formats the Python package reads and complydoc4j does not yet: listed as skipped, never left out silently. */
    private static final List<String> NOT_YET = List.of("html", "email", "pptx", "image");

    /**
     * Audit a folder, or one file, as {@code complydoc audit} does with {@code --security}:
     * every PDF, Word, Excel, text and Markdown file in it, its subfolders included, read by
     * complydoc4j's own readers and checked for identifiers and hidden text.
     */
    public static Report audit(Path target) throws IOException {
        return audit(target, ConfigLoader.shipped(), false);
    }

    public static Report audit(Path target, Config config, boolean reveal) throws IOException {
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
        long began = System.nanoTime();
        Path root = Files.isDirectory(target) ? target : target.toAbsolutePath().getParent();
        List<Path> files;
        if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                files = walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(p -> root.relativize(p).toString())).toList();
            }
        } else {
            files = List.of(target.toAbsolutePath());
        }
        List<Report.Document> documents = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String format = DocumentAudit.formatOf(file.toString());
            if (!DocumentReader.supported(file)) {
                if (NOT_YET.contains(format)) {
                    skipped.add(new Skip(relative, "unsupported", "complydoc4j has no reader for " + format
                            + " files yet; complydoc in Python reads them"));
                }
                continue;
            }
            try {
                documents.add(DocumentAudit.auditFile(file, root, config, reveal, true));
            } catch (IOException | RuntimeException e) {
                skipped.add(new Skip(relative, "unreadable", e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        double seconds = (System.nanoTime() - began) / 1e9;
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
        Config.Masking masking = config.sensitive().masking();
        Report.Run run = Report.Run.of(started.format(format), started.plusNanos((long) (seconds * 1e9)).format(format),
                seconds, target.toString(), config, reveal, true, List.of());
        Map<String, Object> aggregate = new java.util.LinkedHashMap<>(Aggregates.of(documents));
        aggregate.put("documents_skipped", skipped.size());
        return new Report(run, documents, skipped, null, aggregate, List.of(Report.Limitation.java()), List.of(),
                Map.of(), new Report.Masking(masking.revealTailChars(), masking.maskChar(), masking.neverReveal()),
                null, List.of(), null, null, null, null, null, null, null, null, null, null,
                Report.Thresholds.shipped());
    }

    /** A file the audit did not read, and why. */
    public record Skip(String path, String reason, String detail) {}

    /** Write a report where {@code complydoc ui} finds it, or anywhere else. */
    public static Path write(Report report, Path path) {
        return Json.write(report, path);
    }
}
