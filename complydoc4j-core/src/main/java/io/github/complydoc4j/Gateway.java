package io.github.complydoc4j;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.config.ConfigLoader;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.hidden.Smuggled;
import io.github.complydoc4j.read.DocumentReader;
import io.github.complydoc4j.sensitive.Masking;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import io.github.complydoc4j.text.Py;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The document gateway: what stands between your documents and any model, agent or tool.
 *
 * <pre>{@code
 * Gateway gateway = Gateway.create();
 * Gateway.Result safe = gateway.filter(toolOutput);   // any text: a prompt, a retrieved chunk
 * Gateway.Result file = gateway.read(Path.of("contract.pdf"));
 * send(safe.text());
 * }</pre>
 *
 * <p>Whatever passes it comes out with identifiers masked and hidden passages, and text
 * written as an instruction to a model, taken out. {@link #filter} gives the same answer as
 * {@code complydoc.Gateway().filter} in Python, checked by the conformance suite.
 *
 * <p>{@code Gateway.create().blocking()} throws {@link Blocked} instead of returning text that
 * held an instruction to a model, or a file nothing could be read from.
 *
 * <p>{@code Gateway.create().numbered()} gives each masked value a number, as
 * {@code [#12 IBAN ••••4521]}, so a model can ask for one by it. {@link #reveal} puts that
 * request to a person, through a function of yours that asks them, and gives the one value only
 * if they allow it (see {@link Unmask}):
 *
 * <pre>{@code
 * Gateway gateway = Gateway.create().numbered();
 * Gateway.Result safe = gateway.read(Path.of("contract.pdf"));
 * Optional<String> iban = gateway.reveal(safe, 12, "to fill in the payment", request -> askTheUser(request.question()));
 * }</pre>
 */
public final class Gateway {

    /** What stands in the text where a hidden passage was taken out. */
    public static final String REMOVED = "[hidden text removed]";

    /** Results whose masked values a gateway keeps for {@link #reveal}, the oldest let go first. */
    static final int KEPT_VALUES = 256;

    private final Config config;
    private final boolean block;
    private final boolean numbered;
    private final Unmask.RevealLog log;
    private final Map<String, Kept> kept = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
                    return size() > KEPT_VALUES;
                }
            });

    private record Kept(Unmask.Values values, String source) {}

    private Gateway(Config config, boolean block, boolean numbered, Path revealLog) {
        this.config = config;
        this.block = block;
        this.numbered = numbered;
        this.log = new Unmask.RevealLog(revealLog);
    }

    /** A gateway with the shipped configuration, the same files the Python package reads. */
    public static Gateway create() {
        return create(ConfigLoader.shipped());
    }

    /** A gateway with a configuration of your own. */
    public static Gateway create(Config config) {
        return new Gateway(config, false, false, Unmask.REVEAL_LOG);
    }

    /** The same gateway, throwing {@link Blocked} on an instruction to a model or an unreadable file. */
    public Gateway blocking() {
        return new Gateway(config, true, numbered, log.path());
    }

    /** The same gateway, each masked value carrying a number a model can ask for, {@code [#12 IBAN ••••]}. */
    public Gateway numbered() {
        return new Gateway(config, block, true, log.path());
    }

    /** The same gateway, logging requests to {@link #reveal} to {@code path}; null keeps no log. */
    public Gateway revealLog(Path path) {
        return new Gateway(config, block, numbered, path);
    }

    /** The log of every request to {@link #reveal}, without the values. */
    public Unmask.RevealLog log() {
        return log;
    }

    /** How far the original could be trusted; the text returned is cleaned whatever it says. */
    public enum Verdict {
        /** Nothing found. */
        READY,
        /** Identifiers that are not sensitive, or a part that could not be read. */
        CAREFUL,
        /** Hidden text, or a sensitive identifier, in the original. */
        STOP,
        /** Nothing could be read, so nothing was checked. */
        UNREAD;

        /** As the Python package writes it: {@code ready}, {@code careful}, {@code stop}, {@code unread}. */
        public String code() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Text that passed the gateway, and what the gateway did to it.
     *
     * @param identifiers identifiers found, by label, such as {@code {"IBAN": 2}}
     * @param sensitive the labels among them that are sensitive
     * @param hidden hidden passages found, taken out or not
     * @param invisible invisible characters taken out
     * @param unscanned categories that could not be scanned, such as names with no name model
     * @param pages for a file, the text of each page; {@code text} is them joined
     * @param spans on a {@link #numbered()} gateway, each masked value's number, kind and the
     *     text around it
     * @param key which reading this is, for {@link #reveal}
     */
    public record Result(String text, Verdict verdict, int masked, int removed, int invisible,
            Map<String, Integer> identifiers, List<String> sensitive, int hidden, boolean instruction,
            String unread, List<String> unscanned, List<String> pages, String source,
            List<Unmask.MaskedSpan> spans, String key) {

        public Result {
            identifiers = Map.copyOf(identifiers);
            sensitive = List.copyOf(sensitive);
            unscanned = List.copyOf(unscanned);
            pages = List.copyOf(pages);
            spans = List.copyOf(spans);
            key = key == null ? "" : key;
        }

        public Result(String text, Verdict verdict, int masked, int removed, int invisible,
                Map<String, Integer> identifiers, List<String> sensitive, int hidden, boolean instruction,
                String unread, List<String> unscanned, List<String> pages, String source) {
            this(text, verdict, masked, removed, invisible, identifiers, sensitive, hidden, instruction, unread,
                    unscanned, pages, source, List.of(), "");
        }

        Result with(String text, List<String> pages, List<Unmask.MaskedSpan> spans, String key) {
            return new Result(text, verdict, masked, removed, invisible, identifiers, sensitive, hidden,
                    instruction, unread, unscanned, pages, source, spans, key);
        }

        /** True when nothing at all was found: the text is the original. */
        public boolean clean() {
            return verdict == Verdict.READY;
        }

        /** What the gateway did, in one sentence for a person; the Python package's words. */
        public String summary() {
            if (verdict == Verdict.UNREAD) {
                return "Nothing was checked: " + unread + ".";
            }
            List<String> said = new ArrayList<>();
            if (masked > 0) {
                said.add(masked + " identifier" + (masked != 1 ? "s" : "") + " masked");
            }
            if (removed > 0) {
                said.add(removed + " hidden passage" + (removed != 1 ? "s" : "") + " taken out");
            }
            if (invisible > 0) {
                said.add(invisible + " invisible character" + (invisible != 1 ? "s" : "") + " taken out");
            }
            if (hidden > 0 && removed == 0 && invisible == 0) {
                said.add("hidden text found that could not be located in the text");
            }
            if (instruction) {
                said.add("one was written as an instruction to a model");
            }
            if (said.isEmpty()) {
                said.add("nothing found");
            }
            if (unread != null) {
                said.add("not checked: " + unread);
            }
            if (!unscanned.isEmpty()) {
                said.add("not scanned for " + String.join(", ", unscanned));
            }
            return String.join("; ", said) + ".";
        }
    }

    /** Thrown by a {@link #blocking()} gateway instead of returning the text. */
    public static final class Blocked extends RuntimeException {
        private final transient Result result;

        public Blocked(Result result) {
            super((result.source() != null ? result.source() + ": " : "") + result.summary());
            this.result = result;
        }

        public Result result() {
            return result;
        }
    }

    /** {@code text} with identifiers masked and hidden or instruction-like passages taken out. */
    public Result filter(String text) {
        Cleaned cleaned = cleaned(text);
        List<Unmask.Stretch> found = Unmask.stretches(null, cleaned.input(), cleaned.placed());
        Unmask.Values values = Unmask.Values.number(found, Map.of(0, cleaned.result().text()),
                config.sensitive().masking());
        Result result = cleaned.result();
        String shown = numbered ? values.marked(null, result.text()) : result.text();
        return passed(kept(result.with(shown, result.pages(), List.of(), ""), values, null));
    }

    /** A text's result, the text masking was given, and the stretches it covered. */
    private record Cleaned(Result result, String input, List<Masking.Placed> placed) {}

    private Cleaned cleaned(String text) {
        List<ContentFinding> hidden = HiddenCheck.findHidden(text, config, false);
        // Invisible characters out first, so `ig​nore previous instructions` is found.
        String clean = Smuggled.stripInvisible(text);
        List<int[]> spans = HiddenCheck.instructionSpans(clean, config);
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] span = spans.get(i);
            clean = Py.head(clean, span[0]) + REMOVED + Py.slice(clean, span[1], Py.len(clean));
        }
        Scanner.TextScan scan = Scanner.scanText(clean, config.sensitive());
        Scanner.TextScan covering = Scanner.scanText(clean, config.sensitive(), false, true);
        List<Masking.Placed> placed = new ArrayList<>();
        Masking.Masked masked = Masking.maskMatches(clean, covering.matches(), placed);
        Map<String, Integer> identifiers = new TreeMap<>();
        TreeSet<String> sensitive = new TreeSet<>();
        for (SensitiveMatch match : scan.matches()) {
            identifiers.merge(match.label(), 1, Integer::sum);
            if (match.severity().equals("high")) {
                sensitive.add(match.label());
            }
        }
        boolean instruction = hidden.stream().anyMatch(f -> !f.instruction().equals("none"));
        Verdict verdict = !hidden.isEmpty() || !sensitive.isEmpty() ? Verdict.STOP
                : !identifiers.isEmpty() ? Verdict.CAREFUL : Verdict.READY;
        return new Cleaned(new Result(masked.text(), verdict, masked.replaced(), spans.size(),
                Py.len(text) - Py.len(Smuggled.stripInvisible(text)), identifiers, List.copyOf(sensitive),
                hidden.size(), instruction, null, List.copyOf(new TreeSet<>(covering.unscanned().keySet())),
                List.of(), null), clean, placed);
    }

    /**
     * A document read by complydoc4j's own readers, each page filtered, and the passages the
     * file's formatting hides (white text, hidden styles, hidden sheets) taken out too.
     */
    public Result read(Path path) throws IOException {
        String source = path.toString();
        DocumentReader.Read read;
        try {
            read = DocumentReader.read(path);
        } catch (DocumentReader.Unsupported e) {
            return passed(unread("complydoc4j cannot read this type of file", source));
        }
        List<HiddenCheck.Page> pages = new ArrayList<>();
        for (int i = 0; i < read.pages().size(); i++) {
            pages.add(new HiddenCheck.Page(i + 1, read.pages().get(i)));
        }
        List<ContentFinding> byFile = new ArrayList<>();
        for (ContentFinding finding : HiddenCheck.check(path, pages, config, true, false).findings()) {
            if (!finding.hiddenReasons().isEmpty()) {
                byFile.add(finding);
            }
        }
        int masked = 0, removed = 0, invisible = 0, hidden = byFile.size();
        boolean instruction = byFile.stream().anyMatch(f -> !f.instruction().equals("none"));
        Map<String, Integer> identifiers = new TreeMap<>();
        TreeSet<String> sensitive = new TreeSet<>();
        TreeSet<String> unscanned = new TreeSet<>();
        List<String> texts = new ArrayList<>();
        List<Unmask.Stretch> found = new ArrayList<>();
        Map<Integer, String> byPage = new LinkedHashMap<>();
        boolean anyText = false;
        for (HiddenCheck.Page page : pages) {
            String text = page.text();
            for (ContentFinding finding : byFile) {
                if (finding.page() == null || finding.page() == page.number()) {
                    Removal taken = removePassage(text, finding.excerpt(), finding.characters());
                    text = taken.text();
                    removed += taken.count();
                }
            }
            anyText |= !Py.blank(text);
            Cleaned cleaned = cleaned(text);
            Result one = cleaned.result();
            found.addAll(Unmask.stretches(page.number(), cleaned.input(), cleaned.placed()));
            byPage.put(page.number(), one.text());
            masked += one.masked();
            removed += one.removed();
            invisible += one.invisible();
            hidden += one.hidden();
            instruction |= one.instruction();
            one.identifiers().forEach((label, n) -> identifiers.merge(label, n, Integer::sum));
            sensitive.addAll(one.sensitive());
            unscanned.addAll(one.unscanned());
            texts.add(one.text());
        }
        if (!anyText) {
            return passed(unread("no text could be read from it", source));
        }
        String unread = read.warnings().isEmpty() ? null : String.join("; ", read.warnings());
        Verdict verdict = hidden > 0 || !sensitive.isEmpty() ? Verdict.STOP
                : !identifiers.isEmpty() || unread != null ? Verdict.CAREFUL : Verdict.READY;
        Unmask.Values values = Unmask.Values.number(found, byPage, config.sensitive().masking());
        if (numbered) {
            for (int i = 0; i < texts.size(); i++) {
                texts.set(i, values.marked(i + 1, texts.get(i)));
            }
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            joined.append(i > 0 ? "\n\n" : "").append("--- page ").append(i + 1).append(" ---\n").append(texts.get(i));
        }
        return passed(kept(new Result(joined.toString(), verdict, masked, removed, invisible, identifiers,
                List.copyOf(sensitive), hidden, instruction, unread, List.copyOf(unscanned), texts, source),
                values, source));
    }

    /**
     * One masked value of {@code result}, if the person allows it; empty if they decline.
     *
     * <p>{@code approve} is given a {@link Unmask.RevealRequest}, whose {@code question()} says
     * what is asked in plain words, and returns whether the person allowed it. It must ask the
     * person: the model that wants the value cannot allow it. A category never revealed throws
     * {@link Unmask.UnmaskException} without asking. Every request is logged, without the value.
     */
    public Optional<String> reveal(Result result, int id, String reason, Predicate<Unmask.RevealRequest> approve) {
        Kept found = kept.get(result.key());
        if (found == null) {
            throw new Unmask.UnmaskException(
                    "That result was not made by this gateway, or is too old to reveal from.");
        }
        Unmask.MaskedSpan span = found.values().span(id);
        Unmask.RevealRequest request = new Unmask.RevealRequest(span, reason == null ? "" : reason, found.source());
        if (!span.revealable()) {
            log.record(request, "refused", "gateway");
            throw new Unmask.UnmaskException(span.label() + " values are never revealed.");
        }
        boolean allowed = approve.test(request);
        log.record(request, allowed ? "allowed" : "declined", "gateway");
        return allowed ? Optional.of(found.values().value(id)) : Optional.empty();
    }

    private Result kept(Result result, Unmask.Values values, String source) {
        if (values.isEmpty()) {
            return result;
        }
        String key = UUID.randomUUID().toString().replace("-", "");
        kept.put(key, new Kept(values, source));
        return result.with(result.text(), result.pages(), numbered ? values.spans() : List.of(), key);
    }

    /** The filtered text alone, where a function of text is wanted. */
    public String apply(String text) {
        return filter(text).text();
    }

    /** A source of text whose every answer passes the gateway first. */
    public Supplier<String> guard(Supplier<String> source) {
        return () -> apply(source.get());
    }

    private static Result unread(String why, String source) {
        return new Result("", Verdict.UNREAD, 0, 0, 0, Map.of(), List.of(), 0, false, why, List.of(), List.of(),
                source);
    }

    private Result passed(Result result) {
        if (block && (result.instruction() || result.verdict() == Verdict.UNREAD)) {
            throw new Blocked(result);
        }
        return result;
    }

    /** The text with a passage taken out, and how many times it was. */
    record Removal(String text, int count) {}

    /**
     * Take a hidden passage out of the text, however the reader broke its lines. An excerpt cut
     * short ends with "…": the rest of the passage, {@code length} characters in all with
     * whitespace collapsed, is taken out with it, to the end of its last word.
     */
    static Removal removePassage(String text, String excerpt, int length) {
        boolean cut = excerpt.endsWith("…");
        List<String> words = Py.split(cut ? excerpt.substring(0, excerpt.length() - 1) : excerpt);
        if (words.isEmpty()) {
            return new Removal(text, 0);
        }
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            pattern.append(i > 0 ? "\\s+" : "").append(Pattern.quote(words.get(i)));
        }
        if (cut && length > 0) {
            pattern.append("\\S*");
        }
        Matcher matcher = Pattern.compile(pattern.toString()).matcher(text);
        StringBuilder out = new StringBuilder();
        int position = 0, count = 0;
        while (matcher.find()) {
            if (matcher.start() < position) {
                continue;
            }
            out.append(text, position, matcher.start()).append(REMOVED);
            int end = matcher.end();
            if (cut && length > 0) {
                int seen = Py.collapse(matcher.group()).length();
                while (end < text.length() && seen < length) {
                    if (!(Character.isWhitespace(text.charAt(end)) && Character.isWhitespace(text.charAt(end - 1)))) {
                        seen++;
                    }
                    end++;
                }
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
            }
            position = end;
            count++;
        }
        out.append(text.substring(position));
        return new Removal(out.toString(), count);
    }
}
