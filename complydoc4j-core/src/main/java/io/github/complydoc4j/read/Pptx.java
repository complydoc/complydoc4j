package io.github.complydoc4j.read;

import io.github.complydoc4j.text.Py;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * A PowerPoint file, read from its XML: each slide a page, in presentation order. Table
 * cells are separated by tabs, and speaker notes follow the slide text. Hidden slides are
 * read like the others; the hidden-text check reports them. Text inside pictures is not
 * read. complydoc's {@code ingest/pptx.py}.
 */
public final class Pptx {

    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** Characters below which a slide with pictures is said to hold its text in them. */
    static final int FEW_CHARACTERS = 40;

    /**
     * @param outside whether the shape lies wholly outside the slide
     * @param largestSize the largest font size it sets, in hundredths of a point; null when it sets none
     */
    public record Shape(String text, boolean outside, Double largestSize) {}

    public record Slide(int number, boolean hidden, List<Shape> shapes, List<String> lines, String notes,
            int pictures) {

        /** The slide's text, then its speaker notes after a blank line. */
        public String text() {
            String body = String.join("\n", lines);
            return notes.isEmpty() ? body : body + "\n\n" + notes;
        }
    }

    private Pptx() {}

    public static List<Slide> read(Path path) throws IOException {
        try (OfficeXml file = OfficeXml.open(path)) {
            Element presentation = file.element("ppt/presentation.xml");
            if (presentation == null) {
                throw new IOException("the presentation has no ppt/presentation.xml part");
            }
            Element size = OfficeXml.child(presentation, "sldSz");
            long width = emu(size, "cx");
            long height = emu(size, "cy");
            Map<String, String[]> relationships = relationships(file, "ppt/presentation.xml");
            List<Slide> slides = new ArrayList<>();
            Element list = OfficeXml.child(presentation, "sldIdLst");
            for (Element id : list == null ? List.<Element>of() : OfficeXml.descendants(list, "sldId")) {
                // r:id, not the slide's own numeric id, which has the same local name.
                String[] target = relationships.get(id.getAttributeNS(RELATIONSHIPS, "id"));
                if (target != null && !target[1].isEmpty()) {
                    slides.add(slide(file, target[1], slides.size() + 1, width, height));
                }
            }
            return slides;
        }
    }

    /** A part's relationships, by id: their type and the part they point at. External ones are left out. */
    private static Map<String, String[]> relationships(OfficeXml file, String part) throws IOException {
        int slash = part.lastIndexOf('/');
        String folder = slash < 0 ? "" : part.substring(0, slash);
        Element rels = file.element(folder + "/_rels/" + part.substring(slash + 1) + ".rels");
        Map<String, String[]> found = new HashMap<>();
        if (rels == null) {
            return found;
        }
        for (Element relationship : OfficeXml.descendants(rels, "Relationship")) {
            if ("External".equals(OfficeXml.attr(relationship, "TargetMode"))) {
                continue;
            }
            String target = String.valueOf(OfficeXml.attr(relationship, "Target"));
            String resolved = target.startsWith("/") ? target.substring(1)
                    : java.nio.file.Path.of(folder, target).normalize().toString().replace('\\', '/');
            String type = OfficeXml.attr(relationship, "Type");
            found.put(OfficeXml.attr(relationship, "Id"), new String[] {type == null ? "" : type, resolved});
        }
        return found;
    }

    private static long emu(Element element, String attribute) {
        Long value = emuOrNull(element, attribute);
        return value == null ? 0 : value;
    }

    private static Long emuOrNull(Element element, String attribute) {
        if (element == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(OfficeXml.attr(element, attribute)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Each paragraph's runs joined, a line a paragraph, blank lines left out. */
    private static String paragraphs(Element body) {
        List<String> lines = new ArrayList<>();
        for (Element p : OfficeXml.descendants(body, "p")) {
            StringBuilder line = new StringBuilder();
            OfficeXml.descendants(p, "t").forEach(t -> line.append(t.getTextContent()));
            if (!Py.blank(line.toString())) {
                lines.add(line.toString());
            }
        }
        return String.join("\n", lines);
    }

    /** Where a shape sits, in EMU, unless it is inside a group, whose offsets it is relative to. */
    private static long[] placement(Element node) {
        for (Node up = node.getParentNode(); up instanceof Element e; up = e.getParentNode()) {
            if ("grpSp".equals(e.getLocalName())) {
                return null;
            }
        }
        Element properties = OfficeXml.child(node, "spPr");
        Element xfrm = properties == null ? null : OfficeXml.child(properties, "xfrm");
        if (xfrm == null) {
            return null;
        }
        Element off = OfficeXml.child(xfrm, "off");
        Element ext = OfficeXml.child(xfrm, "ext");
        Long[] values = {emuOrNull(off, "x"), emuOrNull(off, "y"), emuOrNull(ext, "cx"), emuOrNull(ext, "cy")};
        for (Long value : values) {
            if (value == null) {
                return null;
            }
        }
        return new long[] {values[0], values[1], values[2], values[3]};
    }

    /** A table's text: cells joined by tabs, merged cells left out, a line a row. */
    private static String table(Element table) {
        List<String> lines = new ArrayList<>();
        for (Element row : OfficeXml.children(table, "tr")) {
            List<String> cells = new ArrayList<>();
            boolean any = false;
            for (Element cell : OfficeXml.children(row, "tc")) {
                if (List.of("1", "true").contains(String.valueOf(OfficeXml.attr(cell, "hMerge")))
                        || List.of("1", "true").contains(String.valueOf(OfficeXml.attr(cell, "vMerge")))) {
                    continue;
                }
                List<String> texts = OfficeXml.descendants(cell, "t").stream().map(Node::getTextContent).toList();
                String text = Py.strip(String.join(" ", texts));
                any |= !text.isEmpty();
                cells.add(text);
            }
            if (any) {
                lines.add(String.join("\t", cells));
            }
        }
        return String.join("\n", lines);
    }

    private static Slide slide(OfficeXml file, String part, int number, long width, long height) throws IOException {
        Element root = file.element(part);
        if (root == null) {
            throw new IOException("the presentation has no " + part + " part");
        }
        boolean hidden = List.of("0", "false").contains(String.valueOf(OfficeXml.attr(root, "show")));
        List<Shape> shapes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int pictures = 0;
        for (Element node : shapesInOrder(root)) {
            switch (node.getLocalName()) {
                case "sp" -> {
                    Element body = OfficeXml.child(node, "txBody");
                    String text = body == null ? "" : paragraphs(body);
                    if (text.isEmpty()) {
                        continue;
                    }
                    Double largest = null;
                    for (Element properties : OfficeXml.descendants(body, "rPr")) {
                        String size = OfficeXml.attr(properties, "sz");
                        if (size != null && !size.isEmpty() && size.chars().allMatch(Character::isDigit)) {
                            largest = largest == null ? Double.parseDouble(size)
                                    : Math.max(largest, Double.parseDouble(size));
                        }
                    }
                    long[] at = placement(node);
                    boolean outside = at != null && width > 0 && height > 0
                            && (at[0] >= width || at[1] >= height || at[0] + at[2] <= 0 || at[1] + at[3] <= 0);
                    shapes.add(new Shape(text, outside, largest));
                    lines.add(text);
                }
                case "graphicFrame" -> {
                    List<Element> tables = OfficeXml.descendants(node, "tbl");
                    if (!tables.isEmpty()) {
                        String text = table(tables.get(0));
                        if (!text.isEmpty()) {
                            lines.add(text);
                        }
                    }
                }
                default -> pictures += placement(node) != null ? 1 : 0;
            }
        }
        List<String> notes = new ArrayList<>();
        for (String[] target : relationships(file, part).values()) {
            if (!target[0].endsWith("/notesSlide")) {
                continue;
            }
            notes.clear();
            Element notesRoot = file.element(target[1]);
            for (Element shape : notesRoot == null ? List.<Element>of() : OfficeXml.descendants(notesRoot, "sp")) {
                Element nv = OfficeXml.child(shape, "nvSpPr");
                Element nvPr = nv == null ? null : OfficeXml.child(nv, "nvPr");
                Element placeholder = nvPr == null ? null : OfficeXml.child(nvPr, "ph");
                Element body = OfficeXml.child(shape, "txBody");
                if (placeholder != null && "body".equals(OfficeXml.attr(placeholder, "type")) && body != null) {
                    String text = paragraphs(body);
                    if (!text.isEmpty()) {
                        notes.add(text);
                    }
                }
            }
        }
        return new Slide(number, hidden, shapes, lines, String.join("\n", notes), pictures);
    }

    /** Every shape, table frame and picture in the slide, in document order, as lxml's {@code iter} finds them. */
    private static List<Element> shapesInOrder(Element root) {
        List<Element> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Element element, List<Element> found) {
        String name = element.getLocalName();
        if ("http://schemas.openxmlformats.org/presentationml/2006/main".equals(element.getNamespaceURI())
                && ("sp".equals(name) || "graphicFrame".equals(name) || "pic".equals(name))) {
            found.add(element);
        }
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                collect(child, found);
            }
        }
    }
}
