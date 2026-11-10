package io.github.complydoc4j.text;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The patterns in {@code sensitive.yaml} and {@code hidden.yaml}, which are written for
 * Python's {@code re}, compiled to mean the same in Java.
 *
 * <p>Python matches {@code \d}, {@code \w}, {@code \s} and {@code \b} by Unicode, and only
 * {@code \n} ends a line; Java needs flags for both. Named groups are spelled
 * {@code (?P<name>...)} in Python and {@code (?<name>...)} in Java.
 */
public final class PyRegex {

    /** Python's {@code re.IGNORECASE}. */
    public static final int IGNORECASE = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Python's {@code re.MULTILINE}. */
    public static final int MULTILINE = Pattern.MULTILINE;

    private static final int ALWAYS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private PyRegex() {}

    public static Pattern compile(String python) {
        return compile(python, 0);
    }

    public static Pattern compile(String python, int flags) {
        return CACHE.computeIfAbsent(flags + "\0" + python,
                key -> Pattern.compile(translate(python), flags | ALWAYS));
    }

    /** The pattern in Java's spelling. */
    static String translate(String python) {
        StringBuilder out = new StringBuilder(python.length());
        boolean inClass = false;
        for (int i = 0; i < python.length(); i++) {
            char c = python.charAt(i);
            if (c == '\\' && i + 1 < python.length()) {
                char next = python.charAt(i + 1);
                // `\Z` is the end of the text in Python; `\z` in Java.
                out.append(c).append(!inClass && next == 'Z' ? 'z' : next);
                i++;
                continue;
            }
            if (inClass) {
                // Java reads `[` and `&&` inside a class as set operations; Python as letters.
                if (c == '[' || (c == '&' && i + 1 < python.length() && python.charAt(i + 1) == '&')) {
                    out.append('\\');
                }
                if (c == ']') {
                    inClass = false;
                }
                out.append(c);
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                // A `]` straight after `[` or `[^` is a letter in Python.
                if (i + 1 < python.length() && python.charAt(i + 1) == '^') {
                    out.append('^');
                    i++;
                }
                if (i + 1 < python.length() && python.charAt(i + 1) == ']') {
                    out.append("\\]");
                    i++;
                }
                continue;
            }
            if (python.startsWith("(?P<", i)) {
                out.append("(?<");
                i += 3;
                continue;
            }
            if (python.startsWith("(?P=", i)) {
                int close = python.indexOf(')', i);
                out.append("\\k<").append(python, i + 4, close).append('>');
                i = close;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
