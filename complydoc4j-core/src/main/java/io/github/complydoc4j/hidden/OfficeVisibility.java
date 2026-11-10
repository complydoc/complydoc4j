package io.github.complydoc4j.hidden;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.read.OfficeXml;
import io.github.complydoc4j.text.Py;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * Formatting that hides text in Word and Excel files. Neither format has a fixed rendering
 * to check pixels against, so visibility is read from the markup, and each reason is a fact
 * about the file, reported as confirmed.
 *
 * <ul>
 *   <li>Word: text formatted as hidden, directly or through its style; white text with no
 *       shading or page colour behind it; text below the minimum size.
 *   <li>Excel: hidden and very hidden sheets, hidden rows and columns, cells whose number
 *       format displays nothing, and white text on an unfilled cell.
 * </ul>
 */
public final class OfficeVisibility {

    private static final int MAX_TEXT = 2000;

    private OfficeVisibility() {}

    private static boolean white(String value, Config.Visibility config) {
        if (value == null || value.length() < 6) {
            return false;
        }
        String rgb = value.substring(value.length() - 6);
        try {
            int least = 255;
            for (int i = 0; i < 6; i += 2) {
                least = Math.min(least, Integer.parseInt(rgb.substring(i, i + 2), 16));
            }
            return least >= (int) (config.nearWhite() * 255);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int letters(String text) {
        return (int) text.codePoints().filter(Py::isAlnum).count();
    }

    // --- Word ---------------------------------------------------------------------------

    public static HiddenRun.Found docx(Path path, Config.Visibility config) {
        OfficeXml file;
        try {
            file = OfficeXml.open(path);
        } catch (IOException e) {
            return unreadable(e);
        }
        try (file) {
            Element body = file.element("word/document.xml");
            if (body == null) {
                return unreadable(new IOException("there is no word/document.xml"));
            }
            Map<String, Map<String, Object>> styles = styles(file.element("word/styles.xml"), config);
            Element background = OfficeXml.child(body, "background");
            boolean colouredPage = background != null && !white(OfficeXml.attr(background, "color"), config);
            List<HiddenRun> runs = new ArrayList<>();
            for (Element paragraph : OfficeXml.descendants(body, "p")) {
                Element paragraphProps = OfficeXml.child(paragraph, "pPr");
                String paragraphStyle = styleId(paragraphProps, "pStyle");
                boolean shadedParagraph = shaded(paragraphProps, config);
                List<String> current = List.of();
                StringBuilder parts = new StringBuilder();
                for (Element run : OfficeXml.descendants(paragraph, "r")) {
                    StringBuilder text = new StringBuilder();
                    OfficeXml.descendants(run, "t").forEach(t -> text.append(t.getTextContent()));
                    if (text.length() == 0) {
                        continue;
                    }
                    Element runProps = OfficeXml.child(run, "rPr");
                    Map<String, Object> effective = new HashMap<>();
                    for (String id : new String[] {paragraphStyle, styleId(runProps, "rStyle")}) {
                        if (id != null) {
                            effective.putAll(styles.getOrDefault(id, Map.of()));
                        }
                    }
                    if (runProps != null) {
                        effective.putAll(properties(runProps, config));
                    }
                    List<String> reasons = new ArrayList<>();
                    if (Boolean.TRUE.equals(effective.get("vanish"))) {
                        reasons.add("formatted as hidden text");
                    }
                    boolean behind = shadedParagraph || Boolean.TRUE.equals(effective.get("shaded")) || colouredPage;
                    if (white((String) effective.get("color"), config) && !behind) {
                        reasons.add("white text");
                    }
                    if (effective.get("size") instanceof Double size && size < config.minFontSizePt()) {
                        reasons.add("font size below " + PdfVisibility.pyG(config.minFontSizePt()) + "pt");
                    }
                    if (!reasons.equals(current)) {
                        flush(runs, current, parts, config);
                        current = reasons;
                        parts.setLength(0);
                    }
                    parts.append(text);
                }
                flush(runs, current, parts, config);
            }
            return new HiddenRun.Found(runs, List.of(), Set.of(), true);
        } catch (IOException | RuntimeException e) {
            return unreadable(e);
        }
    }

    private static void flush(List<HiddenRun> runs, List<String> reasons, CharSequence parts, Config.Visibility config) {
        String text = Py.collapse(parts.toString());
        if (!reasons.isEmpty() && letters(text) >= config.minCharacters()) {
            runs.add(new HiddenRun(null, Py.head(text, MAX_TEXT), "confirmed", reasons, false));
        }
    }

    private static String styleId(Element props, String tag) {
        Element element = props == null ? null : OfficeXml.child(props, tag);
        return element == null ? null : OfficeXml.attr(element, "val");
    }

    private static boolean shaded(Element props, Config.Visibility config) {
        Element shading = props == null ? null : OfficeXml.child(props, "shd");
        if (shading == null) {
            return false;
        }
        String fill = OfficeXml.attr(shading, "fill");
        return fill != null && !fill.equals("auto") && !white(fill, config);
    }

    private static Map<String, Object> properties(Element props, Config.Visibility config) {
        Map<String, Object> found = new HashMap<>();
        Element vanish = OfficeXml.child(props, "vanish");
        if (vanish != null) {
            String value = OfficeXml.attr(vanish, "val");
            found.put("vanish", !(value != null && List.of("0", "false", "off").contains(value)));
        }
        Element color = OfficeXml.child(props, "color");
        if (color != null) {
            found.put("color", OfficeXml.attr(color, "val"));
        }
        Element size = OfficeXml.child(props, "sz");
        if (size != null) {
            try {
                found.put("size", Integer.parseInt(OfficeXml.attr(size, "val")) / 2.0);
            } catch (NumberFormatException e) {
                // A size that is not a number is no size.
            }
        }
        if (OfficeXml.child(props, "highlight") != null || shaded(props, config)) {
            found.put("shaded", true);
        }
        return found;
    }

    /** Run properties per style id, with each style's base styles applied first. */
    private static Map<String, Map<String, Object>> styles(Element root, Config.Visibility config) {
        Map<String, Map<String, Object>> own = new LinkedHashMap<>();
        Map<String, String> basedOn = new HashMap<>();
        if (root == null) {
            return own;
        }
        for (Element style : OfficeXml.children(root, "style")) {
            String id = OfficeXml.attr(style, "styleId");
            if (id == null || id.isEmpty()) {
                continue;
            }
            Element props = OfficeXml.child(style, "rPr");
            own.put(id, props != null ? properties(props, config) : Map.of());
            Element base = OfficeXml.child(style, "basedOn");
            basedOn.put(id, base != null ? OfficeXml.attr(base, "val") : null);
        }
        Map<String, Map<String, Object>> resolved = new HashMap<>();
        for (String id : own.keySet()) {
            resolve(id, own, basedOn, resolved, 0);
        }
        return resolved;
    }

    private static Map<String, Object> resolve(String id, Map<String, Map<String, Object>> own,
            Map<String, String> basedOn, Map<String, Map<String, Object>> resolved, int depth) {
        Map<String, Object> known = resolved.get(id);
        if (known != null) {
            return known;
        }
        String parent = basedOn.get(id);
        Map<String, Object> merged = parent != null && own.containsKey(parent) && depth < 20
                ? new HashMap<>(resolve(parent, own, basedOn, resolved, depth + 1)) : new HashMap<>();
        merged.putAll(own.getOrDefault(id, Map.of()));
        resolved.put(id, merged);
        return merged;
    }

    // --- Excel ------------------------------------------------------------------------------

    public static HiddenRun.Found xlsx(Path path, Config.Visibility config) {
        OfficeXml file;
        try {
            file = OfficeXml.open(path);
        } catch (IOException e) {
            return unreadable(e);
        }
        try (file) {
            OfficeXml.Workbook workbook = file.workbook();
            List<HiddenRun> runs = new ArrayList<>();
            int number = 0;
            for (OfficeXml.Sheet sheet : workbook.sheets()) {
                number++;
                sheetRuns(runs, number, sheet, workbook, config);
            }
            return new HiddenRun.Found(runs, List.of(), Set.of(), true);
        } catch (IOException | RuntimeException e) {
            return unreadable(e);
        }
    }

    /** What one sheet hides: the whole of it when the sheet is hidden, else cell by cell. */
    private static void sheetRuns(List<HiddenRun> runs, int number, OfficeXml.Sheet sheet, OfficeXml.Workbook workbook,
            Config.Visibility config) {
        String title = pyRepr(sheet.name());
        if (sheet.state().equals("hidden") || sheet.state().equals("veryHidden")) {
            String kind = sheet.state().equals("veryHidden") ? "very hidden" : "hidden";
            List<String> values = sheet.cells().stream().map(OfficeXml.Cell::value).filter(v -> !Py.blank(v)).toList();
            add(runs, number, values, kind + " sheet " + title, config);
            return;
        }
        Set<Integer> hiddenRows = new HashSet<>(sheet.hiddenRows());
        Set<Integer> hiddenColumns = new HashSet<>(sheet.hiddenColumns());
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (OfficeXml.Cell cell : sheet.cells()) {
            if (Py.blank(cell.value())) {
                continue;
            }
            String reason = null;
            if (hiddenRows.contains(cell.row())) {
                reason = "hidden row in sheet " + title;
            } else if (hiddenColumns.contains(cell.column())) {
                reason = "hidden column in sheet " + title;
            } else if (showsNothing(workbook.numberFormat(cell.style()))) {
                reason = "number format that displays nothing, in sheet " + title;
            } else if (whiteOnNothing(workbook.style(cell.style()), config)) {
                reason = "white text in sheet " + title;
            }
            if (reason != null) {
                groups.computeIfAbsent(reason, k -> new ArrayList<>()).add(cell.value());
            }
        }
        groups.forEach((reason, values) -> add(runs, number, values, reason, config));
    }

    private static void add(List<HiddenRun> runs, int page, List<String> values, String reason,
            Config.Visibility config) {
        String text = String.join(" | ", values.stream().map(Py::collapse).toList());
        if (letters(text) >= config.minCharacters()) {
            runs.add(new HiddenRun(page, Py.head(text, MAX_TEXT), "confirmed", List.of(reason), false));
        }
    }

    private static boolean showsNothing(String format) {
        String shown = format == null ? "" : format;
        return shown.contains(";") && Py.blank(shown.replace(";", ""));
    }

    private static boolean whiteOnNothing(OfficeXml.CellStyle style, Config.Visibility config) {
        if (style == null || !white(style.fontRgb(), config)) {
            return false;
        }
        if (style.fillType() == null || style.fillType().equals("none")) {
            return true;
        }
        return style.fillRgb() != null && white(style.fillRgb(), config);
    }

    /** A sheet's name as Python's {@code repr} quotes it, as the reason names it. */
    static String pyRepr(String name) {
        if (name.contains("'") && !name.contains("\"")) {
            return "\"" + name + "\"";
        }
        return "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static HiddenRun.Found unreadable(Exception e) {
        return new HiddenRun.Found(List.of(),
                List.of("the file could not be opened to check for hidden text (" + e.getMessage() + ")"), Set.of(),
                false);
    }
}
