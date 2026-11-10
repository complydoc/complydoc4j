package io.github.complydoc4j.hidden;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A run of characters that carry text without displaying it.
 *
 * <ul>
 *   <li>Tag characters (U+E0000 to U+E007F) mirror ASCII and render as nothing. They are
 *       decoded.
 *   <li>A long run of zero-width characters is a payload whose scheme is unknown, so only the
 *       run is reported.
 *   <li>A bidirectional override displays the text after it in reverse.
 * </ul>
 *
 * @param start the run's start, in code points
 * @param end its end, in code points
 * @param text what tag characters spell, or the text an override reverses; empty for a
 *     zero-width run
 */
public record Smuggled(String kind, int start, int end, String text) {

    private static final int TAG_FIRST = 0xE0000;
    private static final String ZERO_WIDTH = "​‌‍⁠﻿᠎";

    private static final Pattern INVISIBLE =
            Pattern.compile("[\\x{E0000}-\\x{E007F}" + ZERO_WIDTH + "‪-‮⁦-⁩]");
    private static final Pattern TAG_RUN = Pattern.compile("[\\x{E0000}-\\x{E007F}]+");
    private static final Pattern ZERO_WIDTH_RUN = Pattern.compile("[" + ZERO_WIDTH + "]+");
    private static final Pattern OVERRIDE = Pattern.compile("[‭‮]([^‬\n]*)");

    /** Every run of invisible carrier characters in {@code text}, in order. */
    public static List<Smuggled> find(String text, int zeroWidthRun) {
        List<Smuggled> found = new ArrayList<>();
        Matcher tags = TAG_RUN.matcher(text);
        while (tags.find()) {
            StringBuilder decoded = new StringBuilder();
            tags.group().codePoints().map(c -> c - TAG_FIRST).filter(c -> 0x20 <= c && c <= 0x7E)
                    .forEach(decoded::appendCodePoint);
            found.add(new Smuggled("tag_characters", cp(text, tags.start()), cp(text, tags.end()), decoded.toString()));
        }
        Matcher zero = ZERO_WIDTH_RUN.matcher(text);
        while (zero.find()) {
            if (zero.group().codePointCount(0, zero.group().length()) >= zeroWidthRun) {
                found.add(new Smuggled("zero_width_run", cp(text, zero.start()), cp(text, zero.end()), ""));
            }
        }
        Matcher override = OVERRIDE.matcher(text);
        while (override.find()) {
            if (override.group(1).codePoints().anyMatch(io.github.complydoc4j.text.Py::isAlnum)) {
                found.add(new Smuggled("bidi_override", cp(text, override.start()), cp(text, override.end()),
                        override.group(1)));
            }
        }
        found.sort(Comparator.comparingInt(Smuggled::start));
        return found;
    }

    /** The text without invisible characters, so patterns still read {@code ig​nore}. */
    public static String stripInvisible(String text) {
        return INVISIBLE.matcher(text).replaceAll("");
    }

    private static int cp(String text, int utf16) {
        return text.codePointCount(0, utf16);
    }
}
