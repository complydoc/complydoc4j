package io.github.complydoc4j.langchain4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a component was set up with, read off its fields: the parameters a stage records,
 * and the endpoints an embedding model or store sends to.
 *
 * <p>A secret is never recorded: a field whose name says it holds a key, a token or a
 * password is skipped.
 */
final class Settings {

    private static final Pattern SECRET = Pattern.compile("(?i).*(key|token|secret|password|credential|auth).*");
    private static final Pattern URL = Pattern.compile("(?i)^(https?|grpcs?|wss?)://[^\\s]+$");
    private static final int DEPTH = 4;

    private Settings() {}

    /** The component's own settings of plain types, named in snake case. */
    static Map<String, Object> parameters(Object component) {
        Map<String, Object> found = new LinkedHashMap<>();
        for (Field field : fields(component.getClass())) {
            if (SECRET.matcher(field.getName()).matches()) {
                continue;
            }
            Object value = read(field, component);
            if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
                found.put(snake(field.getName()), value instanceof Enum<?> e ? e.name() : value);
            } else if (value instanceof String s && !URL.matcher(s).matches()) {
                found.put(snake(field.getName()), s);
            } else if (value instanceof java.util.regex.Pattern p) {
                found.put(snake(field.getName()), p.pattern());
            } else if (value != null && !value.getClass().getName().startsWith("java.")
                    && !value.getClass().isSynthetic()) {
                // A part the component is built from, such as a splitter's sub-splitter.
                found.put(snake(field.getName()), value.getClass().getSimpleName());
            }
        }
        return found;
    }

    /**
     * Every URL in the component's settings, however deep: the base URL of the client an
     * embedding model or a store calls.
     */
    static List<String> endpoints(Object component) {
        Set<String> found = new LinkedHashSet<>();
        walk(component, 0, found, new IdentityHashMap<>());
        return new ArrayList<>(found);
    }

    /** The host part of each endpoint, in order. */
    static List<String> hosts(List<String> endpoints) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String endpoint : endpoints) {
            try {
                String host = URI.create(endpoint).getHost();
                if (host != null) {
                    hosts.add(host);
                }
            } catch (IllegalArgumentException e) {
                // Not a URI after all.
            }
        }
        return new ArrayList<>(hosts);
    }

    private static void walk(Object value, int depth, Set<String> found, Map<Object, Boolean> seen) {
        if (value == null || depth > DEPTH || seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        if (value instanceof String s) {
            if (URL.matcher(s).matches()) {
                found.add(s);
            }
            return;
        }
        if (value instanceof URI uri) {
            found.add(uri.toString());
            return;
        }
        if (value instanceof java.net.URL url) {
            found.add(url.toString());
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type.isArray() || value instanceof Number || value instanceof Boolean
                || type.getName().startsWith("java.") || type.getName().startsWith("javax.")
                || type.getName().startsWith("sun.") || type.getName().startsWith("jdk.")) {
            return;
        }
        for (Field field : fields(type)) {
            walk(read(field, value), depth + 1, found, seen);
        }
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (RuntimeException | IllegalAccessException e) {
            // A field the module system keeps closed is a setting not recorded.
            return null;
        }
    }

    static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (out.length() > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
