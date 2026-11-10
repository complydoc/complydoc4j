package io.github.complydoc4j.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Python's string semantics, where complydoc's answers depend on them.
 *
 * <p>The report is the contract with the Python package, and its numbers are Python's: a
 * column, a length or a count of characters is in code points, not in UTF-16 units, and
 * {@code str.split()} splits on Unicode whitespace, not on what {@code \s} means in Java.
 * Everything that is reported goes through here.
 */
public final class Py {

    private Py() {}

    /** {@code len(s)}: code points. */
    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** The code-point index of a UTF-16 offset. */
    public static int cp(String s, int utf16) {
        return s.codePointCount(0, utf16);
    }

    /** The UTF-16 offset of a code-point index, clamped to the string as a slice is. */
    public static int utf16(String s, int codePoint) {
        if (codePoint <= 0) {
            return 0;
        }
        int total = len(s);
        if (codePoint >= total) {
            return s.length();
        }
        return s.offsetByCodePoints(0, codePoint);
    }

    /** {@code s[start:end]} with code-point indices; negative bounds count from the end. */
    public static String slice(String s, int start, int end) {
        int total = len(s);
        if (start < 0) {
            start = Math.max(0, total + start);
        }
        if (end < 0) {
            end = Math.max(0, total + end);
        }
        start = Math.min(start, total);
        end = Math.min(end, total);
        if (start >= end) {
            return "";
        }
        return s.substring(utf16(s, start), utf16(s, end));
    }

    /** {@code s[:end]}. */
    public static String head(String s, int end) {
        return slice(s, 0, end);
    }

    /** {@code str.isspace()} for one character. */
    public static boolean isSpace(int c) {
        switch (c) {
            case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0 -> {
                return true;
            }
            default -> {
                int type = Character.getType(c);
                return type == Character.SPACE_SEPARATOR
                        || type == Character.LINE_SEPARATOR
                        || type == Character.PARAGRAPH_SEPARATOR;
            }
        }
    }

    /** {@code str.isalpha()} for one character. */
    public static boolean isAlpha(int c) {
        return Character.isLetter(c);
    }

    /** {@code str.isdigit()} for one character, as far as {@code int()} can read it. */
    public static boolean isDigit(int c) {
        return Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER;
    }

    /** {@code str.isalnum()} for one character: letters, and every kind of number. */
    public static boolean isAlnum(int c) {
        if (Character.isLetter(c)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /** {@code str.isprintable()} for one character. */
    public static boolean isPrintable(int c) {
        if (c == ' ') {
            return true;
        }
        int type = Character.getType(c);
        return !(type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SPACE_SEPARATOR);
    }

    /** {@code s.isalpha()}: not empty, and every character a letter. */
    public static boolean allAlpha(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(Py::isAlpha);
    }

    /** {@code s.isdigit()}. */
    public static boolean allDigits(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(Py::isDigit);
    }

    /** {@code s.isalnum()}. */
    public static boolean allAlnum(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(Py::isAlnum);
    }

    /** {@code s.isupper()}: at least one cased character, and none in lower case. */
    public static boolean isUpper(String s) {
        boolean cased = false;
        for (int c : s.codePoints().toArray()) {
            if (Character.isLowerCase(c) || Character.isTitleCase(c)) {
                return false;
            }
            if (Character.isUpperCase(c)) {
                cased = true;
            }
        }
        return cased;
    }

    /** {@code str.split()} with no separator: on runs of whitespace, dropping empty parts. */
    public static List<String> split(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        s.codePoints().forEach(c -> {
            if (isSpace(c)) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.appendCodePoint(c);
            }
        });
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** {@code " ".join(s.split())}. */
    public static String collapse(String s) {
        return String.join(" ", split(s));
    }

    /** {@code s.strip()}. */
    public static String strip(String s) {
        return rstrip(lstrip(s));
    }

    public static String lstrip(String s) {
        int i = 0;
        while (i < s.length()) {
            int c = s.codePointAt(i);
            if (!isSpace(c)) {
                break;
            }
            i += Character.charCount(c);
        }
        return s.substring(i);
    }

    public static String rstrip(String s) {
        int end = s.length();
        while (end > 0) {
            int c = s.codePointBefore(end);
            if (!isSpace(c)) {
                break;
            }
            end -= Character.charCount(c);
        }
        return s.substring(0, end);
    }

    /** Whether {@code s.strip()} is empty. */
    public static boolean blank(String s) {
        return s.codePoints().allMatch(Py::isSpace);
    }

    /**
     * {@code str.casefold()}, as near as the JDK comes without ICU.
     *
     * <p>Upper-casing first folds what lower-casing alone keeps apart, such as {@code ß} and
     * {@code ss}, and final and medial sigma.
     */
    public static String casefold(String s) {
        return s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /** {@code s.lower()}. */
    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** {@code s.upper()}. */
    public static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** {@code str.splitlines()}: on every line boundary Python knows, without the ends. */
    public static List<String> splitlines(String s) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            boolean boundary = c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || c == 0x1C
                    || c == 0x1D || c == 0x1E || c == 0x85 || c == 0x2028 || c == 0x2029;
            if (boundary) {
                lines.add(s.substring(start, i));
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
            i++;
        }
        if (start < s.length()) {
            lines.add(s.substring(start));
        }
        return lines;
    }
}
