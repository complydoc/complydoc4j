package io.github.complydoc4j.hidden;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.sensitive.Masking;
import io.github.complydoc4j.sensitive.Fingerprints;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.text.Py;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visibility and instruction evidence, combined into findings.
 *
 * <table>
 *   <caption>Severity</caption>
 *   <tr><th>Visibility</th><th>Instruction</th><th>Severity</th></tr>
 *   <tr><td>hidden</td><td>pattern or confirmed</td><td>high</td></tr>
 *   <tr><td>hidden</td><td>none</td><td>medium</td></tr>
 *   <tr><td>visible or not measured</td><td>confirmed</td><td>medium</td></tr>
 *   <tr><td>visible or not measured</td><td>pattern</td><td>low</td></tr>
 * </table>
 *
 * <p>This version checks the text itself: invisible characters and instruction patterns.
 * What a file's own formatting hides (white text in a PDF, hidden runs in Office files) is
 * not checked yet, and a report says so for each file it could not check.
 */
public final class HiddenCheck {

    private static final int EXCERPT = 240;
    private static final Pattern BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s+|\\n", Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<String> IMAGES = List.of(".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp");

    /** A page's number and the text a model would be given. */
    public record Page(int number, String text) {}

    /**
     * @param visibilityChecked false when there was no file, or no way, to check against
     */
    public record Result(List<ContentFinding> findings, boolean visibilityChecked, String note) {}

    private HiddenCheck() {}

    public static String severityOf(String visibility, String instruction) {
        boolean hidden = visibility.equals("suspected") || visibility.equals("confirmed");
        if (hidden && (instruction.equals("pattern") || instruction.equals("confirmed"))) {
            return "high";
        }
        if (hidden && instruction.equals("model")) {
            return visibility.equals("confirmed") ? "high" : "medium";
        }
        if (hidden) {
            return "medium";
        }
        return instruction.equals("confirmed") ? "medium" : "low";
    }

    /** Invisible characters and instruction-like passages in a string, with no page. */
    public static List<ContentFinding> findHidden(String text, Config config, boolean reveal) {
        List<ContentFinding> found = new ArrayList<>();
        for (ContentFinding finding : check(null, List.of(new Page(1, text)), config, reveal, false).findings()) {
            found.add(finding.onPage(null));
        }
        return found;
    }

    /**
     * Hidden passages in the file at {@code path}, and instruction-like text in {@code pages}.
     * With {@code loaderText}, the text came from a loader, and each passage records whether
     * that text holds it.
     *
     * <p>Three passes, in the order a reader of the report meets them: what the file's own
     * formatting hides, what invisible characters smuggle in, and what the instruction
     * patterns match in the visible text.
     */
    public static Result check(Path path, List<Page> pages, Config config, boolean reveal, boolean loaderText) {
        HiddenRun.Found file = fileRuns(path, config.hidden().visibility());
        InstructionMatcher matcher = new InstructionMatcher(config.hidden());
        List<String> hiddenTexts = file.runs().stream().map(r -> compact(r.text())).toList();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            all.append(i > 0 ? "\n" : "").append(pages.get(i).text());
        }
        String given = loaderText ? compact(all.toString()) : null;
        Boolean inOutput = loaderText ? Boolean.TRUE : null;

        List<ContentFinding> findings = new ArrayList<>(byFormatting(file.runs(), matcher, config, reveal, given));
        for (Page page : pages) {
            if (page.text().isEmpty()) {
                continue;
            }
            boolean measured = file.checked() && !file.unchecked().contains(page.number());
            findings.addAll(smuggledIn(page.number(), page.text(), matcher, config, reveal, inOutput));
            findings.addAll(matchedByPattern(page.number(), page.text(), measured, matcher, config, reveal, inOutput,
                    hiddenTexts));
        }
        String note = file.notes().isEmpty() ? null : String.join("; ", file.notes());
        return new Result(findings, file.checked(), note);
    }

    /** Passages the file itself hides: white text, hidden styles, hidden sheets. */
    private static List<ContentFinding> byFormatting(List<HiddenRun> runs, InstructionMatcher matcher, Config config,
            boolean reveal, String given) {
        List<ContentFinding> findings = new ArrayList<>();
        for (HiddenRun run : runs) {
            String text = Smuggled.stripInvisible(run.text());
            List<String> labels = labels(matcher.find(text));
            String instruction = labels.isEmpty() ? "none" : "pattern";
            if (run.onlyIfInstruction() && instruction.equals("none")) {
                continue;
            }
            Boolean inOutput = given == null ? null : given.contains(compact(run.text()));
            findings.add(finding(run.page(), run.visibility(), instruction, text, config, reveal, run.reasons(), labels,
                    inOutput));
        }
        return findings;
    }

    private static HiddenRun.Found fileRuns(Path path, Config.Visibility visibility) {
        if (path == null || !Files.isRegularFile(path)) {
            return new HiddenRun.Found(List.of(), List.of("there is no file to check the text against"), Set.of(),
                    false);
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String suffix = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
        // Each check reopens the file with a parser of its own. A failure leaves the file
        // unchecked and says so, rather than ending the scan.
        try {
            switch (suffix) {
                case ".pdf" -> {
                    return PdfVisibility.runs(path, visibility);
                }
                case ".docx" -> {
                    return OfficeVisibility.docx(path, visibility);
                }
                case ".xlsx", ".xlsm" -> {
                    return OfficeVisibility.xlsx(path, visibility);
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            return new HiddenRun.Found(List.of(), List.of("the hidden-text check failed ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"), Set.of(), false);
        }
        if (suffix.equals(".txt") || IMAGES.contains(suffix)) {
            // Plain text has no formatting to hide text with; invisible Unicode is checked on the text.
            return new HiddenRun.Found(List.of(), List.of(), Set.of(), true);
        }
        String kind = suffix.isEmpty() ? "files without an extension" : suffix + " files";
        return new HiddenRun.Found(List.of(), List.of("hidden text is not checked in " + kind + " by complydoc4j yet"),
                Set.of(), false);
    }

    /** The text without spacing or invisible characters, case folded, to tell one passage from another. */
    private static String compact(String text) {
        return Py.casefold(Smuggled.stripInvisible(text).replaceAll("(?U)\\s+", ""));
    }

    private static List<ContentFinding> smuggledIn(int number, String text, InstructionMatcher matcher, Config config,
            boolean reveal, Boolean inOutput) {
        List<ContentFinding> findings = new ArrayList<>();
        for (Smuggled smuggled : Smuggled.find(text, config.hidden().visibility().zeroWidthRun())) {
            switch (smuggled.kind()) {
                case "tag_characters" -> {
                    if (letters(smuggled.text()) < config.hidden().visibility().minCharacters()) {
                        continue;
                    }
                    List<String> reasons = new ArrayList<>();
                    reasons.add("decoded from invisible Unicode tag characters");
                    reasons.addAll(labels(matcher.find(smuggled.text())));
                    findings.add(finding(number, "confirmed", "confirmed", smuggled.text(), config, reveal,
                            List.of("invisible Unicode tag characters"), reasons, inOutput));
                }
                case "zero_width_run" -> {
                    String around = Smuggled.stripInvisible(
                            Py.slice(text, Math.max(0, smuggled.start() - 60), smuggled.end() + 60));
                    findings.add(finding(number, "confirmed", "none", around, config, reveal,
                            List.of((smuggled.end() - smuggled.start()) + " zero-width characters in a row"),
                            List.of(), inOutput));
                }
                default -> {
                    List<String> found = labels(matcher.find(smuggled.text()));
                    findings.add(finding(number, "suspected", found.isEmpty() ? "none" : "pattern", smuggled.text(),
                            config, reveal, List.of("bidirectional override: displayed in a different order"),
                            found, inOutput));
                }
            }
        }
        return findings;
    }

    private static List<ContentFinding> matchedByPattern(int number, String text, boolean measured,
            InstructionMatcher matcher, Config config, boolean reveal, Boolean inOutput, List<String> hiddenTexts) {
        List<ContentFinding> findings = new ArrayList<>();
        String stripped = Smuggled.stripInvisible(text);
        for (Group group : grouped(matcher.find(stripped), stripped)) {
            // A passage already reported as a hidden run of the file is not reported again.
            if (!group.parts().isEmpty() && group.parts().stream()
                    .allMatch(part -> hiddenTexts.stream().anyMatch(h -> h.contains(compact(part))))) {
                continue;
            }
            findings.add(finding(number, measured ? "visible" : "not_measured", "pattern",
                    Py.slice(stripped, group.start(), group.end()), config, reveal, List.of(), group.labels(),
                    inOutput));
        }
        return findings;
    }

    /** Start and end, in code points, of each sentence in {@code text} an instruction pattern matches. */
    public static List<int[]> instructionSpans(String text, Config config) {
        List<int[]> spans = new ArrayList<>();
        for (Group group : grouped(new InstructionMatcher(config.hidden()).find(text), text)) {
            spans.add(new int[] {group.start(), group.end()});
        }
        return spans;
    }

    private record Group(int start, int end, List<String> labels, List<String> parts) {}

    /** Matches in the same sentence, as one passage. */
    private static List<Group> grouped(List<InstructionMatcher.Match> matches, String text) {
        Map<Long, List<String>> groups = new TreeMap<>();
        Map<Long, List<String>> parts = new TreeMap<>();
        for (InstructionMatcher.Match match : matches) {
            int[] sentence = sentence(text, match.start(), match.end());
            long key = ((long) sentence[0] << 32) | sentence[1];
            List<String> labels = groups.computeIfAbsent(key, k -> new ArrayList<>());
            if (!labels.contains(match.label())) {
                labels.add(match.label());
            }
            parts.computeIfAbsent(key, k -> new ArrayList<>()).add(Py.slice(text, match.start(), match.end()));
        }
        List<Group> out = new ArrayList<>();
        groups.forEach((key, labels) -> out.add(new Group((int) (key >>> 32), (int) (key & 0xffffffffL), labels,
                parts.get(key))));
        return out;
    }

    /** The sentence around a match, in code points, cut down to an excerpt if it is long. */
    private static int[] sentence(String text, int start, int end) {
        int startU = Py.utf16(text, start);
        int endU = Py.utf16(text, end);
        int leftU = 0;
        Matcher before = BOUNDARY.matcher(text).region(0, startU);
        while (before.find()) {
            leftU = before.end();
        }
        Matcher after = BOUNDARY.matcher(text).region(endU, text.length()).useTransparentBounds(true);
        int rightU = after.find() ? after.start() : text.length();
        int left = Py.cp(text, leftU);
        int right = Py.cp(text, rightU);
        if (right - left > EXCERPT) {
            left = Math.max(left, start - 80);
            right = Math.min(right, Math.max(end, left + EXCERPT));
        }
        return new int[] {left, right};
    }

    private static List<String> labels(List<InstructionMatcher.Match> matches) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        matches.forEach(m -> labels.add(m.label()));
        return new ArrayList<>(labels);
    }

    private static int letters(String text) {
        return (int) text.codePoints().filter(Py::isAlnum).count();
    }

    private static ContentFinding finding(Integer page, String visibility, String instruction, String text,
            Config config, boolean reveal, List<String> hiddenReasons, List<String> instructionReasons,
            Boolean inLoaderOutput) {
        String clean = Py.collapse(text);
        String excerpt = masked(Py.head(clean, EXCERPT * 2), config, reveal);
        if (Py.len(excerpt) > EXCERPT) {
            excerpt = Py.rstrip(Py.head(excerpt, EXCERPT - 1)) + "…";
        }
        return new ContentFinding(page, visibility, instruction, severityOf(visibility, instruction), excerpt,
                Py.len(clean), hiddenReasons, instructionReasons, null, inLoaderOutput, Fingerprints.passage(clean));
    }

    private static String masked(String text, Config config, boolean reveal) {
        if (reveal || Py.blank(text)) {
            return text;
        }
        return Masking.maskMatches(text, Scanner.scanText(text, config.sensitive(), false, true).matches()).text();
    }
}
