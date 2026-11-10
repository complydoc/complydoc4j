package io.github.complydoc4j.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code sensitive.yaml} and {@code hidden.yaml}.
 *
 * <p>The shipped copies are the Python package's own files, byte for byte; the conformance
 * suite checks that they still are. A folder of one's own can be given instead, as
 * {@code --config-dir} is given to complydoc.
 */
public final class ConfigLoader {

    private static final String SHIPPED = "/io/github/complydoc4j/config/";

    private static volatile Config shipped;

    private ConfigLoader() {}

    /** The shipped configuration, loaded once. */
    public static Config shipped() {
        Config config = shipped;
        if (config == null) {
            synchronized (ConfigLoader.class) {
                if (shipped == null) {
                    shipped = load(resource("sensitive.yaml"), resource("hidden.yaml"), "shipped");
                }
                config = shipped;
            }
        }
        return config;
    }

    /** The configuration in {@code directory}, falling back to the shipped {@code hidden.yaml}. */
    public static Config load(Path directory) {
        Path hidden = directory.resolve("hidden.yaml");
        return load(read(directory.resolve("sensitive.yaml")),
                Files.isRegularFile(hidden) ? read(hidden) : resource("hidden.yaml"),
                directory.toAbsolutePath().toString());
    }

    private static Config load(String sensitiveRaw, String hiddenRaw, String sourceDir) {
        Map<String, Object> sensitive = parse(sensitiveRaw, "sensitive.yaml");
        Map<String, Object> hidden = parse(hiddenRaw, "hidden.yaml");
        return new Config(sensitive(sensitive), hidden(hidden), sourceDir,
                digest(List.of(sensitiveRaw, hiddenRaw)));
    }

    private static Config.Sensitive sensitive(Map<String, Object> raw) {
        Map<String, Object> masking = map(raw.get("masking"));
        Config.Masking mask = new Config.Masking(
                integer(masking.get("reveal_tail_chars"), 4),
                string(masking.get("mask_char"), "•"),
                strings(masking.get("never_reveal")));
        Map<String, CategoryConfig> categories = new LinkedHashMap<>();
        map(raw.get("categories")).forEach((id, value) -> categories.put(id, category(map(value))));
        return new Config.Sensitive(integer(raw.get("schema_version"), 1), mask, categories);
    }

    private static CategoryConfig category(Map<String, Object> raw) {
        String detector = string(raw.get("detector"), "regex");
        boolean model = raw.get("model") != null || "ner".equals(detector);
        for (Object link : list(raw.get("fallback"))) {
            Map<String, Object> spec = map(link);
            model |= spec.get("model") != null || "ner".equals(spec.get("detector"));
        }
        return new CategoryConfig(
                bool(raw.get("enabled"), true),
                bool(raw.get("silent"), false),
                string(raw.get("label"), ""),
                string(raw.get("region"), "international"),
                detector,
                string(raw.get("severity"), "medium"),
                strings(raw.get("patterns")),
                strings(raw.get("context_patterns")),
                strings(raw.get("validators")),
                strings(raw.get("context_terms")),
                integer(raw.get("context_window_chars"), 60),
                number(raw.get("min_confidence"), 0.0),
                model);
    }

    private static Config.Hidden hidden(Map<String, Object> raw) {
        Map<String, Object> visibility = map(raw.get("visibility"));
        Map<String, Object> instructions = map(raw.get("instructions"));
        List<Config.InstructionPattern> patterns = new ArrayList<>();
        for (Object item : list(instructions.get("patterns"))) {
            Map<String, Object> pattern = map(item);
            patterns.add(new Config.InstructionPattern(
                    string(pattern.get("id"), ""),
                    string(pattern.get("label"), ""),
                    strings(pattern.get("languages")),
                    strings(pattern.get("regexes"))));
        }
        return new Config.Hidden(
                integer(raw.get("schema_version"), 1),
                new Config.Visibility(integer(visibility.get("min_characters"), 4),
                        integer(visibility.get("zero_width_run"), 8)),
                number(instructions.get("classifier_threshold"), 0.8),
                patterns);
    }

    static String digest(List<String> raws) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", raws).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> parse(String raw, String name) {
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(raw);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException(name + " must contain a mapping at the top level");
        }
        return map(parsed);
    }

    private static String resource(String name) {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(SHIPPED + name)) {
            if (in == null) {
                throw new IllegalStateException("the shipped " + name + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("configuration file not found: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : list(value)) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }
}
