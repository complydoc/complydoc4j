package io.github.complydoc4j.read;

import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.text.PyRegex;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * The text of an HTML page as an HTML loader passes it on: every text node outside
 * {@code script}, {@code style}, {@code template} and {@code noscript}, block elements on
 * their own lines and table cells separated by tabs. Text a browser would not show is
 * included, as loaders include it, and the hidden-text check reports it. complydoc's
 * {@code ingest/html.py}.
 */
public final class Html {

    /** Elements whose content is never page text. */
    public static final Set<String> SKIPPED = Set.of("script", "style", "template", "noscript");

    private static final Set<String> BLOCKS = Set.of("address", "article", "aside", "blockquote", "br", "caption",
            "dd", "details", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "summary", "table",
            "title", "tr", "ul");
    private static final Set<String> CELLS = Set.of("td", "th");
    private static final Pattern SPACE = PyRegex.compile("\\s+");
    private static final Pattern CELL_GAP = PyRegex.compile(" *\\t *");
    private static final Pattern XML_DECLARATION = PyRegex.compile("^\\s*<\\?xml[^>]*\\?>");
    /** Letters below which a page with scripts is said to build its text in the browser. */
    static final int FEW_LETTERS = 200;

    private Html() {}

    /** The document, its own charset declaration honoured. */
    public static Document parse(byte[] markup) {
        return Jsoup.parse(new String(markup, charset(markup)));
    }

    public static Document parse(String markup) {
        return Jsoup.parse(XML_DECLARATION.matcher(markup).replaceFirst(""));
    }

    /** The page's declared charset, read from its bytes; UTF-8 when it declares none. */
    private static java.nio.charset.Charset charset(byte[] markup) {
        String head = new String(markup, 0, Math.min(markup.length, 2048), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher declared = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w-]+)").matcher(head);
        try {
            return declared.find() ? java.nio.charset.Charset.forName(declared.group(1))
                    : java.nio.charset.StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
    }

    /** The document element, as lxml's {@code document_fromstring} returns it. */
    public static Element root(Document document) {
        return document.child(0);
    }

    /** The text under {@code root}, one block per line, scripts and styles left out. */
    public static String text(Element root) {
        StringBuilder parts = new StringBuilder();
        visit(root, false, parts);
        List<String> lines = new ArrayList<>();
        for (String line : parts.toString().split("\n", -1)) {
            String kept = Py.strip(CELL_GAP.matcher(line).replaceAll("\t"));
            if (!kept.isEmpty()) {
                lines.add(kept);
            }
        }
        return String.join("\n", lines);
    }

    private static void visit(Element element, boolean preserve, StringBuilder parts) {
        String tag = element.normalName();
        if (SKIPPED.contains(tag)) {
            return;
        }
        boolean keep = preserve || tag.equals("pre");
        if (BLOCKS.contains(tag)) {
            parts.append('\n');
        } else if (CELLS.contains(tag)) {
            parts.append('\t');
        }
        // lxml's text and each child's tail are jsoup's text nodes, in the same order.
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode text) {
                String raw = text.getWholeText();
                parts.append(keep ? raw : SPACE.matcher(raw).replaceAll(" "));
            } else if (child instanceof Element inner) {
                visit(inner, keep, parts);
            }
        }
        if (BLOCKS.contains(tag)) {
            parts.append('\n');
        }
    }

    /** How many {@code script} elements the document has. */
    public static int scripts(Element root) {
        return root.getElementsByTag("script").size();
    }
}
