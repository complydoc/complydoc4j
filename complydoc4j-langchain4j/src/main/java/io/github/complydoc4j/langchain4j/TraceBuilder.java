package io.github.complydoc4j.langchain4j;

import io.github.complydoc4j.Complydoc4j;
import io.github.complydoc4j.chunks.ChunkInspector;
import io.github.complydoc4j.chunks.ChunkInspector.ChunkReport;
import io.github.complydoc4j.config.CategoryConfig;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.report.Aggregates;
import io.github.complydoc4j.report.DocumentAudit;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.tokens.Tokens;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turning a finished observation's recordings into a report: the documents loaded in it,
 * audited; each splitter's chunks, inspected; and every stage's output scanned, so a value
 * can be followed from the loader to what an embedding model was sent.
 */
final class TraceBuilder {

    /** Settings named with a splitter's chunks, so two runs' splitters are told apart. */
    private static final List<String> SHAPING =
            List.of("chunk_size", "chunk_overlap", "max_segment_size", "max_overlap_size");
    private static final Map<String, Integer> EMBEDDING_LIMITS = Map.of(
            "text-embedding-3-small", 8191, "text-embedding-3-large", 8191, "text-embedding-ada-002", 8191);
    private static final int TINY_TOKENS = 20;
    private static final int TRACEBACK_LINES = 60;
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private final Observation observation;
    private final double seconds;
    private final String error;
    private final Config settings;
    private final Config patterns;
    private final Scans scans = new Scans();
    private final Measurer measurer;

    TraceBuilder(Observation observation, double seconds, String error) {
        this.observation = observation;
        this.seconds = seconds;
        this.error = error;
        this.settings = observation.options.settings();
        this.patterns = settings.patternsOnly();
        this.measurer = new Measurer(settings, observation.options.reveal(),
                observation.options.scan().equals("off") ? 0 : observation.options.previews());
    }

    Report build() {
        long started = System.nanoTime();
        List<Recording> recordings = List.copyOf(observation.recordings);
        List<Recording> top = recordings.stream().filter(r -> r.parent == null).toList();
        int last = top.isEmpty() ? -1 : top.get(top.size() - 1).index;

        List<Recording> loads = outermost(recordings, "load").stream().filter(r -> r.outputs != null).toList();
        List<Item> documents = new ArrayList<>();
        loads.forEach(r -> documents.addAll(r.outputs));
        if (documents.isEmpty()) {
            recordings.stream().filter(r -> !r.kind.equals("embed") && !r.kind.equals("store") && r.inputs != null
                    && !r.inputs.isEmpty()).findFirst().ifPresent(r -> documents.addAll(r.inputs));
        }
        boolean scanning = !observation.options.scan().equals("off");
        List<String> names = new ArrayList<>(new LinkedHashSet<>(loads.stream().map(r -> r.component).toList()));
        String loaderName = names.isEmpty() ? "documents" : String.join(", ", names);
        List<Report.Document> audited = documents.isEmpty() ? List.of()
                : DocumentAudit.audit(documents, loaderName, settings, observation.options.reveal(),
                        observation.options.extractedText());
        if (!scanning) {
            audited = audited.stream().map(TraceBuilder::unscanned).toList();
        }

        List<ChunkReport> chunkReports = new ArrayList<>();
        Map<Integer, Integer> chunkIndex = new HashMap<>();
        for (Recording recording : outermost(recordings, "split")) {
            if (recording.outputs != null) {
                chunkIndex.put(recording.index, chunkReports.size());
                Config config = configFor(recording, last);
                chunkReports.add(ChunkInspector.inspect(chunker(recording), recording.outputs,
                        recording.inputs == null ? List.of() : recording.inputs, config != null ? config : patterns));
            }
        }

        Set<Integer> directories = new java.util.HashSet<>();
        recordings.stream().filter(r -> r.kind.equals("load") && r.parent != null).forEach(r -> directories.add(r.parent));
        boolean anyLoad = recordings.stream().anyMatch(r -> r.kind.equals("load"));
        Recording first = anyLoad ? null : recordings.stream()
                .filter(r -> r.inputs != null && !r.inputs.isEmpty()).findFirst().orElse(null);

        Map<String, String> origin = new HashMap<>();
        List<Report.Stage> stages = new ArrayList<>();
        for (Recording recording : recordings) {
            Config config = configFor(recording, last);
            boolean fromReport = loads.size() == 1 && recording == loads.get(0) && config == settings;
            Integer chunks = chunkIndex.get(recording.index);
            stages.add(stage(recording, config, chunks, fromReport ? audited : null,
                    chunks != null ? chunkReports.get(chunks) : null, !directories.contains(recording.index),
                    recording == first, origin));
        }

        Map<String, String> unscanned = unscanned(audited);
        List<String> outside = List.of();
        Report.Trace trace = new Report.Trace(observation.name, observation.options.scan(), round(seconds, 3), 0.0,
                stages, "pipeline", outside, libraries(), error, unscanned);
        List<Report.Limitation> limitations = new ArrayList<>(limitations(trace));
        limitations.add(Report.Limitation.java());

        Set<String> sentTo = new TreeSet<>();
        stages.forEach(s -> sentTo.addAll(s.hosts()));
        Report.Loader loader = documents.isEmpty() ? null : new Report.Loader(loaderName, documents.size(),
                loads.isEmpty() ? null : round(loads.stream().mapToDouble(r -> r.seconds).sum(), 3), List.of(), null,
                new ArrayList<>(new TreeSet<>(documents.stream().flatMap(d -> d.metadata().keySet().stream()).toList())),
                Map.of(), 0, false,
                new ArrayList<>(new LinkedHashSet<>(loads.stream().flatMap(r -> r.tags.stream()).toList())),
                List.of(), null);
        Config.Masking masking = settings.sensitive().masking();
        Report.Run run = Report.Run.of(observation.startedAt.format(SECONDS),
                observation.startedAt.plusNanos((long) (seconds * 1e9)).format(SECONDS), seconds, loaderName,
                settings, observation.options.reveal(), observation.options.extractedText(), new ArrayList<>(sentTo));
        if (!scanning) {
            run = withoutComponents(run);
        }
        double overhead = round((System.nanoTime() - started) / 1e9, 3);
        return new Report(run, audited, List.of(), null, Aggregates.of(audited), limitations, List.of(), Map.of(),
                new Report.Masking(masking.revealTailChars(), masking.maskChar(), masking.neverReveal()), null,
                List.of(), loader, null, null, null, null, null, null, null,
                chunkReports.isEmpty() ? null : chunkReports,
                new Report.Trace(trace.name(), trace.scan(), trace.seconds(), overhead, trace.stages(), trace.kind(),
                        trace.connectionsOutside(), trace.libraries(), trace.error(), trace.unscanned()),
                Report.Thresholds.shipped());
    }

    /** The name model reads what was loaded, sent or stored, and the last stage; patterns the rest. */
    private Config configFor(Recording recording, int last) {
        String scan = observation.options.scan();
        if (scan.equals("off")) {
            return null;
        }
        if (scan.equals("full") || List.of("load", "embed", "store").contains(recording.kind)) {
            return settings;
        }
        return recording.index == last ? settings : patterns;
    }

    private static List<Recording> outermost(List<Recording> recordings, String kind) {
        Map<Integer, Recording> byIndex = new HashMap<>();
        recordings.forEach(r -> byIndex.put(r.index, r));
        List<Recording> out = new ArrayList<>();
        for (Recording recording : recordings) {
            if (!recording.kind.equals(kind)) {
                continue;
            }
            boolean nested = false;
            Recording parent = recording.parent != null ? byIndex.get(recording.parent) : null;
            while (parent != null) {
                if (parent.kind.equals(kind)) {
                    nested = true;
                    break;
                }
                parent = parent.parent != null ? byIndex.get(parent.parent) : null;
            }
            if (!nested) {
                out.add(recording);
            }
        }
        return out;
    }

    private static String chunker(Recording recording) {
        List<String> parts = new ArrayList<>();
        parts.add(recording.component);
        for (String key : SHAPING) {
            if (recording.parameters.containsKey(key)) {
                parts.add(key + "=" + recording.parameters.get(key));
            }
        }
        return String.join(" ", parts);
    }

    private Report.Stage stage(Recording recording, Config config, Integer chunks, List<Report.Document> audited,
            ChunkReport chunkReport, boolean warn, boolean loadedBefore, Map<String, String> origin) {
        boolean sends = recording.kind.equals("embed") || recording.kind.equals("store");
        List<Item> given = recording.inputs == null ? List.of() : recording.inputs;
        List<Item> passed = sends ? given : (recording.outputs == null ? List.of() : recording.outputs);
        List<String> texts = passed.stream().map(Item::text).toList();
        Integer tokensIn = recording.inputs != null ? measurer.tokens(given.stream().map(Item::text).toList()) : null;
        Integer tokensOut = sends || recording.outputs == null ? null : measurer.tokens(texts);
        Object[] cost = measurer.cost(recording);

        List<Report.StageIdentifier> identifiers = new ArrayList<>();
        List<String> storedIds = new ArrayList<>();
        Integer hidden = null;
        if (config != null) {
            List<SensitiveMatch> matches = new ArrayList<>();
            if (audited != null && !audited.isEmpty()) {
                audited.forEach(d -> matches.addAll(d.sensitive().matches()));
            } else {
                texts.forEach(t -> matches.addAll(scans.matches(t, config)));
            }
            if (recording.kind.equals("store")) {
                // A store keeps each item's metadata beside its text, so a value in either stays.
                passed.forEach(item -> matches.addAll(scans.matches(values(item.metadata()), config)));
            }
            identifiers = byFingerprint(matches);
            if (recording.kind.equals("store")) {
                storedIds = withIds(identifiers, recording, config);
                identifiers = withItems(identifiers, recording, config);
            }
            int count = 0;
            for (String text : texts) {
                count += scans.hidden(text, config);
            }
            hidden = count;
            if (audited != null) {
                audited.forEach(d -> d.sensitive().unscannedCategories()
                        .forEach(c -> scans.unavailable.put(c.category(), c.reason())));
            }
        }

        TreeSet<String> keysOut = new TreeSet<>();
        passed.forEach(item -> keysOut.addAll(item.metadata().keySet()));
        Set<String> keysIn = new java.util.HashSet<>();
        given.forEach(item -> keysIn.addAll(item.metadata().keySet()));
        TreeSet<String> pathKeys = new TreeSet<>();
        passed.forEach(item -> item.metadata().forEach((key, value) -> {
            if (value instanceof String s && ChunkInspector.ABSOLUTE_PATH.matcher(s).lookingAt()) {
                pathKeys.add(key);
            }
        }));

        // Each item with the document it came from, as the stages before named it: an
        // embedding call is given bare texts, which are known by the chunks they were.
        List<String[]> items = new ArrayList<>();
        for (Item item : passed) {
            String source = item.source() != null ? item.source() : origin.get(item.text());
            if (source != null) {
                origin.putIfAbsent(item.text(), source);
            }
            items.add(new String[] {item.text(), source});
        }
        String scanned = scanned(config);
        List<Report.StageWarning> warnings = new ArrayList<>();
        if (loadedBefore) {
            List<String[]> loaded = new ArrayList<>();
            given.forEach(item -> loaded.add(new String[] {item.text(), item.source()}));
            warnings.addAll(loadWarnings(loaded));
        }
        if (warn) {
            warnings.addAll(warnings(recording, items, chunkReport));
        }
        List<String> connections = new ArrayList<>(new LinkedHashSet<>(recording.connections));
        return new Report.Stage(
                recording.index, recording.kind, recording.component, recording.module, recording.method,
                round(recording.seconds, 4), recording.tags, recording.parameters,
                recording.inputs != null ? given.size() : null,
                sends || recording.outputs == null ? null : recording.outputs.size(),
                recording.inputs != null ? given.stream().mapToInt(i -> Py.len(i.text())).sum() : null,
                sends || recording.outputs == null ? null : texts.stream().mapToInt(Py::len).sum(),
                sources(passed), scanned, identifiers, hidden, new ArrayList<>(keysOut),
                given.isEmpty() ? List.of() : keysOut.stream().filter(k -> !keysIn.contains(k)).toList(),
                new ArrayList<>(pathKeys), connections, Settings.hosts(connections), storedIds, recording.vectors,
                recording.dimensions, chunks, recording.parent, recording.started, tokensIn, tokensOut,
                (Double) cost[0], (String) cost[1], config != null ? measurer.preview(passed) : List.of(),
                recording.finished, recording.error,
                recording.traceback != null ? traceback(recording.traceback) : null,
                stageDocuments(items, scanned.equals("off") ? null : config, audited), warnings);
    }

    private String scanned(Config config) {
        if (config == null) {
            return "off";
        }
        boolean ran = config.sensitive().enabledCategories().entrySet().stream()
                .anyMatch(e -> e.getValue().modelBacked() && !scans.unavailable.containsKey(e.getKey()));
        return ran ? "full" : "patterns";
    }

    private List<Report.StageDocument> stageDocuments(List<String[]> items, Config config,
            List<Report.Document> audited) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String[] item : items) {
            if (item[1] != null && !item[1].isEmpty()) {
                grouped.computeIfAbsent(item[1], k -> new ArrayList<>()).add(item[0]);
            }
        }
        Map<String, List<String>> byAudit = null;
        if (audited != null && config != null) {
            byAudit = new LinkedHashMap<>();
            for (Report.Document document : audited) {
                byAudit.put(document.relativePath(), keys(document.sensitive().matches()));
            }
        }
        List<Report.StageDocument> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            List<String> found;
            if (config == null) {
                found = List.of();
            } else if (byAudit != null) {
                found = lookup(byAudit, entry.getKey());
            } else {
                List<SensitiveMatch> matches = new ArrayList<>();
                entry.getValue().forEach(t -> matches.addAll(scans.matches(t, config)));
                found = keys(matches);
            }
            List<String> texts = entry.getValue();
            out.add(new Report.StageDocument(entry.getKey(), texts.size(), texts.stream().mapToInt(Py::len).sum(),
                    (int) texts.stream().filter(Py::blank).count(), found));
        }
        return out;
    }

    private static List<String> keys(List<SensitiveMatch> matches) {
        return new ArrayList<>(new LinkedHashSet<>(matches.stream().map(SensitiveMatch::key).toList()));
    }

    private static List<String> lookup(Map<String, List<String>> audited, String source) {
        if (audited.containsKey(source)) {
            return audited.get(source);
        }
        String name = Path.of(source).getFileName().toString();
        for (Map.Entry<String, List<String>> entry : audited.entrySet()) {
            String path = entry.getKey();
            if (path.endsWith(source) || source.endsWith(path) || Path.of(path).getFileName().toString().equals(name)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private static List<Report.StageIdentifier> byFingerprint(List<SensitiveMatch> matches) {
        Map<String, Report.StageIdentifier> found = new LinkedHashMap<>();
        for (SensitiveMatch match : matches) {
            found.merge(match.key(), new Report.StageIdentifier(match.key(), match.label(), match.masked(),
                    match.severity(), 1, match.evidence(), List.of()),
                    (seen, again) -> new Report.StageIdentifier(seen.fingerprint(), seen.label(), seen.masked(),
                            seen.severity(), seen.occurrences() + 1, seen.evidence(), seen.items()));
        }
        Map<String, Integer> severity = Map.of("high", 0, "medium", 1, "low", 2);
        List<Report.StageIdentifier> out = new ArrayList<>(found.values());
        out.sort(Comparator.<Report.StageIdentifier>comparingInt(i -> severity.getOrDefault(i.severity(), 3))
                .thenComparing(Report.StageIdentifier::label).thenComparing(Report.StageIdentifier::masked));
        return out;
    }

    /**
     * The IDs the store kept its items under, in order. An ID that is a path, or in which a
     * pattern finds an identifier, is kept empty: the report never carries the value.
     */
    private List<String> withIds(List<Report.StageIdentifier> identifiers, Recording recording, Config config) {
        List<String> ids = recording.ids;
        if (ids == null || ids.isEmpty() || ids.size() != recording.inputs.size()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String id : ids) {
            boolean holds = id == null || ChunkInspector.ABSOLUTE_PATH.matcher(id).lookingAt()
                    || scans.matches(id, config).stream().anyMatch(m -> !m.evidence().equals("model"));
            kept.add(holds ? "" : id);
        }
        return kept;
    }

    /** Each identifier with the positions of the stored items holding it. */
    private List<Report.StageIdentifier> withItems(List<Report.StageIdentifier> identifiers, Recording recording,
            Config config) {
        List<String> ids = recording.ids;
        if (ids == null || ids.isEmpty() || ids.size() != recording.inputs.size()) {
            return identifiers;
        }
        Map<String, Set<Integer>> holding = new HashMap<>();
        for (int position = 0; position < recording.inputs.size(); position++) {
            Item item = recording.inputs.get(position);
            for (String part : List.of(item.text(), values(item.metadata()))) {
                for (SensitiveMatch match : scans.matches(part, config)) {
                    holding.computeIfAbsent(match.key(), k -> new TreeSet<>()).add(position);
                }
            }
        }
        return identifiers.stream().map(i -> new Report.StageIdentifier(i.fingerprint(), i.label(), i.masked(),
                i.severity(), i.occurrences(), i.evidence(),
                new ArrayList<>(holding.getOrDefault(i.fingerprint(), Set.of())))).toList();
    }

    /** A metadata's plain values as one text, to be scanned as a store keeps them. */
    private static String values(Map<String, Object> metadata) {
        List<String> parts = new ArrayList<>();
        metadata.values().forEach(v -> {
            if (v instanceof String || v instanceof Integer || v instanceof Long) {
                parts.add(String.valueOf(v));
            } else if (v instanceof Double || v instanceof Float) {
                parts.add(io.github.complydoc4j.report.Json.pyFloat(((Number) v).doubleValue()));
            }
        });
        return String.join("\n", parts);
    }

    private static List<String> sources(List<Item> items) {
        return new ArrayList<>(new LinkedHashSet<>(items.stream().map(Item::source).filter(Objects::nonNull).toList()));
    }

    private String traceback(String text) {
        String home = System.getProperty("user.home");
        List<String> lines = Py.splitlines(text);
        List<String> kept = new ArrayList<>();
        for (String line : lines.subList(Math.max(0, lines.size() - TRACEBACK_LINES), lines.size())) {
            kept.add(home == null ? line : line.replace(home, "~"));
        }
        return measurer.mask(String.join("\n", kept));
    }

    private Map<String, String> unscanned(List<Report.Document> audited) {
        Map<String, String> found = new LinkedHashMap<>();
        audited.forEach(d -> d.sensitive().unscannedCategories().forEach(c -> found.put(c.category(), c.reason())));
        found.putAll(measurer.unscanned);
        found.putAll(scans.unavailable);
        Map<String, String> byLabel = new LinkedHashMap<>();
        found.forEach((id, reason) -> {
            CategoryConfig category = settings.sensitive().categories().get(id);
            byLabel.put(category != null ? category.label() : id, reason);
        });
        return byLabel;
    }

    private static Map<String, String> libraries() {
        Map<String, String> found = new TreeMap<>();
        Package core = dev.langchain4j.data.document.Document.class.getPackage();
        String version = core == null ? null : core.getImplementationVersion();
        found.put("langchain4j-core", version != null ? version : "unknown");
        found.put("complydoc4j", Complydoc4j.VERSION);
        return found;
    }

    private static Report.Run withoutComponents(Report.Run r) {
        return new Report.Run(r.toolVersion(), r.schemaVersion(), r.startedAt(), r.finishedAt(), r.durationSeconds(),
                r.target(), List.of(), r.configDir(), r.configDigest(), r.offlineGuard(), r.revealUsed(),
                r.pageImagesUsed(), r.extractedTextUsed(), r.ocrCompareUsed(), r.ocrRequested(), r.ocrAvailable(),
                r.nerAvailable(), r.pythonVersion(), r.monthlyVolume(), r.extractor(), r.compareExtractors(),
                r.compareEngines(), r.jobs(), r.timeoutSeconds(), r.sampledFrom(), r.sampleSize(), r.passwordUsed(),
                r.reportDetail(), r.documentsReadAfterWorkerFailure(), r.context(), r.contentSentTo(),
                r.classifierMissedWorkers(), r.classifierCalls(), r.verifyModel(), r.verifyScope(),
                r.classifierFailures());
    }

    private static Report.Document unscanned(Report.Document d) {
        return new Report.Document(d.path(), d.relativePath(), d.sha256(), d.format(), d.pageCount(),
                d.pageCountKnown(), d.loadWarnings(), d.cost(), d.readiness(), null, d.routing(), d.timing(),
                d.extractedText(), d.extractions(), List.of(), List.of(), d.conceptFindings(), d.conceptsUnjudged(),
                d.verification(), false, "scan was off", d.ignored(), d.pathExposures());
    }

    // --- warnings ---------------------------------------------------------------------------

    private List<Report.StageWarning> warnings(Recording recording, List<String[]> items, ChunkReport chunks) {
        switch (recording.kind) {
            case "load" -> {
                return loadWarnings(items);
            }
            case "split" -> {
                return chunks == null ? List.of() : splitWarnings(chunks);
            }
            case "embed", "store" -> {
                return sentWarnings(recording.kind, items, Measurer.model(recording));
            }
            default -> {
                return List.of();
            }
        }
    }

    private static List<Report.StageWarning> loadWarnings(List<String[]> items) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        items.forEach(i -> grouped.computeIfAbsent(i[1] == null ? "" : i[1], k -> new ArrayList<>()).add(i[0]));
        List<Report.StageWarning> warnings = new ArrayList<>();
        List<String> empty = grouped.entrySet().stream().filter(e -> e.getValue().stream().allMatch(Py::blank))
                .map(Map.Entry::getKey).toList();
        if (!empty.isEmpty()) {
            warnings.add(new Report.StageWarning("empty_document", count(empty.size(), "document") + " loaded no text",
                    empty.stream().filter(s -> !s.isEmpty()).toList()));
        }
        Map<String, Integer> partly = new LinkedHashMap<>();
        grouped.forEach((source, texts) -> {
            if (!empty.contains(source) && texts.size() > 1) {
                int blank = (int) texts.stream().filter(Py::blank).count();
                if (blank > 0) {
                    partly.put(source, blank);
                }
            }
        });
        if (!partly.isEmpty()) {
            int pages = partly.values().stream().mapToInt(Integer::intValue).sum();
            warnings.add(new Report.StageWarning("empty_pages", count(pages, "page") + " with no text, in "
                    + count(partly.size(), "document"), partly.keySet().stream().filter(s -> !s.isEmpty()).toList()));
        }
        return warnings;
    }

    private static List<Report.StageWarning> splitWarnings(ChunkReport report) {
        Map<String, String> wording = new LinkedHashMap<>();
        wording.put("tiny", "under " + TINY_TOKENS + " tokens");
        wording.put("oversized", "over the maximum set");
        wording.put("duplicate", "repeating an earlier chunk");
        List<Report.StageWarning> warnings = new ArrayList<>();
        wording.forEach((flag, words) -> {
            List<ChunkInspector.InspectedChunk> flagged = report.chunks().stream()
                    .filter(c -> c.flags().contains(flag)).toList();
            if (!flagged.isEmpty()) {
                List<String> sources = new ArrayList<>(new LinkedHashSet<>(flagged.stream()
                        .map(ChunkInspector.InspectedChunk::document).filter(Objects::nonNull).toList()));
                warnings.add(new Report.StageWarning(flag + "_chunks", count(flagged.size(), "chunk") + " " + words,
                        sources));
            }
        });
        return warnings;
    }

    private List<Report.StageWarning> sentWarnings(String kind, List<String[]> items, String model) {
        List<Report.StageWarning> warnings = new ArrayList<>();
        List<String> blank = items.stream().filter(i -> Py.blank(i[0])).map(i -> i[1]).toList();
        if (!blank.isEmpty()) {
            warnings.add(new Report.StageWarning("empty_texts", count(blank.size(), "empty text") + " "
                    + (kind.equals("embed") ? "embedded" : "stored"),
                    new ArrayList<>(new LinkedHashSet<>(blank.stream().filter(Objects::nonNull).toList()))));
        }
        Integer limit = model == null ? null : EMBEDDING_LIMITS.get(model);
        if (kind.equals("embed") && limit != null) {
            List<String> over = items.stream().filter(i -> measurer.tokens(List.of(i[0])) > limit).map(i -> i[1])
                    .toList();
            if (!over.isEmpty()) {
                warnings.add(new Report.StageWarning("over_token_limit", count(over.size(), "text") + " over " + model
                        + "'s limit of " + String.format(Locale.US, "%,d", limit) + " tokens",
                        new ArrayList<>(new LinkedHashSet<>(over.stream().filter(Objects::nonNull).toList()))));
            }
        }
        return warnings;
    }

    // --- limitations and summary -------------------------------------------------------------

    private static List<Report.Limitation> limitations(Report.Trace trace) {
        List<Report.Limitation> out = new ArrayList<>();
        Set<Integer> sendingInside = new java.util.HashSet<>();
        trace.stages().stream().filter(s -> !s.hosts().isEmpty() && s.parent() != null)
                .forEach(s -> sendingInside.add(s.parent()));
        for (Report.Stage stage : trace.stages()) {
            String what = "Stage " + (stage.index() + 1) + ", " + stage.component();
            if (!stage.hosts().isEmpty() && !sendingInside.contains(stage.index())) {
                String subject = stage.kind().equals("embed")
                        ? count(stage.documentsIn() == null ? 0 : stage.documentsIn(), "text") : "what it was given";
                String held = stage.identifiers().isEmpty() ? ""
                        : ", holding " + count(stage.identifiers().size(), "identifier") + ",";
                out.add(new Report.Limitation("Pipeline", what + ", sent " + subject + held + " to "
                        + String.join(", ", stage.hosts()) + " (the endpoint in its settings).", stage.connections(),
                        "important"));
            }
            if (stage.error() != null) {
                out.add(new Report.Limitation("Pipeline", what + ", raised " + stage.error() + ".", List.of(),
                        "important"));
            }
        }
        if (!trace.unscanned().isEmpty()) {
            String labels = String.join(" and ", trace.unscanned().keySet());
            String reason = trace.unscanned().values().iterator().next();
            out.add(new Report.Limitation("Pipeline", labels + " were not looked for, so they are neither counted nor"
                    + " masked in the trace's previews: " + reason, List.of(), "important"));
        }
        if (trace.error() != null) {
            out.add(new Report.Limitation("Pipeline", "The pipeline raised " + trace.error()
                    + "; the trace ends where it stopped.", List.of(), "important"));
        }
        return out;
    }

    static List<String> summary(Observation observation, Report report, String error, Path path) {
        if (report == null || report.trace() == null) {
            return List.of(observation.name + ": not recorded (" + (error != null ? error : "the block is open") + ")");
        }
        Report.Trace trace = report.trace();
        List<List<Report.Stage>> steps = new ArrayList<>();
        for (Report.Stage stage : trace.stages()) {
            if (stage.parent() != null) {
                continue;
            }
            List<Report.Stage> previous = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            Report.Stage last = previous == null ? null : previous.get(previous.size() - 1);
            if (last != null && last.kind().equals(stage.kind()) && last.component().equals(stage.component())
                    && last.method().equals(stage.method())) {
                previous.add(stage);
            } else {
                steps.add(new ArrayList<>(List.of(stage)));
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US, "%s: %s in %.2f s; observing took %.2f s", trace.name(),
                count(steps.size(), "step"), trace.seconds(), trace.overheadSeconds()));
        for (int number = 0; number < steps.size(); number++) {
            List<Report.Stage> group = steps.get(number);
            Report.Stage first = group.get(0);
            List<String> parts = new ArrayList<>();
            int in = group.stream().mapToInt(s -> s.documentsIn() == null ? 0 : s.documentsIn()).sum();
            if (first.kind().equals("embed")) {
                parts.add(count(in, "text") + " embedded");
            } else if (first.kind().equals("store")) {
                parts.add(count(in, "document") + " stored");
            } else if (first.documentsOut() != null) {
                parts.add(count(group.stream().mapToInt(s -> s.documentsOut() == null ? 0 : s.documentsOut()).sum(),
                        first.kind().equals("split") ? "chunk" : "document"));
            }
            if (!first.scanned().equals("off")) {
                Set<String> found = new java.util.HashSet<>();
                group.forEach(s -> s.identifiers().forEach(i -> found.add(i.fingerprint())));
                parts.add(count(found.size(), "identifier"));
            }
            Set<String> hosts = new LinkedHashSet<>();
            group.forEach(s -> hosts.addAll(s.hosts()));
            if (!hosts.isEmpty()) {
                parts.add("sent to " + String.join(", ", hosts));
            }
            group.stream().filter(s -> s.error() != null).forEach(s -> parts.add("raised " + s.error()));
            group.forEach(s -> s.warnings().forEach(w -> parts.add(w.message())));
            String calls = group.size() > 1 ? " (" + group.size() + " calls)" : "";
            lines.add(String.format(Locale.US, "  %d. %-9s %-34s %s", number + 1, first.kind(),
                    first.component() + calls, String.join(", ", parts)));
        }
        if (!trace.unscanned().isEmpty()) {
            lines.add("Not looked for, so neither counted nor masked: " + String.join(", ", trace.unscanned().keySet())
                    + ". " + trace.unscanned().values().iterator().next());
        }
        if (path != null) {
            lines.add("Written to " + path);
        }
        return lines;
    }

    static String count(int quantity, String singular) {
        return String.format(Locale.US, "%,d %s", quantity, quantity == 1 ? singular : singular + "s");
    }

    private static double round(double value, int places) {
        return Observation.round(value, places);
    }

    /** Each distinct text scanned once per configuration, however many stages pass it on. */
    private static final class Scans {
        private final Map<Config, Map<String, List<SensitiveMatch>>> matches = new java.util.IdentityHashMap<>();
        private final Map<Config, Map<String, Integer>> hidden = new java.util.IdentityHashMap<>();
        /** Categories that could not be looked for, by id, with why. */
        final Map<String, String> unavailable = new LinkedHashMap<>();

        List<SensitiveMatch> matches(String text, Config config) {
            return matches.computeIfAbsent(config, c -> new HashMap<>()).computeIfAbsent(text, t -> {
                Scanner.TextScan scan = Scanner.scanText(t, config.sensitive(), false, false);
                unavailable.putAll(scan.unscanned());
                return scan.matches();
            });
        }

        /** Passages of {@code text} hidden or instruction-like at medium or high. */
        int hidden(String text, Config config) {
            return hidden.computeIfAbsent(config, c -> new HashMap<>()).computeIfAbsent(text, t -> {
                int count = 0;
                for (ContentFinding finding : HiddenCheck.findHidden(t, config, false)) {
                    if (finding.severity().equals("medium") || finding.severity().equals("high")) {
                        count++;
                    }
                }
                return count;
            });
        }
    }

    static int tokens(String text) {
        return Tokens.count(text);
    }
}
