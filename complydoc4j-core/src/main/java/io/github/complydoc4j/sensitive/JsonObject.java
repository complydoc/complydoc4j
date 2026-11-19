package io.github.complydoc4j.sensitive;

/**
 * Just enough of Python's {@code json.loads} to say whether a text is one JSON object with a
 * given top-level key, as a token's header must be. Anything that is not valid JSON is not
 * an object.
 */
final class JsonObject {

    private final String text;
    private int at;

    private JsonObject(String text) {
        this.text = text;
    }

    /** Whether {@code text} is a JSON object, and {@code key} one of its own keys. */
    static boolean hasKey(String text, String key) {
        JsonObject parser = new JsonObject(text);
        try {
            parser.space();
            if (parser.peek() != '{') {
                return false;
            }
            boolean found = parser.object(key);
            parser.space();
            return parser.at == text.length() && found;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw new IllegalArgumentException("end of text");
        }
        return text.charAt(at);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new IllegalArgumentException("expected " + c);
        }
        at++;
    }

    private void space() {
        while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    /** Parse an object; with {@code key}, say whether it is one of the object's keys. */
    private boolean object(String key) {
        boolean found = false;
        expect('{');
        space();
        if (peek() == '}') {
            at++;
            return false;
        }
        while (true) {
            space();
            String name = string();
            found |= name.equals(key) && key != null;
            space();
            expect(':');
            value();
            space();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect('}');
            return found;
        }
    }

    private void value() {
        space();
        char c = peek();
        switch (c) {
            case '{' -> object(null);
            case '[' -> array();
            case '"' -> string();
            default -> literal();
        }
    }

    private void array() {
        expect('[');
        space();
        if (peek() == ']') {
            at++;
            return;
        }
        while (true) {
            value();
            space();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect(']');
            return;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = peek();
            at++;
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("control character in a string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = peek();
            at++;
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) {
                        throw new IllegalArgumentException("short escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw new IllegalArgumentException("bad escape");
            }
        }
    }

    private static final java.util.regex.Pattern LITERAL = java.util.regex.Pattern.compile(
            "true|false|null|NaN|-?Infinity|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?");

    private void literal() {
        java.util.regex.Matcher match = LITERAL.matcher(text).region(at, text.length());
        if (!match.lookingAt()) {
            throw new IllegalArgumentException("not a value");
        }
        at = match.end();
    }
}
