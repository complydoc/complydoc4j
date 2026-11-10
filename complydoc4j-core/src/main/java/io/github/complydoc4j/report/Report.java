package io.github.complydoc4j.report;

import io.github.complydoc4j.chunks.ChunkInspector.ChunkReport;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.sensitive.Scanner.UnscannedCategory;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import java.util.List;
import java.util.Map;

/**
 * A complydoc report, in the shape of report schema 27: what {@code complydoc ui} opens and
 * {@code complydoc check} reads.
 *
 * <p>Sections the Java version does not produce yet (cost, readiness, routing, evals,
 * verification) are null, as the schema allows. {@code run.tool_version} starts with
 * {@code complydoc4j}, so a reader can tell which version wrote a report.
 */
public record Report(
        Run run,
        List<Document> documents,
        List<Object> skipped,
        Object cost,
        Map<String, Object> aggregate,
        List<Limitation> limitations,
        List<String> stalenessWarnings,
        Map<String, Double> signalWeights,
        Masking configMasking,
        Object overall,
        List<Object> quickWins,
        Loader loader,
        Object loaderComparison,
        Object routing,
        Object verification,
        Object ignores,
        Object concepts,
        Object categories,
        Object evals,
        List<ChunkReport> chunks,
        Trace trace,
        Thresholds thresholds) {

    public static final int SCHEMA_VERSION = 27;

    public record Run(
            String toolVersion,
            int schemaVersion,
            String startedAt,
            String finishedAt,
            double durationSeconds,
            String target,
            List<String> componentsRun,
            String configDir,
            String configDigest,
            String offlineGuard,
            boolean revealUsed,
            boolean pageImagesUsed,
            boolean extractedTextUsed,
            boolean ocrCompareUsed,
            boolean ocrRequested,
            boolean ocrAvailable,
            boolean nerAvailable,
            String pythonVersion,
            Object monthlyVolume,
            String extractor,
            List<String> compareExtractors,
            List<String> compareEngines,
            int jobs,
            Object timeoutSeconds,
            Object sampledFrom,
            Object sampleSize,
            boolean passwordUsed,
            String reportDetail,
            int documentsReadAfterWorkerFailure,
            Object context,
            List<String> contentSentTo,
            int classifierMissedWorkers,
            int classifierCalls,
            Object verifyModel,
            Object verifyScope,
            int classifierFailures) {

        /** A run's record as complydoc4j writes it: one job, no network guard, no OCR, no names. */
        public static Run of(String startedAt, String finishedAt, double seconds, String target,
                io.github.complydoc4j.config.Config config, boolean reveal, boolean extractedText,
                List<String> contentSentTo) {
            return new Run(io.github.complydoc4j.Complydoc4j.TOOL, SCHEMA_VERSION, startedAt, finishedAt,
                    Math.round(seconds * 1000) / 1000.0, target, List.of("sensitive"), config.sourceDir(),
                    config.digest(), "not_armed", reveal, false, extractedText, false, false, false, false,
                    "Java " + System.getProperty("java.version"), null, target, List.of(), List.of(), 1, null, null,
                    null, false, "summary", 0, null, contentSentTo, 0, 0, null, null, 0);
        }
    }

    public record Masking(int revealTailChars, String maskChar, List<String> neverReveal) {}

    public record Thresholds(Map<String, Integer> bands, double similarEnough) {
        /** The readiness bands the Python package ships, so the viewer draws its scales the same. */
        public static Thresholds shipped() {
            return new Thresholds(Map.of("needs work", 25, "not ready", 0, "ready", 75, "workable", 50), 0.95);
        }
    }

    public record Limitation(String area, String statement, List<String> affected, String severity) {

        /** What a complydoc4j report leaves out, said once in every report it writes. */
        public static Limitation java() {
            return new Limitation("Pipeline", "Written by " + io.github.complydoc4j.Complydoc4j.TOOL
                    + ": cost, readiness, routing and evals are not produced yet, and hidden text is checked in the"
                    + " text itself, not in how a PDF or Office file draws it.", List.of(), "info");
        }
    }

    public record Loader(String name, int documentsReturned, Double seconds, List<Object> networkAttempts,
            String error, List<String> metadataKeys, Map<String, Object> failures, int cachedFiles,
            boolean networkAllowed, List<String> tags, List<Object> skipped, Object formats) {}

    public record Document(
            String path,
            String relativePath,
            String sha256,
            String format,
            int pageCount,
            boolean pageCountKnown,
            List<String> loadWarnings,
            Object cost,
            Object readiness,
            Sensitive sensitive,
            Object routing,
            Timing timing,
            List<PageText> extractedText,
            List<Extraction> extractions,
            List<MetadataFinding> metadataFindings,
            List<ContentFinding> contentFindings,
            List<Object> conceptFindings,
            int conceptsUnjudged,
            Object verification,
            boolean visibilityChecked,
            String visibilityNote,
            List<Object> ignored,
            List<String> pathExposures) {}

    public record Sensitive(String path, List<SensitiveMatch> matches, List<SensitiveMatch> silent,
            List<UnscannedCategory> unscannedCategories, List<Integer> unreadablePages, int pagesScanned,
            boolean revealUsed, List<String> revealBlockedCategories, Map<String, String> modelsUsed) {}

    public record Timing(double readSeconds, double analyseSeconds, double scanSeconds, double totalSeconds,
            double secondsPerPage, String startedAt) {}

    public record PageText(int number, String source, int characters, String text, String ocrText, boolean truncated,
            Map<String, String> readings, String kept, Map<String, Object> costs, Map<String, Double> seconds,
            Map<String, Map<String, Integer>> tokens, Map<String, Object> imageTokens, Object visionEstimate,
            String maskedText, String maskedOcrText, Object maskedReadings) {}

    public record Extraction(String extractor, int characters, Double meanCoveragePct, double seconds,
            String granularity, boolean readsTables, Double similarity, boolean reordered) {}

    public record MetadataFinding(String key, String category, String label, String severity, String evidence,
            String masked, String revealed, Integer page) {}

    public record Trace(String name, String scan, double seconds, double overheadSeconds, List<Stage> stages,
            String kind, List<String> connectionsOutside, Map<String, String> libraries, String error,
            Map<String, String> unscanned) {}

    public record Stage(
            int index,
            String kind,
            String component,
            String module,
            String method,
            double seconds,
            List<String> tags,
            Map<String, Object> parameters,
            Integer documentsIn,
            Integer documentsOut,
            Integer charactersIn,
            Integer charactersOut,
            List<String> sources,
            String scanned,
            List<StageIdentifier> identifiers,
            Integer hidden,
            List<String> metadataKeys,
            List<String> metadataKeysAdded,
            List<String> pathKeys,
            List<String> connections,
            List<String> hosts,
            List<String> ids,
            Integer vectors,
            Integer dimensions,
            Integer chunks,
            Integer parent,
            double started,
            Integer tokensIn,
            Integer tokensOut,
            Double usd,
            String usdBasis,
            List<StagePreview> previews,
            boolean finished,
            String error,
            String traceback,
            List<StageDocument> documents,
            List<StageWarning> warnings) {}

    public record StageIdentifier(String fingerprint, String label, String masked, String severity, int occurrences,
            String evidence, List<Integer> items) {}

    public record StageDocument(String source, int items, int characters, int empty, List<String> identifiers) {}

    public record StagePreview(String source, Integer page, int characters, int tokens, String text,
            Map<String, String> metadata) {}

    public record StageWarning(String code, String message, List<String> sources) {}
}
