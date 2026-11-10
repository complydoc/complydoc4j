package io.github.complydoc4j.langchain4j;

import static io.github.complydoc4j.langchain4j.StageWarnings.count;

import io.github.complydoc4j.report.Report;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A few lines on what each step of a trace did, as {@code cd.observe} prints them. */
final class Summary {

    private Summary() {}

    static List<String> lines(String name, Report report, String error, Path path) {
        if (report == null || report.trace() == null) {
            return List.of(name + ": not recorded (" + (error != null ? error : "the block is open") + ")");
        }
        Report.Trace trace = report.trace();
        List<List<Report.Stage>> steps = steps(trace.stages());
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US, "%s: %s in %.2f s; observing took %.2f s", trace.name(),
                count(steps.size(), "step"), trace.seconds(), trace.overheadSeconds()));
        for (int number = 0; number < steps.size(); number++) {
            List<Report.Stage> group = steps.get(number);
            Report.Stage first = group.get(0);
            String calls = group.size() > 1 ? " (" + group.size() + " calls)" : "";
            lines.add(String.format(Locale.US, "  %d. %-9s %-34s %s", number + 1, first.kind(),
                    first.component() + calls, String.join(", ", parts(group))));
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

    /** Stages one after another of the same component and method, as one step each. */
    private static List<List<Report.Stage>> steps(List<Report.Stage> stages) {
        List<List<Report.Stage>> steps = new ArrayList<>();
        for (Report.Stage stage : stages) {
            if (stage.parent() != null) {
                continue;
            }
            List<Report.Stage> last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            Report.Stage previous = last == null ? null : last.get(last.size() - 1);
            if (previous != null && previous.kind().equals(stage.kind())
                    && previous.component().equals(stage.component()) && previous.method().equals(stage.method())) {
                last.add(stage);
            } else {
                steps.add(new ArrayList<>(List.of(stage)));
            }
        }
        return steps;
    }

    private static List<String> parts(List<Report.Stage> group) {
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
            Set<String> found = new HashSet<>();
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
        return parts;
    }
}
