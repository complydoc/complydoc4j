package io.github.complydoc4j.read;

import io.github.complydoc4j.report.DocumentAudit;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import io.github.complydoc4j.text.Py;

/**
 * Reads a file into pages of text, the way complydoc's own readers do: a PDF a page a page
 * with PDFBox; a Word file as one logical page, its paragraphs and then its tables' cells;
 * an Excel file a sheet a page, its cells tab-separated; a PowerPoint file a slide a page,
 * speaker notes after the slide; an HTML page, an email and a text or Markdown file as one
 * page.
 */
public final class DocumentReader {

    /** The name a report gives this reader, where Python's says {@code complydoc}. */
    public static final String NAME = "complydoc4j";

    /**
     * @param pageCountKnown whether the format has pages of its own (a PDF), rather than
     *     logical pages complydoc4j made up
     */
    public record Read(String format, List<String> pages, boolean pageCountKnown, List<String> warnings) {}

    /** A file complydoc4j has no reader for. */
    public static final class Unsupported extends IOException {
        public Unsupported(String message) {
            super(message);
        }
    }

    private DocumentReader() {}

    public static boolean supported(Path path) {
        return List.of("pdf", "docx", "xlsx", "pptx", "html", "email", "text", "markdown")
                .contains(DocumentAudit.formatOf(path.toString()));
    }

    public static Read read(Path path) throws IOException {
        String format = DocumentAudit.formatOf(path.toString());
        switch (format) {
            case "pdf" -> {
                return pdf(path);
            }
            case "docx" -> {
                try (OfficeXml file = OfficeXml.open(path)) {
                    return new Read(format, List.of(file.docxText()), false, List.of());
                }
            }
            case "xlsx" -> {
                try (OfficeXml file = OfficeXml.open(path)) {
                    List<String> pages = new ArrayList<>();
                    for (OfficeXml.Sheet sheet : file.workbook().sheets()) {
                        pages.add(OfficeXml.sheetText(sheet));
                    }
                    return new Read(format, pages, false, List.of());
                }
            }
            case "pptx" -> {
                return pptx(path);
            }
            case "html" -> {
                return html(path);
            }
            case "email" -> {
                Mail.Read mail = Mail.read(path);
                return new Read("email", List.of(mail.text()), false, mail.warnings());
            }
            case "text", "markdown" -> {
                return text(path, format);
            }
            default -> throw new Unsupported("complydoc4j has no reader for " + format + " files yet");
        }
    }

    private static Read pptx(Path path) throws IOException {
        List<Pptx.Slide> slides = Pptx.read(path);
        List<String> pages = new ArrayList<>();
        List<String> picturesOnly = new ArrayList<>();
        for (Pptx.Slide slide : slides) {
            pages.add(slide.text());
            if (slide.pictures() > 0 && Py.len(Py.strip(slide.text())) < Pptx.FEW_CHARACTERS) {
                picturesOnly.add(String.valueOf(slide.number()));
            }
        }
        List<String> warnings = new ArrayList<>();
        if (slides.isEmpty()) {
            warnings.add("The presentation has no slides.");
        }
        if (!picturesOnly.isEmpty()) {
            warnings.add("Slides " + String.join(", ", picturesOnly)
                    + " hold pictures and little text. Text inside pictures is not read.");
        }
        return new Read("pptx", pages, true, warnings);
    }

    private static Read html(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (Py.blank(new String(data, StandardCharsets.ISO_8859_1))) {
            return new Read("html", List.of(""), false, List.of());
        }
        org.jsoup.nodes.Element root = Html.root(Html.parse(data));
        String text = Html.text(root);
        int scripts = Html.scripts(root);
        List<String> warnings = new ArrayList<>();
        if (scripts > 0 && text.codePoints().filter(Py::isAlnum).count() < Html.FEW_LETTERS) {
            warnings.add("The page has little text and " + scripts + " script" + (scripts == 1 ? "" : "s")
                    + ". Text a script adds in a browser is not in the file, so it was not read.");
        }
        return new Read("html", List.of(text), false, warnings);
    }

    private static Read pdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            List<String> pages = new ArrayList<>();
            for (int n = 1; n <= document.getNumberOfPages(); n++) {
                stripper.setStartPage(n);
                stripper.setEndPage(n);
                pages.add(io.github.complydoc4j.text.Py.rstrip(stripper.getText(document)));
            }
            return new Read("pdf", pages, true, List.of());
        }
    }

    /** UTF-8, or UTF-16 with its mark; failing both, Windows-1252, as Python reads a text file. */
    private static Read text(Path path, String format) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length >= 2 && ((data[0] == (byte) 0xFF && data[1] == (byte) 0xFE)
                || (data[0] == (byte) 0xFE && data[1] == (byte) 0xFF))) {
            return new Read(format, List.of(new String(data, StandardCharsets.UTF_16)), false, List.of());
        }
        for (int i = 0; i < Math.min(4096, data.length); i++) {
            if (data[i] == 0) {
                throw new IOException("the file contains binary data rather than text");
            }
        }
        int start = data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF ? 3 : 0;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, start, data.length - start)).toString();
            return new Read(format, List.of(text), false, List.of());
        } catch (CharacterCodingException e) {
            String text = new String(data, Charset.forName("windows-1252"));
            return new Read(format, List.of(text), false, List.of("The file is not valid UTF-8, so it was read as "
                    + "Windows-1252. Some characters may be wrong."));
        }
    }
}
