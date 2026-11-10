package io.github.complydoc4j.hidden;

import io.github.complydoc4j.config.Config;
import io.github.complydoc4j.text.Py;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

/**
 * Which characters in a PDF's text layer a reader would not see.
 *
 * <p>The page is drawn, and each character in the text layer is checked for ink under its
 * box. Where the pixels are uniform, nothing visible is drawn there: the text is in the
 * background colour, under a filled shape, clipped away, or in an invisible render mode.
 * The pixels decide whether text is hidden; the file explains why. Text outside the page's
 * crop box and text drawn below the minimum font size are hidden whatever the pixels show.
 * Text under an opaque image painted after it is suspected, and reported only when it reads
 * as an instruction: a figure painted over its own labels looks the same.
 *
 * <p>The Python package's check, on PDFBox rather than pdfium.
 */
public final class PdfVisibility {

    static final String OUTSIDE = "outside the visible page area";
    static final String BLANK = "nothing is drawn where the text layer has this text";
    static final String COVERED = "covered by an opaque image";

    /** Visible glyphs a hidden passage may contain before it is split in two. */
    private static final int MAX_VISIBLE_GAP = 2;
    /** Height in pixels below which a glyph that looks blank is drawn again, larger. */
    private static final double SMALL_GLYPH_PX = 4;
    private static final int CLOSER_SCALE = 4;
    private static final long CLOSER_MAX_PIXELS = 16_000_000L;
    /** Points a glyph box may pass an image edge and still count as inside it. */
    private static final double COVER_SLOP = 0.6;
    /** Share of the page an image may cover and still be a cover; past it, it is a scan's picture. */
    private static final double SCAN_SHARE = 0.5;

    private PdfVisibility() {}

    /** One character of the text layer, with what was found under it. */
    record Glyph(String ch, boolean hidden, List<String> reasons, boolean confirming, boolean space,
            boolean newline) {

        static Glyph space(boolean newline) {
            return new Glyph(" ", false, List.of(), false, true, newline);
        }

        static Glyph shown(String ch) {
            return new Glyph(ch, false, List.of(), false, false, false);
        }
    }

    public static HiddenRun.Found runs(Path path, Config.Visibility config) {
        PDDocument document;
        try {
            document = Loader.loadPDF(path.toFile());
        } catch (IOException e) {
            return new HiddenRun.Found(List.of(),
                    List.of("the file could not be opened to check for hidden text (" + e.getMessage() + ")"),
                    Set.of(), false);
        }
        List<HiddenRun> runs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<Integer> unchecked = new HashSet<>();
        try (document) {
            int total = document.getNumberOfPages();
            if (total > config.maxPages()) {
                notes.add("pages after " + config.maxPages() + " were not checked for hidden text");
                for (int n = config.maxPages() + 1; n <= total; n++) {
                    unchecked.add(n);
                }
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<Integer> rotated = new ArrayList<>();
            for (int index = 0; index < Math.min(total, config.maxPages()); index++) {
                PDPage page = document.getPage(index);
                if (page.getRotation() % 360 != 0) {
                    rotated.add(index + 1);
                    continue;
                }
                try {
                    runs.addAll(pageRuns(document, renderer, page, index, config));
                } catch (IOException | RuntimeException e) {
                    // One malformed page must not stop the others.
                    notes.add("page " + (index + 1) + " could not be checked for hidden text (" + e.getMessage() + ")");
                    unchecked.add(index + 1);
                }
            }
            if (!rotated.isEmpty()) {
                notes.add("rotated pages were not checked for hidden text: "
                        + String.join(", ", rotated.stream().map(String::valueOf).toList()));
                unchecked.addAll(rotated);
            }
        } catch (IOException e) {
            notes.add("the file could not be read to the end (" + e.getMessage() + ")");
        }
        return new HiddenRun.Found(runs, notes, unchecked, true);
    }

    private static List<HiddenRun> pageRuns(PDDocument document, PDFRenderer renderer, PDPage page, int index,
            Config.Visibility config) throws IOException {
        Stripper stripper = new Stripper();
        stripper.setStartPage(index + 1);
        stripper.setEndPage(index + 1);
        stripper.getText(document);
        if (stripper.glyphs.isEmpty()) {
            return List.of();
        }
        PDRectangle crop = page.getCropBox();
        double width = crop.getWidth();
        double height = crop.getHeight();
        double scale = config.renderDpi() / 72.0;
        BufferedImage image = renderer.renderImageWithDPI(index, config.renderDpi(), ImageType.GRAY);
        Raster raster = image.getRaster();
        double white = config.nearWhite();

        List<Glyph> glyphs = new ArrayList<>();
        Map<Integer, double[]> doubtful = new LinkedHashMap<>();
        for (Stripper.Placed placed : stripper.glyphs) {
            if (placed.position == null) {
                glyphs.add(Glyph.space(placed.newline));
                continue;
            }
            int c = placed.ch.codePointAt(0);
            if (Py.isSpace(c) || !Py.isPrintable(c)) {
                glyphs.add(Glyph.space(placed.ch.equals("\n") || placed.ch.equals("\r")));
                continue;
            }
            TextPosition t = placed.position;
            Stripper.State state = stripper.states.get(t);
            double x0 = t.getXDirAdj();
            double x1 = x0 + Math.max(t.getWidthDirAdj(), 0.1);
            double y1 = t.getYDirAdj();
            double y0 = y1 - Math.max(t.getHeightDir(), 0.1);

            List<String> reasons = new ArrayList<>();
            boolean confirming = false;
            boolean outside = x1 <= 0 || x0 >= width || y1 <= 0 || y0 >= height;
            if (outside) {
                reasons.add(OUTSIDE);
                confirming = true;
            }
            float size = t.getFontSizeInPt();
            boolean tiny = size >= 0 && size < config.minFontSizePt();
            if (tiny) {
                reasons.add("font size below " + pyG(config.minFontSizePt()) + "pt");
                confirming = true;
            }
            List<String> explained = new ArrayList<>();
            boolean invisibleMode = false;
            if (state != null) {
                if (state.mode == RenderingMode.NEITHER) {
                    explained.add("invisible text render mode");
                    invisibleMode = true;
                } else if (state.mode == RenderingMode.NEITHER_CLIP) {
                    explained.add("clip-only text render mode");
                    invisibleMode = true;
                }
                if (state.alpha == 0) {
                    explained.add("fully transparent text");
                } else if (state.rgb != null && Math.min(state.rgb[0], Math.min(state.rgb[1], state.rgb[2])) >= white) {
                    explained.add("white text");
                }
            }
            boolean blank = false;
            boolean covered = false;
            if (!outside) {
                int px0 = Math.max(0, (int) (x0 * scale));
                int px1 = Math.min(raster.getWidth(), (int) Math.ceil(x1 * scale));
                int py0 = Math.max(0, (int) (y0 * scale));
                int py1 = Math.min(raster.getHeight(), (int) Math.ceil(y1 * scale));
                blank = px1 <= px0 || py1 <= py0 || contrast(raster, px0, py0, px1, py1) < config.minContrast();
                if (!invisibleMode && state != null
                        && stripper.underOpaqueImage(state.order, x0, y0, x1, y1, width * height)) {
                    reasons.add(COVERED);
                    covered = true;
                } else if (blank && !tiny) {
                    if (!explained.isEmpty()) {
                        reasons.addAll(explained);
                        confirming = true;
                    } else {
                        reasons.add(BLANK);
                        if ((y1 - y0) * scale < SMALL_GLYPH_PX) {
                            doubtful.put(glyphs.size(), new double[] {x0, y0, x1, y1});
                        }
                    }
                }
            }
            glyphs.add(new Glyph(placed.ch, outside || tiny || blank || covered, reasons, confirming, false, false));
        }
        recheckSmall(renderer, index, glyphs, doubtful, scale, config);
        return fromGlyphs(glyphs, index + 1, config);
    }

    /** Glyphs too small to judge at the page's resolution, drawn again larger before they are called hidden. */
    private static void recheckSmall(PDFRenderer renderer, int index, List<Glyph> glyphs, Map<Integer, double[]> doubtful,
            double scale, Config.Visibility config) {
        if (doubtful.isEmpty()) {
            return;
        }
        try {
            float dpi = (float) (config.renderDpi() * CLOSER_SCALE);
            BufferedImage closer = renderer.renderImageWithDPI(index, dpi, ImageType.GRAY);
            if ((long) closer.getWidth() * closer.getHeight() > CLOSER_MAX_PIXELS) {
                return;
            }
            Raster raster = closer.getRaster();
            double big = scale * CLOSER_SCALE;
            doubtful.forEach((at, box) -> {
                int px0 = Math.max(0, (int) (box[0] * big));
                int px1 = Math.min(raster.getWidth(), (int) Math.ceil(box[2] * big));
                int py0 = Math.max(0, (int) (box[1] * big));
                int py1 = Math.min(raster.getHeight(), (int) Math.ceil(box[3] * big));
                if (px1 > px0 && py1 > py0 && contrast(raster, px0, py0, px1, py1) >= config.minContrast()) {
                    glyphs.set(at, Glyph.shown(glyphs.get(at).ch()));
                }
            });
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // A second drawing that fails leaves the first verdicts standing.
        }
    }

    private static int contrast(Raster raster, int x0, int y0, int x1, int y1) {
        int low = 255;
        int high = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int v = raster.getSample(x, y, 0);
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
        }
        return high - low;
    }

    /** Python's {@code :g}: a number without a trailing {@code .0}. */
    static String pyG(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Whether a run is the end of a visible line that ran past the page's edge. */
    private static boolean overflows(List<Glyph> glyphs, int start, List<String> reasons) {
        if (!reasons.equals(List.of(OUTSIDE))) {
            return false;
        }
        for (int i = start - 1; i >= 0; i--) {
            Glyph glyph = glyphs.get(i);
            if (glyph.newline()) {
                return false;
            }
            if (!glyph.space()) {
                return !glyph.hidden();
            }
        }
        return false;
    }

    /**
     * Consecutive hidden glyphs grouped into passages worth reporting. A passage ends at a line
     * break where the reason for hiding changes, so white text on one line and invisible text
     * on the next are two findings, each with its own reason.
     */
    static List<HiddenRun> fromGlyphs(List<Glyph> glyphs, Integer page, Config.Visibility config) {
        List<HiddenRun> runs = new ArrayList<>();
        int i = 0;
        int n = glyphs.size();
        while (i < n) {
            if (glyphs.get(i).space() || !glyphs.get(i).hidden()) {
                i++;
                continue;
            }
            int start = i;
            int end = i;
            int gap = 0;
            boolean crossed = false;
            for (int j = i; j < n; j++) {
                Glyph glyph = glyphs.get(j);
                if (glyph.space()) {
                    crossed = crossed || glyph.newline();
                    continue;
                }
                if (glyph.hidden()) {
                    if (crossed && !glyph.reasons().equals(glyphs.get(end).reasons())) {
                        break;
                    }
                    end = j;
                    gap = 0;
                    crossed = false;
                } else if (++gap > MAX_VISIBLE_GAP) {
                    break;
                }
            }
            List<Glyph> members = glyphs.subList(start, end + 1);
            List<Glyph> shown = members.stream().filter(g -> !g.space()).toList();
            List<Glyph> hidden = shown.stream().filter(Glyph::hidden).toList();
            long letters = hidden.stream().filter(g -> Py.isAlnum(g.ch().codePointAt(0))).count();
            if (letters >= config.minCharacters() && hidden.size() >= config.minHiddenShare() * shown.size()) {
                Map<String, Integer> tally = new LinkedHashMap<>();
                hidden.forEach(g -> g.reasons().forEach(r -> tally.merge(r, 1, Integer::sum)));
                // Most common first; ties in the order first met, as Counter.most_common orders them.
                List<String> reasons = new ArrayList<>(tally.keySet());
                reasons.sort((a, b) -> tally.get(b) - tally.get(a));
                boolean confirmed = hidden.stream().filter(Glyph::confirming).count() * 2 >= hidden.size();
                StringBuilder text = new StringBuilder();
                members.forEach(g -> text.append(g.ch()));
                runs.add(new HiddenRun(page, Py.collapse(text.toString()), confirmed ? "confirmed" : "suspected",
                        reasons, overflows(glyphs, start, reasons) || reasons.equals(List.of(COVERED))));
            }
            i = end + 1;
        }
        return runs;
    }

    /**
     * The text stripper, kept listening: what was in force when each glyph was shown, and
     * which images were painted when, so a glyph an opaque image was painted over is known.
     */
    private static final class Stripper extends PDFTextStripper {

        record State(RenderingMode mode, float[] rgb, double alpha, int order) {}

        record Drawn(double x0, double y0, double x1, double y1, int order, boolean opaque) {}

        /** A glyph in reading order, or a separator: {@code position} is null for a space or a break. */
        record Placed(String ch, TextPosition position, boolean newline) {}

        final Map<TextPosition, State> states = new IdentityHashMap<>();
        final List<Drawn> images = new ArrayList<>();
        final List<Placed> glyphs = new ArrayList<>();
        private int order;
        private PDRectangle crop;

        Stripper() throws IOException {
            setSortByPosition(true);
            // The text stripper reads no colours of its own; white text is told by its fill.
            addOperator(new SetNonStrokingColorSpace(this));
            addOperator(new SetNonStrokingColor(this));
            addOperator(new SetNonStrokingColorN(this));
            addOperator(new SetNonStrokingDeviceRGBColor(this));
            addOperator(new SetNonStrokingDeviceGrayColor(this));
            addOperator(new SetNonStrokingDeviceCMYKColor(this));
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            crop = page.getCropBox();
            super.startPage(page);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            PDGraphicsState state = getGraphicsState();
            float[] rgb = null;
            try {
                PDColor colour = state.getNonStrokingColor();
                if (colour != null && colour.getColorSpace() != null) {
                    rgb = colour.getColorSpace().toRGB(colour.getComponents());
                }
            } catch (IOException | RuntimeException e) {
                rgb = null;
            }
            states.put(text, new State(state.getTextState().getRenderingMode(), rgb, state.getNonStrokeAlphaConstant(),
                    order++));
            super.processTextPosition(text);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name
                    && getResources() != null) {
                PDXObject object = getResources().getXObject(name);
                if (object instanceof PDImageXObject picture && crop != null) {
                    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                    double[] xs = new double[4];
                    double[] ys = new double[4];
                    int k = 0;
                    for (double[] corner : new double[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                        Point2D.Float p = ctm.transformPoint((float) corner[0], (float) corner[1]);
                        xs[k] = p.x - crop.getLowerLeftX();
                        ys[k] = crop.getUpperRightY() - p.y;
                        k++;
                    }
                    boolean opaque = !picture.isStencil() && picture.getSoftMask() == null
                            && !picture.getCOSObject().containsKey(COSName.MASK)
                            && getGraphicsState().getNonStrokeAlphaConstant() >= 1.0;
                    images.add(new Drawn(min(xs), min(ys), max(xs), max(ys), order++, opaque));
                }
            }
            super.processOperator(operator, operands);
        }

        /** Whether an opaque image painted after the glyph holds the whole of its box. */
        boolean underOpaqueImage(int glyphOrder, double x0, double y0, double x1, double y1, double pageArea) {
            for (Drawn image : images) {
                double area = (image.x1 - image.x0) * (image.y1 - image.y0);
                if (image.order > glyphOrder && image.opaque && area < SCAN_SHARE * pageArea
                        && x0 >= image.x0 - COVER_SLOP && x1 <= image.x1 + COVER_SLOP
                        && y0 >= image.y0 - COVER_SLOP && y1 <= image.y1 + COVER_SLOP) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                unicode.codePoints().forEach(c -> glyphs.add(new Placed(new String(Character.toChars(c)), position,
                        false)));
            }
            super.writeString(text, positions);
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            glyphs.add(new Placed(" ", null, false));
            super.writeWordSeparator();
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            glyphs.add(new Placed("\n", null, true));
            super.writeLineSeparator();
        }

        private static double min(double[] values) {
            double out = values[0];
            for (double v : values) {
                out = Math.min(out, v);
            }
            return out;
        }

        private static double max(double[] values) {
            double out = values[0];
            for (double v : values) {
                out = Math.max(out, v);
            }
            return out;
        }
    }
}
