package io.github.complydoc4j.sensitive;

import io.github.complydoc4j.text.Py;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Stable names for findings: the same on every run that finds the same thing, in Java or
 * in Python, and without what was found in them. What an ignore file names.
 */
public final class Fingerprints {

    private static final Pattern SEPARATORS =
            Pattern.compile("[\\s\\-_.·/]+", Pattern.UNICODE_CHARACTER_CLASS);

    private Fingerprints() {}

    /** {@code id-} and 16 hex characters. Spacing, dashes and case do not change it. */
    public static String identifier(String category, String value) {
        return "id-" + digest("identifier", category, Py.casefold(SEPARATORS.matcher(value).replaceAll("")));
    }

    /** {@code ct-} and 16 hex characters, from the passage's words and nothing between them. */
    public static String passage(String text) {
        return "ct-" + digest("passage", Py.casefold(Py.collapse(text)));
    }

    private static String digest(String... parts) {
        String joined = "complydoc\0" + String.join("\0", parts);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
