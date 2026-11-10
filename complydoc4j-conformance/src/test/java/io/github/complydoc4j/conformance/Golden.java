package io.github.complydoc4j.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The Python package's answers, as complydoc's {@code src/scripts/java_conformance.py} wrote them. */
final class Golden {

    static final Path ROOT = Path.of("src/test/resources/golden");

    private Golden() {}

    /** A JSON file, read with the YAML parser, of which JSON is a subset. */
    static Object read(Path path) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        try {
            return new Yaml(new SafeConstructor(options)).load(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The fixture names, from the texts Python read. */
    static List<String> fixtures() {
        try (Stream<Path> files = Files.list(ROOT.resolve("texts"))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
