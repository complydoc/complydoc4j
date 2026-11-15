package io.github.complydoc4j.hidden;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.read.DocumentReader;
import io.github.complydoc4j.read.Html;
import io.github.complydoc4j.read.Mail;
import io.github.complydoc4j.read.Pptx;
import io.github.complydoc4j.text.Py;
import io.github.complydoc4j.text.PyRegex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * Markup that hides text in HTML, Markdown, PowerPoint and email. As for Word, visibility is
 * read from the markup rather than from a rendering.
 *
 * <ul>
 *   <li>HTML: the {@code hidden} attribute, {@code display: none}, {@code visibility: hidden},
 *       {@code opacity: 0}, transparent text, white text with no background behind it, text
 *       below the minimum size, zero height with hidden overflow, and comments; from
 *       {@code style} attributes and from {@code <style>} rules on a tag, a class or an id.
 *       Text placed far off screen is suspected, since that depends on the layout.
 *   <li>Markdown: HTML comments, link references used as comments, and inline HTML hidden as
 *       above. Code blocks are left alone.
 *   <li>PowerPoint: hidden slides, shapes outside the slide, and text below the minimum size.
 *   <li>Email: the HTML body, checked as HTML.
 * </ul>
 *
 * <p>Web pages and Markdown routinely hide menus, dialogs and comments, so a passage hidden
 * there is reported only when it reads as an instruction. complydoc's {@code hidden/markup.py}.
 */
public final class MarkupVisibility {

    private static final int MAX_TEXT = 2000;
    private static final double OFF_SCREEN_PT = 500.0;
    private static final Pattern DECLARATION = PyRegex.compile("([a-zA-Z-]+)\\s*:\\s*([^;]+)");
    private static final Pattern RULE = PyRegex.compile("([^{}@]+)\\{([^{}]*)\\}");
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern SELECTOR = PyRegex.compile("^([a-zA-Z][a-zA-Z0-9]*)?(?:([.#])([\\w-]+))?$");
    private static final Pattern LENGTH = PyRegex.compile("^(-?\\d*\\.?\\d+)\\s*(px|pt|em|rem|%)?$");
    private static final Pattern HEX = PyRegex.compile("#([0-9a-f]{3}|[0-9a-f]{6})");
    private static final Pattern RGB = PyRegex.compile("rgba?\\((\\d+),(\\d+),(\\d+)(?:,[\\d.]+)?\\)");

    private static final Pattern FENCE = Pattern.compile("^(```|~~~).*?^\\1[ \\t]*$",
            Pattern.DOTALL | Pattern.MULTILINE | Pattern.UNIX_LINES);
    private static final Pattern INLINE_CODE = PyRegex.compile("`[^`\\n]+`");
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);
    private static final Pattern REFERENCE_COMMENT = PyRegex.compile(
            "^[ \\t]*\\[[^\\]]*\\]:[ \\t]*(?:#|<>)[ \\t]*[(\"'](.*)[)\"'][ \\t]*$", PyRegex.MULTILINE);
    private static final Pattern TAG = PyRegex.compile("<[a-zA-Z][^>]*>");

    private record Rule(String tag, String name, Map<String, String> declarations) {}

    private MarkupVisibility() {}

    private static void add(List<HiddenRun> runs, String text, String visibility, List<String> reasons,
            Config.Visibility config, Integer page, boolean routine) {
        String clean = Py.collapse(text);
        if (clean.codePoints().filter(Py::isAlnum).count() >= config.minCharacters()) {
            runs.add(new HiddenRun(page, Py.head(clean, MAX_TEXT), visibility, reasons, routine));
        }
    }

    private static HiddenRun.Found unreadable(String what, Exception e) {
        return new HiddenRun.Found(List.of(), List.of(what + " (" + e.getMessage() + ")"), Set.of(), false);
    }

    // --- HTML -----------------------------------------------------------------------------

    public static HiddenRun.Found html(Path path, Config.Visibility config) {
        try {
            byte[] markup = Files.readAllBytes(path);
            if (Py.blank(new String(markup, java.nio.charset.StandardCharsets.ISO_8859_1))) {
                return new HiddenRun.Found(List.of(), List.of(), Set.of(), true);
            }
            return new HiddenRun.Found(walk(Html.root(Html.parse(markup)), config), List.of(), Set.of(), true);
        } catch (IOException | RuntimeException e) {
            return unreadable("the file could not be parsed to check for hidden text", e);
        }
    }

    /** Hidden passages in HTML given as a string, such as an email's body. */
    public static List<HiddenRun> html(String markup, Config.Visibility config) {
        if (Py.blank(markup)) {
            return List.of();
        }
        return walk(Html.root(Html.parse(markup)), config);
    }

    private static Map<String, String> declarations(String style) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = DECLARATION.matcher(style == null ? "" : style);
        while (m.find()) {
            found.put(Py.lower(m.group(1)), Py.lower(Py.strip(m.group(2).replace("!important", ""))));
        }
        return found;
    }

    private static List<Rule> stylesheet(Element root) {
        List<Rule> rules = new ArrayList<>();
        for (Element style : root.getElementsByTag("style")) {
            String css = CSS_COMMENT.matcher(style.data()).replaceAll("");
            Matcher rule = RULE.matcher(css);
            while (rule.find()) {
                Map<String, String> body = declarations(rule.group(2));
                if (body.isEmpty()) {
                    continue;
                }
                for (String selector : rule.group(1).split(",", -1)) {
                    String trimmed = Py.strip(selector);
                    Matcher m = SELECTOR.matcher(trimmed);
                    if (trimmed.isEmpty() || !m.find()) {
                        continue;
                    }
                    rules.add(new Rule(m.group(1) == null ? null : Py.lower(m.group(1)),
                            m.group(2) == null ? null : m.group(2) + m.group(3), body));
                }
            }
        }
        return rules;
    }

    private static Map<String, String> computed(Element node, String tag, List<Rule> rules) {
        Set<String> classes = new HashSet<>();
        Py.split(node.attr("class")).forEach(name -> classes.add("." + name));
        String id = node.hasAttr("id") && !node.attr("id").isEmpty() ? "#" + node.attr("id") : null;
        Map<String, String> found = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.tag() != null && !rule.tag().equals(tag)) {
                continue;
            }
            if (rule.name() != null && !classes.contains(rule.name()) && !rule.name().equals(id)) {
                continue;
            }
            found.putAll(rule.declarations());
        }
        found.putAll(declarations(node.hasAttr("style") ? node.attr("style") : null));
        return found;
    }

    private static Double number(String value) {
        try {
            return value == null ? null : Double.valueOf(Py.strip(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A CSS length in points; a relative one only when it is zero, the one value it can be measured at. */
    private static Double points(String value) {
        Matcher m = LENGTH.matcher(Py.strip(value == null ? "" : value));
        if (!m.find()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "px" : m.group(2);
        if (unit.equals("pt")) {
            return n;
        }
        if (unit.equals("px")) {
            return n * 0.75;
        }
        return n == 0 ? 0.0 : null;
    }

    private static boolean white(String value, Config.Visibility config) {
        String compact = value.replace(" ", "");
        if (compact.equals("white")) {
            return true;
        }
        int[] channels;
        Matcher hex = HEX.matcher(compact);
        Matcher rgb = RGB.matcher(compact);
        if (hex.matches()) {
            String digits = hex.group(1);
            if (digits.length() == 3) {
                StringBuilder doubled = new StringBuilder();
                digits.chars().forEach(c -> doubled.append((char) c).append((char) c));
                digits = doubled.toString();
            }
            channels = new int[] {Integer.parseInt(digits.substring(0, 2), 16),
                Integer.parseInt(digits.substring(2, 4), 16), Integer.parseInt(digits.substring(4, 6), 16)};
        } else if (rgb.matches()) {
            channels = new int[] {Integer.parseInt(rgb.group(1)), Integer.parseInt(rgb.group(2)),
                Integer.parseInt(rgb.group(3))};
        } else {
            return false;
        }
        return Math.min(channels[0], Math.min(channels[1], channels[2])) >= (int) (config.nearWhite() * 255);
    }

    private static List<String>[] hiding(Element node, Map<String, String> d, boolean background,
            Config.Visibility config) {
        List<String> confirmed = new ArrayList<>();
        List<String> suspected = new ArrayList<>();
        if (node.hasAttr("hidden")) {
            confirmed.add("hidden attribute");
        }
        if ("none".equals(d.get("display"))) {
            confirmed.add("display: none");
        }
        if ("hidden".equals(d.get("visibility")) || "collapse".equals(d.get("visibility"))) {
            confirmed.add("visibility: hidden");
        }
        Double opacity = number(d.get("opacity"));
        if (opacity != null && opacity <= 0.01) {
            confirmed.add("opacity: 0");
        }
        String color = d.get("color");
        if ("transparent".equals(color)) {
            confirmed.add("transparent text");
        } else if (color != null && !color.isEmpty() && white(color, config) && !background) {
            confirmed.add("white text");
        }
        Double size = points(d.get("font-size"));
        if (size != null && size < config.minFontSizePt()) {
            confirmed.add("font size below " + PdfVisibility.pyG(config.minFontSizePt()) + "pt");
        }
        if ("hidden".equals(d.get("overflow"))
                && (Double.valueOf(0.0).equals(points(d.get("height")))
                        || Double.valueOf(0.0).equals(points(d.get("max-height"))))) {
            confirmed.add("zero height with overflow hidden");
        }
        for (String offset : List.of("left", "top", "text-indent", "margin-left")) {
            Double placed = points(d.get(offset));
            if (placed != null && placed <= -OFF_SCREEN_PT) {
                suspected.add("positioned off screen");
                break;
            }
        }
        @SuppressWarnings("unchecked")
        List<String>[] out = new List[] {confirmed, suspected};
        return out;
    }

    /** What the markup hides, element by element; everything inside a hidden element is reported with it. */
    private static List<HiddenRun> walk(Element root, Config.Visibility config) {
        List<Rule> rules = stylesheet(root);
        List<HiddenRun> runs = new ArrayList<>();
        // What comes before the root, as lxml's preceding siblings: comments before <html>.
        Node parent = root.parent();
        for (Node sibling : parent == null ? List.<Node>of() : parent.childNodes()) {
            if (sibling == root) {
                break;
            }
            visit(sibling, false, rules, runs, config);
        }
        visit(root, false, rules, runs, config);
        return runs;
    }

    private static void visit(Node node, boolean background, List<Rule> rules, List<HiddenRun> runs,
            Config.Visibility config) {
        if (node instanceof Comment comment) {
            add(runs, comment.getData(), "confirmed", List.of("HTML comment"), config, null, true);
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String tag = element.normalName();
        if (Html.SKIPPED.contains(tag)) {
            return;
        }
        Map<String, String> d = computed(element, tag, rules);
        boolean shaded = background || element.hasAttr("bgcolor") || d.containsKey("background")
                || d.containsKey("background-color") || d.containsKey("background-image");
        List<String>[] hidden = hiding(element, d, shaded, config);
        if (!hidden[0].isEmpty() || !hidden[1].isEmpty()) {
            add(runs, Html.text(element), hidden[0].isEmpty() ? "suspected" : "confirmed",
                    hidden[0].isEmpty() ? hidden[1] : hidden[0], config, null, true);
            return;
        }
        for (Node child : element.childNodes()) {
            visit(child, shaded, rules, runs, config);
        }
    }

    // --- Markdown --------------------------------------------------------------------------

    public static HiddenRun.Found markdown(Path path, Config.Visibility config) {
        try {
            String text = DocumentReader.read(path).pages().get(0);
            return new HiddenRun.Found(markdown(text, config), List.of(), Set.of(), true);
        } catch (IOException | RuntimeException e) {
            return unreadable("the file could not be read to check for hidden text", e);
        }
    }

    /** Hidden passages in Markdown text, code blocks and inline code left alone. */
    public static List<HiddenRun> markdown(String text, Config.Visibility config) {
        String rest = blank(INLINE_CODE, blank(FENCE, text));
        List<HiddenRun> runs = new ArrayList<>();
        Matcher comment = HTML_COMMENT.matcher(rest);
        while (comment.find()) {
            add(runs, comment.group(1), "confirmed", List.of("HTML comment, not shown when the Markdown is rendered"),
                    config, null, true);
        }
        Matcher reference = REFERENCE_COMMENT.matcher(rest);
        while (reference.find()) {
            add(runs, reference.group(1), "confirmed",
                    List.of("link reference used as a comment, not shown when the Markdown is rendered"), config, null,
                    true);
        }
        String html = HTML_COMMENT.matcher(rest).replaceAll(" ");
        if (TAG.matcher(html).find()) {
            runs.addAll(walk(Jsoup.parseBodyFragment(html).body(), config));
        }
        return runs;
    }

    private static String blank(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, " ".repeat(Py.len(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    // --- PowerPoint ---------------------------------------------------------------------------

    public static HiddenRun.Found pptx(Path path, Config.Visibility config) {
        List<Pptx.Slide> slides;
        try {
            slides = Pptx.read(path);
        } catch (IOException | RuntimeException e) {
            return unreadable("the file could not be opened to check for hidden text", e);
        }
        List<HiddenRun> runs = new ArrayList<>();
        double smallest = config.minFontSizePt() * 100;
        for (Pptx.Slide slide : slides) {
            if (slide.hidden()) {
                String text = String.join("\n", slide.shapes().stream().map(Pptx.Shape::text).toList());
                add(runs, text, "confirmed", List.of("hidden slide"), config, slide.number(), false);
                continue;
            }
            for (Pptx.Shape shape : slide.shapes()) {
                List<String> reasons = new ArrayList<>();
                if (shape.outside()) {
                    reasons.add("placed outside the slide");
                }
                if (shape.largestSize() != null && shape.largestSize() < smallest) {
                    reasons.add("font size below " + PdfVisibility.pyG(config.minFontSizePt()) + "pt");
                }
                if (!reasons.isEmpty()) {
                    add(runs, shape.text(), "confirmed", reasons, config, slide.number(), false);
                }
            }
        }
        return new HiddenRun.Found(runs, List.of(), Set.of(), true);
    }

    // --- Email --------------------------------------------------------------------------------

    public static HiddenRun.Found eml(Path path, Config.Visibility config) {
        String markup;
        try {
            markup = Mail.htmlBody(Mail.message(path));
        } catch (IOException | RuntimeException e) {
            return unreadable("the file could not be opened to check for hidden text", e);
        }
        if (markup == null) {
            return new HiddenRun.Found(List.of(), List.of(), Set.of(), true);
        }
        try {
            return new HiddenRun.Found(html(markup, config), List.of(), Set.of(), true);
        } catch (RuntimeException e) {
            return unreadable("the HTML body could not be parsed to check for hidden text", e);
        }
    }
}
