package io.github.complydoc4j.langchain4j;

import static io.github.complydoc4j.langchain4j.StageWarnings.count;

import io.github.complydoc4j.Complydoc4j;
import io.github.complydoc4j.chunks.ChunkInspector;
import io.github.complydoc4j.chunks.ChunkInspector.ChunkReport;
import io.github.complydoc4j.config.CategoryConfig;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.report.Aggregates;
import io.github.complydoc4j.report.DocumentAudit;
import io.github.complydoc4j.report.Item;
import io.github.complydoc4j.report.Report;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import io.github.complydoc4j.text.Py;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The report of a closed observation: the documents loaded in it, audited; each splitter's
 * chunks, inspected; and every stage's output scanned, so a value can be followed from the
 * loader to what an embedding model was sent. The port of complydoc's
 * {@code observe/trace.py}.
 */
final class TraceReport {

    /** Settings named with a splitter's chunks, so two runs' splitters are told apart. */
    private static final List<String> SHAPING =
            List.of("chunk_size", "chunk_overlap", "max_segment_size", "max_overlap_size");
    private static final int TRACEBACK_LINES = 60;
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private final Observation observation;
    private final List<Recording> recordings;
    private final Config settings;
    private final Config patterns;
    private final StageScans scans = new StageScans();
    private final Measurer measurer;
    /** The document each text came from, as the stages before named it. */
    private final Map<String, String> origin = new HashMap<>();
    private final int last;

    private TraceReport(Observation observation) {
        this.observation = observation;
        this.recordings = List.copyOf(observation.recordings);
        this.settings = observation.options.settings();
        this.patterns = settings.patternsOnly();
        this.measurer = new Measurer(settings, observation.options.reveal(),
                scanning() ? observation.options.previews() : 0);
        List<Recording> top = recordings.stream().filter(r -> r.parent == null).toList();
        this.last = top.isEmpty() ? -1 : top.get(top.size() - 1).index;
    }

    static Report of(Observation observation, double seconds) {
        return new TraceReport(observation).report(seconds);
    }

    private boolean scanning() {
        return !observation.options.scan().equals("off");
    }

    private Report report(double seconds) {
        long began = System.nanoTime();
        List<Recording> loads = outermost("load").stream().filter(r -> r.outputs != null).toList();
        List<Item> documents = documents(loads);
        List<String> names = new ArrayList<>(new LinkedHashSet<>(loads.stream().map(r -> r.component).toList()));
        String loader = names.isEmpty() ? "documents" : String.join(", ", names);
        List<Report.Document> audited = documents.isEmpty() ? List.of()
                : DocumentAudit.audit(documents, loader, settings, observation.options.reveal(),
                        observation.options.extractedText());
        if (!scanning()) {
            audited = audited.stream().map(TraceReport::unscanned).toList();
        }

        List<ChunkReport> chunks = new ArrayList<>();
        Map<Integer, Integer> chunkIndex = new HashMap<>();
        for (Recording split : outermost("split")) {
            if (split.outputs != null) {
                chunkIndex.put(split.index, chunks.size());
                Config config = configFor(split);
                chunks.add(ChunkInspector.inspect(chunker(split), split.outputs,
                        split.inputs == null ? List.of() : split.inputs, config != null ? config : patterns));
            }
        }

        // A directory loader passes on what the loader it runs for each file did; the
        // warnings are that loader's.
        Set<Integer> directories = new HashSet<>();
        recordings.stream().filter(r -> r.kind.equals("load") && r.parent != null).forEach(r -> directories.add(r.parent));
        // Where the documents were made by the caller's own code, the first stage given them
        // stands for the loader.
        Recording first = recordings.stream().anyMatch(r -> r.kind.equals("load")) ? null : recordings.stream()
                .filter(r -> r.inputs != null && !r.inputs.isEmpty()).findFirst().orElse(null);

        List<Report.Stage> stages = new ArrayList<>();
        for (Recording recording : recordings) {
            boolean fromAudit = loads.size() == 1 && recording == loads.get(0) && configFor(recording) == settings;
            Integer chunk = chunkIndex.get(recording.index);
            stages.add(stage(recording, chunk, chunk != null ? chunks.get(chunk) : null, fromAudit ? audited : null,
                    !directories.contains(recording.index), recording == first));
        }

        Map<String, String> unscanned = unscanned(audited);
        Set<String> sentTo = new TreeSet<>();
        stages.forEach(s -> sentTo.addAll(s.hosts()));
        List<Report.Limitation> limitations = new ArrayList<>(limitations(stages, unscanned));
        limitations.add(Report.Limitation.java());
        Report.Run run = Report.Run.of(observation.startedAt.format(SECONDS),
                observation.startedAt.plusNanos((long) (seconds * 1e9)).format(SECONDS), seconds, loader,
                settings, observation.options.reveal(), observation.options.extractedText(), new ArrayList<>(sentTo));
        if (!scanning()) {
            run = run.withComponents(List.of());
        }
        Config.Masking masking = settings.sensitive().masking();
        Report.Trace trace = new Report.Trace(observation.name, observation.options.scan(), round(seconds, 3),
                round((System.nanoTime() - began) / 1e9, 3), stages, "pipeline", List.of(), libraries(), null,
                unscanned);
        return new Report(run, audited, List.of(), null, Aggregates.of(audited), limitations, List.of(), Map.of(),
                new Report.Masking(masking.revealTailChars(), masking.maskChar(), masking.neverReveal()), null,
                List.of(), loaderRun(loader, documents, loads), null, null, null, null, null, null, null,
                chunks.isEmpty() ? null : chunks, trace, Report.Thresholds.shipped());
    }

    /** What was loaded inside the block; or, where nothing was, what the first stage was given. */
    private List<Item> documents(List<Recording> loads) {
        List<Item> documents = new ArrayList<>();
        loads.forEach(r -> documents.addAll(r.outputs));
        if (documents.isEmpty()) {
            recordings.stream().filter(r -> !r.kind.equals("embed") && !r.kind.equals("store") && r.inputs != null
                    && !r.inputs.isEmpty()).findFirst().ifPresent(r -> documents.addAll(r.inputs));
        }
        return documents;
    }

    private static Report.Loader loaderRun(String name, List<Item> documents, List<Recording> loads) {
        if (documents.isEmpty()) {
            return null;
        }
        return new Report.Loader(name, documents.size(),
                loads.isEmpty() ? null : round(loads.stream().mapToDouble(r -> r.seconds).sum(), 3), List.of(), null,
                new ArrayList<>(new TreeSet<>(documents.stream().flatMap(d -> d.metadata().keySet().stream()).toList())),
                Map.of(), 0, false,
                new ArrayList<>(new LinkedHashSet<>(loads.stream().flatMap(r -> r.tags.stream()).toList())), List.of(),
                null);
    }

    /** The name model reads what was loaded, sent or stored, and the last stage; patterns the rest. */
    private Config configFor(Recording recording) {
        String scan = observation.options.scan();
        if (scan.equals("off")) {
            return null;
        }
        if (scan.equals("full") || List.of("load", "embed", "store").contains(recording.kind)) {
            return settings;
        }
        return recording.index == last ? settings : patterns;
    }

    /** Stages of {@code kind} not inside another of the same kind. */
    private List<Recording> outermost(String kind) {
        Map<Integer, Recording> byIndex = new HashMap<>();
        recordings.forEach(r -> byIndex.put(r.index, r));
        List<Recording> out = new ArrayList<>();
        for (Recording recording : recordings) {
            if (!recording.kind.equals(kind)) {
                continue;
            }
            boolean nested = false;
            for (Recording p = parentOf(recording, byIndex); p != null && !nested; p = parentOf(p, byIndex)) {
                nested = p.kind.equals(kind);
            }
            if (!nested) {
                out.add(recording);
            }
        }
        return out;
    }

    private static Recording parentOf(Recording recording, Map<Integer, Recording> byIndex) {
        return recording.parent == null ? null : byIndex.get(recording.parent);
    }

    private static String chunker(Recording split) {
        List<String> parts = new ArrayList<>(List.of(split.component));
        SHAPING.stream().filter(split.parameters::containsKey)
                .forEach(key -> parts.add(key + "=" + split.parameters.get(key)));
        return String.join(" ", parts);
    }

    private Report.Stage stage(Recording recording, Integer chunk, ChunkReport chunks, List<Report.Document> audited,
            boolean warn, boolean loadedBefore) {
        Config config = configFor(recording);
        // An embedding call or a store passes nothing on: what it was given is what left.
        boolean sends = recording.kind.equals("embed") || recording.kind.equals("store");
        List<Item> given = recording.inputs == null ? List.of() : recording.inputs;
        List<Item> passed = sends ? given : (recording.outputs == null ? List.of() : recording.outputs);
        List<String> texts = passed.stream().map(Item::text).toList();

        List<Report.StageIdentifier> identifiers = List.of();
        List<String> ids = List.of();
        Integer hidden = null;
        if (config != null) {
            List<SensitiveMatch> found = new ArrayList<>();
            if (audited != null && !audited.isEmpty()) {
                audited.forEach(d -> found.addAll(d.sensitive().matches()));
                audited.forEach(d -> d.sensitive().unscannedCategories()
                        .forEach(c -> scans.unavailable.put(c.category(), c.reason())));
            } else {
                texts.forEach(t -> found.addAll(scans.matches(t, config)));
            }
            if (recording.kind.equals("store")) {
                found.addAll(scans.stored(passed, config));
            }
            identifiers = StageScans.byFingerprint(found);
            if (recording.kind.equals("store")) {
                ids = scans.keptIds(recording, config);
                identifiers = scans.withItems(identifiers, recording, config);
            }
            hidden = texts.stream().mapToInt(t -> scans.hidden(t, config)).sum();
        }

        List<StageWarnings.Sourced> items = sourced(passed);
        List<Report.StageWarning> warnings = new ArrayList<>();
        if (loadedBefore) {
            warnings.addAll(StageWarnings.loaded(given.stream()
                    .map(i -> new StageWarnings.Sourced(i.text(), i.source())).toList()));
        }
        if (warn) {
            warnings.addAll(StageWarnings.of(recording, items, chunks, t -> measurer.tokens(List.of(t))));
        }

        TreeSet<String> keysOut = new TreeSet<>();
        passed.forEach(item -> keysOut.addAll(item.metadata().keySet()));
        Set<String> keysIn = new HashSet<>();
        given.forEach(item -> keysIn.addAll(item.metadata().keySet()));
        List<String> connections = new ArrayList<>(new LinkedHashSet<>(recording.connections));
        Object[] cost = measurer.cost(recording);
        String scanned = scanned(config);
        return new Report.Stage(
                recording.index, recording.kind, recording.component, recording.module, recording.method,
                round(recording.seconds, 4), recording.tags, recording.parameters,
                recording.inputs != null ? given.size() : null,
                sends || recording.outputs == null ? null : recording.outputs.size(),
                recording.inputs != null ? given.stream().mapToInt(i -> Py.len(i.text())).sum() : null,
                sends || recording.outputs == null ? null : texts.stream().mapToInt(Py::len).sum(),
                new ArrayList<>(new LinkedHashSet<>(passed.stream().map(Item::source).filter(Objects::nonNull).toList())),
                scanned, identifiers, hidden, new ArrayList<>(keysOut),
                given.isEmpty() ? List.of() : keysOut.stream().filter(k -> !keysIn.contains(k)).toList(),
                pathKeys(passed), connections, Settings.hosts(connections), ids, recording.vectors,
                recording.dimensions, chunk, recording.parent, recording.started,
                recording.inputs != null ? measurer.tokens(given.stream().map(Item::text).toList()) : null,
                sends || recording.outputs == null ? null : measurer.tokens(texts),
                (Double) cost[0], (String) cost[1], config != null ? measurer.preview(passed) : List.of(),
                recording.finished, recording.error,
                recording.traceback != null ? traceback(recording.traceback) : null,
                stageDocuments(items, scanned.equals("off") ? null : config, audited), warnings);
    }

    /**
     * Each item with the document it came from: its own metadata, or the stage before that
     * passed the same text on. An embedding call is given bare texts, known by the chunks
     * they were.
     */
    private List<StageWarnings.Sourced> sourced(List<Item> passed) {
        List<StageWarnings.Sourced> items = new ArrayList<>();
        for (Item item : passed) {
            String source = item.source() != null ? item.source() : origin.get(item.text());
            if (source != null) {
                origin.putIfAbsent(item.text(), source);
            }
            items.add(new StageWarnings.Sourced(item.text(), source));
        }
        return items;
    }

    private static List<String> pathKeys(List<Item> passed) {
        TreeSet<String> keys = new TreeSet<>();
        passed.forEach(item -> item.metadata().forEach((key, value) -> {
            if (value instanceof String s && ChunkInspector.ABSOLUTE_PATH.matcher(s).lookingAt()) {
                keys.add(key);
            }
        }));
        return new ArrayList<>(keys);
    }

    /** How a stage was scanned: {@code full} only where a model-backed category actually ran. */
    private String scanned(Config config) {
        if (config == null) {
            return "off";
        }
        boolean ran = config.sensitive().enabledCategories().entrySet().stream()
                .anyMatch(e -> e.getValue().modelBacked() && !scans.unavailable.containsKey(e.getKey()));
        return ran ? "full" : "patterns";
    }

    private List<Report.StageDocument> stageDocuments(List<StageWarnings.Sourced> items, Config config,
            List<Report.Document> audited) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        items.stream().filter(i -> i.source() != null && !i.source().isEmpty())
                .forEach(i -> grouped.computeIfAbsent(i.source(), k -> new ArrayList<>()).add(i.text()));
        List<Report.StageDocument> out = new ArrayList<>();
        grouped.forEach((source, texts) -> {
            List<String> found;
            if (config == null) {
                found = List.of();
            } else if (audited != null) {
                // The loader's documents were scanned by the audit, and are not scanned again.
                found = auditedKeys(audited, source);
            } else {
                List<SensitiveMatch> matches = new ArrayList<>();
                texts.forEach(t -> matches.addAll(scans.matches(t, config)));
                found = keys(matches);
            }
            out.add(new Report.StageDocument(source, texts.size(), texts.stream().mapToInt(Py::len).sum(),
                    (int) texts.stream().filter(Py::blank).count(), found));
        });
        return out;
    }

    private static List<String> auditedKeys(List<Report.Document> audited, String source) {
        String name = Path.of(source).getFileName().toString();
        Report.Document exact = audited.stream().filter(d -> d.relativePath().equals(source)).findFirst().orElse(null);
        Report.Document match = exact != null ? exact : audited.stream().filter(d -> d.relativePath().endsWith(source)
                || source.endsWith(d.relativePath())
                || Path.of(d.relativePath()).getFileName().toString().equals(name)).findFirst().orElse(null);
        return match == null ? List.of() : keys(match.sensitive().matches());
    }

    private static List<String> keys(List<SensitiveMatch> matches) {
        return new ArrayList<>(new LinkedHashSet<>(matches.stream().map(SensitiveMatch::key).toList()));
    }

    /** The last lines of a stack trace, the home folder cut and identifiers in it masked. */
    private String traceback(String text) {
        String home = System.getProperty("user.home");
        List<String> lines = Py.splitlines(text);
        List<String> kept = lines.subList(Math.max(0, lines.size() - TRACEBACK_LINES), lines.size()).stream()
                .map(line -> home == null ? line : line.replace(home, "~")).toList();
        return measurer.mask(String.join("\n", kept));
    }

    /** The kinds of identifier the run could not look for, by label, with why. */
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

    private static List<Report.Limitation> limitations(List<Report.Stage> stages, Map<String, String> unscanned) {
        List<Report.Limitation> out = new ArrayList<>();
        // A stage's endpoints include those of the stages inside it; each is said once, by the innermost.
        Set<Integer> sendingInside = new HashSet<>();
        stages.stream().filter(s -> !s.hosts().isEmpty() && s.parent() != null).forEach(s -> sendingInside.add(s.parent()));
        for (Report.Stage stage : stages) {
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
        if (!unscanned.isEmpty()) {
            out.add(new Report.Limitation("Pipeline", String.join(" and ", unscanned.keySet())
                    + " were not looked for, so they are neither counted nor masked in the trace's previews: "
                    + unscanned.values().iterator().next(), List.of(), "important"));
        }
        return out;
    }

    private static Map<String, String> libraries() {
        Map<String, String> found = new TreeMap<>();
        Package core = dev.langchain4j.data.document.Document.class.getPackage();
        String version = core == null ? null : core.getImplementationVersion();
        found.put("langchain4j-core", version != null ? version : "unknown");
        found.put("complydoc4j", Complydoc4j.VERSION);
        return found;
    }

    /** A document as a run with the scan off reports it: nothing looked for. */
    private static Report.Document unscanned(Report.Document d) {
        return new Report.Document(d.path(), d.relativePath(), d.sha256(), d.format(), d.pageCount(),
                d.pageCountKnown(), d.loadWarnings(), d.cost(), d.readiness(), null, d.routing(), d.timing(),
                d.extractedText(), d.extractions(), List.of(), List.of(), d.conceptFindings(), d.conceptsUnjudged(),
                d.verification(), false, "scan was off", d.ignored(), d.pathExposures());
    }

    private static double round(double value, int places) {
        return Observation.round(value, places);
    }
}
