package io.github.complydoc4j.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.complydoc4j.Gateway;
import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.config.ConfigLoader;
import io.github.complydoc4j.hidden.ContentFinding;
import io.github.complydoc4j.hidden.HiddenCheck;
import io.github.complydoc4j.report.Json;
import io.github.complydoc4j.sensitive.Scanner;
import io.github.complydoc4j.sensitive.SensitiveMatch;
import io.github.complydoc4j.sensitive.Validators;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Validators, scans, masking and hidden-text findings, against Python's on the same input. */
class TextConformanceTest {

    private static final Config PATTERNS = ConfigLoader.shipped().patternsOnly();
    private static final Gateway GATEWAY = Gateway.create(PATTERNS);

    @Test
    void theShippedConfigurationIsPythonsByteForByte() {
        for (String name : List.of("sensitive.yaml", "hidden.yaml")) {
            String shipped = Golden.text(Path.of("../complydoc4j-core/src/main/resources/io/github/complydoc4j/config", name));
            assertEquals(Golden.text(Golden.ROOT.resolve("config").resolve(name)), shipped,
                    name + " differs from the Python package's: copy it from the golden folder");
        }
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> everyValidatorGivesPythonsVerdict() {
        Map<String, Map<String, Boolean>> expected =
                (Map<String, Map<String, Boolean>>) Golden.read(Golden.ROOT.resolve("validators.json"));
        return expected.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            // Written in code, or declared in sensitive.yaml as Python's are.
            Predicate<String> check = Validators.VALIDATORS.containsKey(entry.getKey())
                    ? Validators.VALIDATORS.get(entry.getKey())
                    : value -> Validators.declared(PATTERNS.sensitive().checks().get(entry.getKey()), value);
            List<String> wrong = new ArrayList<>();
            entry.getValue().forEach((value, verdict) -> {
                if (check.test(value) != verdict) {
                    wrong.add("'" + value + "' should be " + verdict);
                }
            });
            assertEquals(List.of(), wrong.subList(0, Math.min(20, wrong.size())),
                    wrong.size() + " of " + entry.getValue().size() + " values differ");
        }));
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> scansMasksAndHiddenTextMatchPython() {
        return Golden.fixtures().stream().flatMap(fixture -> {
            List<Map<String, Object>> answers =
                    (List<Map<String, Object>>) Golden.read(Golden.ROOT.resolve("texts").resolve(fixture + ".json"));
            List<DynamicTest> tests = new ArrayList<>();
            for (int i = 0; i < answers.size(); i++) {
                Map<String, Object> answer = answers.get(i);
                String text = (String) answer.get("text");
                String name = fixture + (i == 0 ? " (whole)" : " paragraph " + i);
                tests.add(DynamicTest.dynamicTest(name + ": matches", () -> assertEquals(
                        canonical(answer.get("matches")),
                        canonical(matches(Scanner.scanText(text, PATTERNS.sensitive()).matches())))));
                tests.add(DynamicTest.dynamicTest(name + ": masked", () ->
                        assertEquals(answer.get("masked"), Scanner.maskText(text, PATTERNS).text())));
                tests.add(DynamicTest.dynamicTest(name + ": hidden", () -> assertEquals(
                        canonical(answer.get("hidden")),
                        canonical(findings(HiddenCheck.findHidden(text, PATTERNS, false))))));
                // Golden data written by a complydoc from before the gateway has no answer to check.
                if (answer.containsKey("gateway")) {
                    tests.add(DynamicTest.dynamicTest(name + ": gateway", () -> assertEquals(
                            canonical(answer.get("gateway")), canonical(gateway(GATEWAY.filter(text))))));
                }
            }
            return tests.stream();
        });
    }

    private static List<Map<String, Object>> matches(List<SensitiveMatch> matches) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SensitiveMatch m : matches) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("category", m.category());
            map.put("line", m.line());
            map.put("column", m.column());
            map.put("length", m.length());
            map.put("masked", m.masked());
            map.put("evidence", m.evidence());
            map.put("confidence", m.confidence());
            map.put("validators_passed", m.validatorsPassed());
            map.put("context_term", m.contextTerm());
            map.put("fingerprint", m.fingerprint());
            out.add(map);
        }
        return out;
    }

    private static List<Map<String, Object>> findings(List<ContentFinding> findings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentFinding f : findings) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("visibility", f.visibility());
            map.put("instruction", f.instruction());
            map.put("severity", f.severity());
            map.put("excerpt", f.excerpt());
            map.put("characters", f.characters());
            map.put("hidden_reasons", f.hiddenReasons());
            map.put("instruction_reasons", f.instructionReasons());
            map.put("fingerprint", f.fingerprint());
            out.add(map);
        }
        return out;
    }

    private static Map<String, Object> gateway(Gateway.Result r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", r.text());
        map.put("verdict", r.verdict().code());
        map.put("masked", r.masked());
        map.put("removed", r.removed());
        map.put("invisible", r.invisible());
        map.put("identifiers", new java.util.TreeMap<>(r.identifiers()));
        map.put("sensitive", r.sensitive());
        map.put("hidden", r.hidden());
        map.put("instruction", r.instruction());
        map.put("unscanned", r.unscanned());
        return map;
    }

    /** Both sides written the same way, so a difference reads as a line of JSON. */
    private static String canonical(Object value) {
        return Json.write(value);
    }
}
