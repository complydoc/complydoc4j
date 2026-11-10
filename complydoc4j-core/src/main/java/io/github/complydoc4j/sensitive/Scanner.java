package io.github.complydoc4j.sensitive;

import io.github.complydoc4j.config.CategoryConfig;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.text.Py;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Find personal and financial identifiers, and report them masked.
 *
 * <p>The Python package's scanner, with one detector: patterns. A category a statistical
 * model reads (names, organisations) cannot run here, and is reported as not looked for, as
 * Python reports it when its name model is not installed. Its patterns, such as the name
 * after {@code Name:}, still cover the text wherever it is masked, and are counted nowhere.
 */
public final class Scanner {

    /** Why a category a model reads was not looked for. */
    public static final String NO_NAME_MODEL =
            "names need a name model, which the Java version does not have; run complydoc in Python to look for them";

    /**
     * Categories where one run of characters can only be one identifier: overlapping matches
     * among them are resolved longest first. Categories outside the group are independent.
     */
    private static final Set<String> EXCLUSIVE_GROUP = Set.of(
            "ni_number", "sort_code", "bank_account_number", "iban", "card_number", "phone_number",
            "date_of_birth", "vat_number", "utr", "international_phone", "us_phone", "us_ssn",
            "us_ein", "us_routing_number", "nl_bsn", "pt_nif", "de_steuer_id", "be_national_number",
            "pl_pesel", "se_personnummer", "dk_cpr", "no_fnr", "ch_ahv", "br_cpf", "br_cnpj",
            "in_aadhaar", "ca_sin", "au_tfn");

    public record UnscannedCategory(String category, String label, String reason) {}

    /** What a scan of a piece of text found, and what it could not look for. */
    public record TextScan(List<SensitiveMatch> matches, Map<String, String> unscanned) {}

    private record Candidate(String categoryId, CategoryConfig category, RegexDetector.Finding finding,
            List<String> validatorsPassed, boolean byPattern, boolean silent) {

        boolean quiet() {
            return silent || category.silent();
        }
    }

    private Scanner() {}

    /**
     * Scan a piece of text that is not a page, such as a chunk or a metadata value. With
     * {@code masking}, what the silent categories found is returned too: the matches are
     * then for covering the text, not for reporting.
     */
    public static TextScan scanText(String text, Config.Sensitive config, boolean reveal, boolean masking) {
        Map<String, String> unavailable = new LinkedHashMap<>();
        if (Py.blank(text)) {
            return new TextScan(List.of(), unavailable);
        }
        List<SensitiveMatch> silent = new ArrayList<>();
        List<SensitiveMatch> matches = scanPage(1, text, config, reveal, unavailable, silent);
        if (masking) {
            matches = new ArrayList<>(matches);
            matches.addAll(silent);
            matches.sort(Comparator.comparingInt(SensitiveMatch::page)
                    .thenComparingInt(SensitiveMatch::line)
                    .thenComparingInt(SensitiveMatch::column));
        }
        return new TextScan(matches, reported(unavailable, config));
    }

    public static TextScan scanText(String text, Config.Sensitive config) {
        return scanText(text, config, false, false);
    }

    /** {@code text} with every identifier masked, silent categories' included. */
    public static Masking.Masked maskText(String text, Config config) {
        return Masking.maskMatches(text, scanText(text, config.sensitive(), false, true).matches());
    }

    /** What a scan of a document's pages found. */
    public record PageScan(List<SensitiveMatch> matches, List<SensitiveMatch> silent,
            List<UnscannedCategory> unscannedCategories, List<Integer> unreadablePages, int pagesScanned) {}

    /** Scan each page, numbered from 1, as a document is scanned. */
    public static PageScan scanPages(List<String> pages, Config.Sensitive config, boolean reveal) {
        Map<String, String> unavailable = new LinkedHashMap<>();
        List<SensitiveMatch> matches = new ArrayList<>();
        List<SensitiveMatch> silent = new ArrayList<>();
        List<Integer> unreadable = new ArrayList<>();
        int scanned = 0;
        for (int i = 0; i < pages.size(); i++) {
            String text = pages.get(i);
            if (Py.blank(text)) {
                unreadable.add(i + 1);
                continue;
            }
            scanned++;
            matches.addAll(scanPage(i + 1, text, config, reveal, unavailable, silent));
        }
        List<UnscannedCategory> unscanned = new ArrayList<>();
        reported(unavailable, config).forEach((id, reason) -> {
            CategoryConfig category = config.categories().get(id);
            unscanned.add(new UnscannedCategory(id, category != null ? category.label() : id, reason));
        });
        return new PageScan(matches, silent, unscanned, unreadable, scanned);
    }

    private static Map<String, String> reported(Map<String, String> unavailable, Config.Sensitive config) {
        Map<String, String> out = new LinkedHashMap<>();
        unavailable.forEach((id, reason) -> {
            CategoryConfig category = config.categories().get(id);
            if (category == null || !category.silent()) {
                out.put(id, reason);
            }
        });
        return out;
    }

    private static List<SensitiveMatch> scanPage(int page, String text, Config.Sensitive config, boolean reveal,
            Map<String, String> unavailable, List<SensitiveMatch> silent) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, CategoryConfig> entry : config.enabledCategories().entrySet()) {
            String id = entry.getKey();
            CategoryConfig category = entry.getValue();
            if (unavailable.containsKey(id)) {
                continue;
            }
            boolean ran = !category.modelBacked() && "regex".equals(category.detector());
            List<RegexDetector.Finding> findings = ran ? RegexDetector.find(text, category) : null;

            // A category a model reads may carry patterns too: what a rule finds without the
            // model is found whether or not the model is there. Here it never is, so these
            // only cover the text.
            if (!"regex".equals(category.detector()) && !category.patterns().isEmpty()) {
                List<RegexDetector.Finding> byModel = findings == null ? List.of() : findings;
                for (RegexDetector.Finding rule : RegexDetector.find(text, category)) {
                    boolean overlaps = byModel.stream().anyMatch(f -> rule.start() < f.end() && f.start() < rule.end());
                    if (overlaps) {
                        continue;
                    }
                    Validators.Outcome outcome = Validators.validate(text.substring(rule.start(), rule.end()),
                            category.validators());
                    if (outcome.passed()) {
                        candidates.add(new Candidate(id, category, rule, outcome.names(), true, findings == null));
                    }
                }
            }
            if (findings == null) {
                unavailable.put(id, category.modelBacked() ? NO_NAME_MODEL
                        : "no detector named '" + category.detector() + "' is registered");
                continue;
            }
            for (RegexDetector.Finding finding : findings) {
                if (finding.confidence() != null && finding.confidence() < category.minConfidence()) {
                    continue;
                }
                Validators.Outcome outcome = Validators.validate(text.substring(finding.start(), finding.end()),
                        category.validators());
                if (outcome.passed()) {
                    candidates.add(new Candidate(id, category, finding, outcome.names(), false, false));
                }
            }
        }

        List<Candidate> reported = resolveOverlaps(candidates.stream().filter(c -> !c.quiet()).toList());
        List<Candidate> quiet = resolveOverlaps(candidates.stream().filter(Candidate::quiet).toList()).stream()
                .filter(c -> reported.stream().noneMatch(r ->
                        c.finding().start() < r.finding().end() && r.finding().start() < c.finding().end()))
                .toList();
        int[] starts = lineStarts(text);
        if (silent != null) {
            silent.addAll(matches(quiet, page, text, starts, config, reveal));
        }
        return matches(reported, page, text, starts, config, reveal);
    }

    private static List<Candidate> resolveOverlaps(List<Candidate> candidates) {
        List<Candidate> exclusive = new ArrayList<>();
        List<Candidate> independent = new ArrayList<>();
        for (Candidate candidate : candidates) {
            (EXCLUSIVE_GROUP.contains(candidate.categoryId()) ? exclusive : independent).add(candidate);
        }
        // Longest first; at equal length a match beside its own label wins. A stable sort, as
        // Python's, so ties keep the order the categories are configured in.
        exclusive.sort(Comparator.<Candidate>comparingInt(c -> c.finding().end() - c.finding().start())
                .thenComparing(c -> c.finding().contextTerm() != null)
                .thenComparingDouble(c -> c.finding().confidence() == null ? 0.0 : c.finding().confidence())
                .reversed());
        List<Candidate> accepted = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        for (Candidate candidate : exclusive) {
            int start = candidate.finding().start();
            int end = candidate.finding().end();
            if (taken.stream().anyMatch(t -> start < t[1] && t[0] < end)) {
                continue;
            }
            taken.add(new int[] {start, end});
            accepted.add(candidate);
        }
        accepted.addAll(independent);
        return accepted;
    }

    private static List<SensitiveMatch> matches(List<Candidate> candidates, int page, String text, int[] starts,
            Config.Sensitive config, boolean reveal) {
        List<SensitiveMatch> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            RegexDetector.Finding finding = candidate.finding();
            String value = text.substring(finding.start(), finding.end());
            String[] rendered = Masking.render(value, candidate.categoryId(), config.masking(), reveal);
            int line = lineOf(starts, finding.start());
            int column = Py.len(text.substring(starts[line - 1], finding.start()));
            out.add(new SensitiveMatch(
                    candidate.categoryId(),
                    candidate.category().label(),
                    candidate.category().severity(),
                    page,
                    line,
                    column,
                    Py.len(value),
                    rendered[0],
                    rendered[1],
                    evidenceOf(candidate.category().modelBacked() && !candidate.byPattern(),
                            candidate.validatorsPassed(), finding.contextTerm(),
                            labelNear(text, finding, candidate.category())),
                    finding.confidence(),
                    candidate.validatorsPassed(),
                    finding.contextTerm(),
                    Fingerprints.identifier(candidate.categoryId(), value)));
        }
        out.sort(Comparator.comparingInt(SensitiveMatch::page)
                .thenComparingInt(SensitiveMatch::line)
                .thenComparingInt(SensitiveMatch::column));
        return out;
    }

    /** The tier a finding earned from the checks it passed. */
    static String evidenceOf(boolean modelBacked, List<String> validatorsPassed, String contextTerm,
            boolean labelNearby) {
        if (validatorsPassed.stream().anyMatch(name -> !Validators.FORMAT_ONLY.contains(name))) {
            return "confirmed";
        }
        if (contextTerm != null || labelNearby) {
            return "corroborated";
        }
        return modelBacked ? "model" : "pattern";
    }

    private static boolean labelNear(String text, RegexDetector.Finding finding, CategoryConfig category) {
        if (finding.contextTerm() != null && !finding.contextTerm().isEmpty()) {
            return true;
        }
        if (category.contextTerms().isEmpty()) {
            return false;
        }
        return RegexDetector.nearbyTerm(text, finding.start(), finding.end(), category.contextTerms(),
                category.contextWindowChars()) != null;
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The 1-indexed line holding a UTF-16 offset. */
    private static int lineOf(int[] starts, int offset) {
        int low = 0;
        int high = starts.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] <= offset) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
