package io.github.complydoc4j.read;

import io.github.complydoc4j.report.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The parts of a Word or Excel file complydoc4j reads, straight from the XML in the zip, with
 * elements and attributes named by their local names.
 *
 * <p>Cell values are written as the Python package writes them ({@code str()} of what openpyxl
 * reads): {@code 1250.5}, {@code 42}, {@code True}, {@code 2026-01-05 00:00:00}.
 */
public final class OfficeXml implements AutoCloseable {

    private final ZipFile zip;

    private OfficeXml(ZipFile zip) {
        this.zip = zip;
    }

    public static OfficeXml open(Path path) throws IOException {
        return new OfficeXml(new ZipFile(path.toFile()));
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    /** The root element of an entry, or null where the file has no such entry. */
    public Element element(String entry) throws IOException {
        ZipEntry found = zip.getEntry(entry);
        if (found == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(found)) {
            return parser().parse(in).getDocumentElement();
        } catch (SAXException e) {
            throw new IOException(entry + " is not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder parser() throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // A document is untrusted: no external entities, no DTDs.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IOException(e);
        }
    }

    // --- elements by local name -------------------------------------------------------------

    public static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    public static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    /** Every element below {@code parent} of that name, in document order. */
    public static List<Element> descendants(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList all = parent.getElementsByTagNameNS("*", name);
        for (int i = 0; i < all.getLength(); i++) {
            out.add((Element) all.item(i));
        }
        return out;
    }

    /** An attribute by its local name, whatever its namespace; null when absent. */
    public static String attr(Element element, String name) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node a = element.getAttributes().item(i);
            String local = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
            if (name.equals(local)) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    // --- Word -----------------------------------------------------------------------------------

    /**
     * The text python-docx gives: each body paragraph with text, then each table cell with
     * text, a line each.
     */
    public String docxText() throws IOException {
        Element document = element("word/document.xml");
        Element body = document == null ? null : child(document, "body");
        if (body == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Element paragraph : children(body, "p")) {
            String text = paragraphText(paragraph);
            if (!io.github.complydoc4j.text.Py.blank(text)) {
                parts.add(text);
            }
        }
        for (Element table : children(body, "tbl")) {
            for (Element row : children(table, "tr")) {
                for (Element cell : children(row, "tc")) {
                    List<String> lines = children(cell, "p").stream().map(OfficeXml::paragraphText).toList();
                    String text = String.join("\n", lines);
                    if (!io.github.complydoc4j.text.Py.blank(text)) {
                        parts.add(text);
                    }
                }
            }
        }
        return String.join("\n", parts);
    }

    /** A paragraph's text as python-docx reads it: runs' text, tabs and breaks. */
    private static String paragraphText(Element paragraph) {
        StringBuilder out = new StringBuilder();
        for (Element run : descendants(paragraph, "r")) {
            for (Node n = run.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e)) {
                    continue;
                }
                switch (e.getLocalName()) {
                    case "t" -> out.append(e.getTextContent());
                    case "tab" -> out.append('\t');
                    case "br", "cr" -> out.append('\n');
                    default -> { }
                }
            }
        }
        return out.toString();
    }

    // --- Excel ----------------------------------------------------------------------------------

    public record Cell(int row, int column, String value, int style) {}

    public record Sheet(String name, String state, List<Cell> cells, List<Integer> hiddenRows,
            List<Integer> hiddenColumns) {}

    public record CellStyle(String numberFormat, String fontRgb, String fillType, String fillRgb) {}

    public record Workbook(List<Sheet> sheets, List<CellStyle> styles) {
        public CellStyle style(int index) {
            return index >= 0 && index < styles.size() ? styles.get(index) : null;
        }

        public String numberFormat(int index) {
            CellStyle style = style(index);
            return style == null ? "General" : style.numberFormat();
        }
    }

    /** Built-in number formats, as openpyxl names them. */
    private static final Map<Integer, String> BUILTIN_FORMATS = Map.ofEntries(
            Map.entry(0, "General"), Map.entry(1, "0"), Map.entry(2, "0.00"), Map.entry(3, "#,##0"),
            Map.entry(4, "#,##0.00"), Map.entry(9, "0%"), Map.entry(10, "0.00%"), Map.entry(11, "0.00E+00"),
            Map.entry(12, "# ?/?"), Map.entry(13, "# ??/??"), Map.entry(14, "mm-dd-yy"), Map.entry(15, "d-mmm-yy"),
            Map.entry(16, "d-mmm"), Map.entry(17, "mmm-yy"), Map.entry(18, "h:mm AM/PM"),
            Map.entry(19, "h:mm:ss AM/PM"), Map.entry(20, "h:mm"), Map.entry(21, "h:mm:ss"),
            Map.entry(22, "m/d/yy h:mm"), Map.entry(37, "#,##0 ;(#,##0)"), Map.entry(38, "#,##0 ;[Red](#,##0)"),
            Map.entry(39, "#,##0.00;(#,##0.00)"), Map.entry(40, "#,##0.00;[Red](#,##0.00)"),
            Map.entry(45, "mm:ss"), Map.entry(46, "[h]:mm:ss"), Map.entry(47, "mmss.0"), Map.entry(48, "##0.0E+0"),
            Map.entry(49, "@"));

    public Workbook workbook() throws IOException {
        Element book = element("xl/workbook.xml");
        if (book == null) {
            throw new IOException("there is no xl/workbook.xml");
        }
        Map<String, String> targets = new HashMap<>();
        Element rels = element("xl/_rels/workbook.xml.rels");
        if (rels != null) {
            for (Element rel : children(rels, "Relationship")) {
                String target = attr(rel, "Target");
                targets.put(attr(rel, "Id"), target.startsWith("/") ? target.substring(1) : "xl/" + target);
            }
        }
        List<String> shared = sharedStrings();
        List<CellStyle> styles = styles();
        List<Sheet> sheets = new ArrayList<>();
        Element list = child(book, "sheets");
        for (Element entry : list == null ? List.<Element>of() : children(list, "sheet")) {
            String state = attr(entry, "state");
            Element root = element(targets.getOrDefault(attr(entry, "id"), ""));
            sheets.add(sheet(attr(entry, "name"), state == null ? "visible" : state, root, shared, styles));
        }
        return new Workbook(sheets, styles);
    }

    private List<String> sharedStrings() throws IOException {
        List<String> out = new ArrayList<>();
        Element root = element("xl/sharedStrings.xml");
        if (root != null) {
            for (Element item : children(root, "si")) {
                StringBuilder text = new StringBuilder();
                descendants(item, "t").stream()
                        .filter(t -> !"rPh".equals(((Element) t.getParentNode()).getLocalName()))
                        .forEach(t -> text.append(t.getTextContent()));
                out.add(text.toString());
            }
        }
        return out;
    }

    private List<CellStyle> styles() throws IOException {
        Element root = element("xl/styles.xml");
        List<CellStyle> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        Map<Integer, String> formats = new HashMap<>(BUILTIN_FORMATS);
        Element numFmts = child(root, "numFmts");
        if (numFmts != null) {
            for (Element f : children(numFmts, "numFmt")) {
                formats.put(Integer.parseInt(attr(f, "numFmtId")), attr(f, "formatCode"));
            }
        }
        List<String> fontRgbs = new ArrayList<>();
        Element fonts = child(root, "fonts");
        for (Element font : fonts == null ? List.<Element>of() : children(fonts, "font")) {
            Element color = child(font, "color");
            fontRgbs.add(color == null ? null : attr(color, "rgb"));
        }
        List<String[]> fills = new ArrayList<>();
        Element fillList = child(root, "fills");
        for (Element fill : fillList == null ? List.<Element>of() : children(fillList, "fill")) {
            Element pattern = child(fill, "patternFill");
            Element fg = pattern == null ? null : child(pattern, "fgColor");
            fills.add(new String[] {pattern == null ? null : attr(pattern, "patternType"),
                fg == null ? null : attr(fg, "rgb")});
        }
        Element xfs = child(root, "cellXfs");
        for (Element xf : xfs == null ? List.<Element>of() : children(xfs, "xf")) {
            int format = integer(attr(xf, "numFmtId"));
            int font = integer(attr(xf, "fontId"));
            int fill = integer(attr(xf, "fillId"));
            String[] f = fill < fills.size() ? fills.get(fill) : new String[] {null, null};
            out.add(new CellStyle(formats.getOrDefault(format, "General"),
                    font < fontRgbs.size() ? fontRgbs.get(font) : null, f[0], f[1]));
        }
        return out;
    }

    private static Sheet sheet(String name, String state, Element root, List<String> shared, List<CellStyle> styles) {
        List<Cell> cells = new ArrayList<>();
        List<Integer> hiddenRows = new ArrayList<>();
        List<Integer> hiddenColumns = new ArrayList<>();
        if (root == null) {
            return new Sheet(name, state, cells, hiddenRows, hiddenColumns);
        }
        Element cols = child(root, "cols");
        for (Element col : cols == null ? List.<Element>of() : children(cols, "col")) {
            if (truthy(attr(col, "hidden"))) {
                for (int c = integer(attr(col, "min")); c <= integer(attr(col, "max")); c++) {
                    hiddenColumns.add(c);
                }
            }
        }
        Element data = child(root, "sheetData");
        for (Element row : data == null ? List.<Element>of() : children(data, "row")) {
            int number = integer(attr(row, "r"));
            if (truthy(attr(row, "hidden"))) {
                hiddenRows.add(number);
            }
            for (Element c : children(row, "c")) {
                int style = attr(c, "s") == null ? 0 : integer(attr(c, "s"));
                String value = value(c, shared, style < styles.size() ? styles.get(style).numberFormat() : "General");
                if (value != null) {
                    int[] at = reference(attr(c, "r"));
                    cells.add(new Cell(at[0], at[1], value, style));
                }
            }
        }
        return new Sheet(name, state, cells, hiddenRows, hiddenColumns);
    }

    /** A cell's value as Python's {@code str()} writes what openpyxl reads, or null when empty. */
    private static String value(Element cell, List<String> shared, String format) {
        String type = attr(cell, "t");
        Element v = child(cell, "v");
        if ("inlineStr".equals(type)) {
            Element is = child(cell, "is");
            if (is == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            descendants(is, "t").forEach(t -> text.append(t.getTextContent()));
            return text.toString();
        }
        if (v == null) {
            return null;
        }
        String raw = v.getTextContent();
        if ("s".equals(type)) {
            int index = integer(raw);
            return index < shared.size() ? shared.get(index) : null;
        }
        if ("b".equals(type)) {
            return raw.equals("1") ? "True" : "False";
        }
        if ("str".equals(type) || "e".equals(type)) {
            return raw;
        }
        if (isDate(format)) {
            try {
                return date(Double.parseDouble(raw));
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        try {
            if (raw.contains(".") || raw.contains("E") || raw.contains("e")) {
                return Json.pyFloat(Double.parseDouble(raw));
            }
            return new java.math.BigInteger(raw).toString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static final Pattern DATE_PART = Pattern.compile("[dmyhs]", Pattern.CASE_INSENSITIVE);

    /** openpyxl's test: a format with day, month, year or time letters outside quotes and brackets. */
    private static boolean isDate(String format) {
        if (format == null || format.equals("General") || format.equals("@")) {
            return false;
        }
        String bare = format.replaceAll("\"[^\"]*\"|\\[[^]]*]|\\\\.", "");
        return DATE_PART.matcher(bare).find();
    }

    private static String date(double serial) {
        LocalDateTime base = LocalDateTime.of(1899, 12, 30, 0, 0);
        long millis = Math.round(serial * 86_400_000L);
        LocalDateTime at = base.plusNanos(millis * 1_000_000L);
        return at.format(DateTimeFormatter.ofPattern(at.getNano() == 0 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm:ss.SSSSSS"));
    }

    /** {@code B12} as row 12, column 2. */
    private static int[] reference(String ref) {
        int column = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            column = column * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return new int[] {integer(ref.substring(i)), column};
    }

    private static boolean truthy(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }

    private static int integer(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The text openpyxl's reader gives a sheet: each row's non-empty values joined by tabs, a
     * line a row, in row order.
     */
    public static String sheetText(Sheet sheet) {
        Map<Integer, List<Cell>> rows = new java.util.TreeMap<>();
        for (Cell cell : sheet.cells()) {
            rows.computeIfAbsent(cell.row(), r -> new ArrayList<>()).add(cell);
        }
        List<String> lines = new ArrayList<>();
        rows.forEach((row, cells) -> {
            List<String> values = cells.stream().sorted(java.util.Comparator.comparingInt(Cell::column))
                    .map(Cell::value).filter(v -> !io.github.complydoc4j.text.Py.blank(v)).toList();
            if (!values.isEmpty()) {
                lines.add(String.join("\t", values));
            }
        });
        return String.join("\n", lines);
    }
}
