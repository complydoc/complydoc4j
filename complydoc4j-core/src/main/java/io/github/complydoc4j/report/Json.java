package io.github.complydoc4j.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON written exactly as the Python package writes a report: keys sorted, two spaces of
 * indentation, characters as they are, numbers as Python prints them. Two reports of the
 * same run, one from each language, then differ only where their content does.
 *
 * <p>Records are written as objects, their components named in snake case.
 */
public final class Json {

    private Json() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(normalise(value), out, 0);
        return out.append('\n').toString();
    }

    public static Path write(Object value, Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, write(value), StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records, maps and collections as sorted maps and lists, everything else as it is. */
    public static Object normalise(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name().toLowerCase(java.util.Locale.ROOT);
        }
        if (value instanceof Record record) {
            Map<String, Object> map = new TreeMap<>();
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    map.put(snake(component.getName()), normalise(component.getAccessor().invoke(record)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return map;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> map = new TreeMap<>();
            m.forEach((k, v) -> map.put(String.valueOf(k), normalise(v)));
            return map;
        }
        if (value instanceof Collection<?> c) {
            List<Object> list = new ArrayList<>();
            c.forEach(item -> list.add(normalise(item)));
            return list;
        }
        if (value instanceof int[] ints) {
            List<Object> list = new ArrayList<>();
            for (int i : ints) {
                list.add(i);
            }
            return list;
        }
        return String.valueOf(value);
    }

    static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            out.append(pyFloat(((Number) value).doubleValue()));
        } else if (value instanceof Number n) {
            out.append(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(first ? "\n" : ",\n");
                first = false;
                indent(out, depth + 1);
                string((String) entry.getKey(), out);
                out.append(": ");
                write(entry.getValue(), out, depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                out.append(first ? "\n" : ",\n");
                first = false;
                indent(out, depth + 1);
                write(item, out, depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append(']');
        } else {
            throw new IllegalArgumentException("not JSON: " + value.getClass());
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c) && !validPair(s, i)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static boolean validPair(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    /** A float as Python's {@code repr} prints it: shortest digits, exponent below 1e-4 or from 1e16. */
    public static String pyFloat(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0.0" : "0.0";
        }
        BigDecimal exact = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = exact.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - exact.scale();
        String sign = d < 0 ? "-" : "";
        if (exponent < -4 || exponent >= 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
        }
        String plain = exact.abs().toPlainString();
        return sign + (plain.contains(".") ? plain : plain + ".0");
    }
}
