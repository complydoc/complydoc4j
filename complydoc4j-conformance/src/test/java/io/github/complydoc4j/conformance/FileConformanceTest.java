package io.github.complydoc4j.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.complydoc4j.Complydoc4j;
import io.github.complydoc4j.report.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * A folder of PDF, Word and Excel files, audited by complydoc4j's own readers, against the
 * report {@code cd.security_audit} wrote for the same folder.
 *
 * <p>The two read text with different libraries (PDFBox and pdfium), so a line or a column
 * can differ where neither is wrong. What is compared is what a reader of the report acts
 * on: which identifiers each document holds, and every hidden or instruction-like passage,
 * with its visibility, its reasons and the fingerprint of its words.
 */
class FileConformanceTest {

    @TempDir
    static Path folder;

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> eachFileMatchesPython() throws IOException {
        Path golden = Golden.ROOT.resolve("files");
        try (Stream<Path> files = Files.list(golden)) {
            for (Path file : files.filter(f -> !f.getFileName().toString().equals("audit.json")).toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        Map<String, Object> python = (Map<String, Object>) Golden.read(golden.resolve("audit.json"));
        Map<String, Object> java = (Map<String, Object>) Json.normalise(Complydoc4j.audit(folder));
        Json.write(java, Path.of("target", "reports", "files.json"));

        Map<String, Map<String, Object>> expected = byPath(python);
        Map<String, Map<String, Object>> actual = byPath(java);
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(DynamicTest.dynamicTest("the same documents", () ->
                assertEquals(expected.keySet(), actual.keySet())));
        for (String name : expected.keySet()) {
            Map<String, Object> e = expected.get(name);
            Map<String, Object> a = actual.getOrDefault(name, Map.of());
            tests.add(check(name, "shape", shape(e), shape(a)));
            tests.add(check(name, "identifiers", identifiers(e), identifiers(a)));
            tests.add(check(name, "hidden text", hidden(e), hidden(a)));
        }
        return tests.stream();
    }

    private static DynamicTest check(String name, String part, Object expected, Object actual) {
        return DynamicTest.dynamicTest(name + ": " + part, () -> assertEquals(Json.write(expected), Json.write(actual)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byPath(Map<String, Object> report) {
        Map<String, Map<String, Object>> out = new java.util.TreeMap<>();
        for (Object d : (List<Object>) report.get("documents")) {
            Map<String, Object> document = (Map<String, Object>) d;
            out.put((String) document.get("relative_path"), document);
        }
        return out;
    }

    private static Map<String, Object> shape(Map<String, Object> document) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("format", "page_count", "page_count_known", "visibility_checked", "visibility_note")) {
            out.put(key, document.get(key));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> identifiers(Map<String, Object> document) {
        Map<String, Object> sensitive = (Map<String, Object>) document.get("sensitive");
        if (sensitive == null) {
            return List.of();
        }
        List<Object> out = new ArrayList<>();
        for (Object m : (List<Object>) sensitive.get("matches")) {
            Map<String, Object> match = (Map<String, Object>) m;
            out.add(match.get("page") + " " + match.get("category") + " " + match.get("masked") + " "
                    + match.get("evidence") + " " + match.get("fingerprint"));
        }
        out.sort(Comparator.comparing(Object::toString));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> hidden(Map<String, Object> document) {
        List<Object> out = new ArrayList<>();
        for (Object f : (List<Object>) document.get("content_findings")) {
            Map<String, Object> finding = (Map<String, Object>) f;
            Map<String, Object> kept = new LinkedHashMap<>();
            for (String key : List.of("page", "visibility", "instruction", "severity", "hidden_reasons",
                    "instruction_reasons", "excerpt", "fingerprint")) {
                kept.put(key, finding.get(key));
            }
            out.add(kept);
        }
        out.sort(Comparator.comparing(Object::toString));
        return out;
    }
}
