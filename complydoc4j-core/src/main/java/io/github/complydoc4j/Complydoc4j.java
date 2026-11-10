package io.github.complydoc4j;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.config.ConfigLoader;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.report.Aggregates;
import io.github.complydoc4j.report.DocumentAudit;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.report.Json;
import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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

    /** Write a report where {@code complydoc ui} finds it, or anywhere else. */
    public static Path write(Report report, Path path) {
        return Json.write(report, path);
    }
}
