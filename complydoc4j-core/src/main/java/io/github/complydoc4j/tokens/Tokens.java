package io.github.complydoc4j.tokens;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token counts in the encoding the Python report counts with, {@code o200k_base}. It stands
 * in for models whose own tokenizer is not public, so the count is approximate for them.
 */
public final class Tokens {

    public static final String ENCODING = "o200k_base";
    public static final String FIDELITY = "approximate";

    private static final Encoding O200K = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    private Tokens() {}

    /** Tokens in {@code text}, special tokens read as ordinary text. */
    public static int count(String text) {
        return text.isEmpty() ? 0 : O200K.countTokensOrdinary(text);
    }
}
