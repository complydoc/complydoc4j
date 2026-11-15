package io.github.complydoc4j.read;

import io.github.complydoc4j.text.Py;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;

/**
 * An email in a {@code .eml} file: the From, To, Cc, Date and Subject headers, then the body,
 * the plain text part when there is one, otherwise the HTML part as text. Attachments are
 * counted and not read. complydoc's {@code ingest/eml.py}, with Python's
 * {@code EmailMessage.get_body} choosing the body part.
 */
public final class Mail {

    private static final List<String> HEADERS = List.of("From", "To", "Cc", "Date", "Subject");
    private static final List<String> ENVELOPE = List.of("Message-ID", "Received", "MIME-Version");

    /** The text a loader gives, whether its body came from HTML, and what it did not read. */
    public record Read(String text, boolean fromHtml, List<String> warnings) {}

    private Mail() {}

    public static MimeMessage message(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), in);
            boolean any = false;
            for (String name : concat(HEADERS, ENVELOPE)) {
                any |= header(message, name) != null;
            }
            if (!any) {
                throw new IOException("the file has no email headers");
            }
            return message;
        } catch (MessagingException e) {
            throw new IOException("the message could not be read (" + e.getMessage() + ")", e);
        }
    }

    public static Read read(Path path) throws IOException {
        MimeMessage message = message(path);
        try {
            List<String> lines = new ArrayList<>();
            for (String name : HEADERS) {
                String value = header(message, name);
                if (value != null && !value.isEmpty()) {
                    lines.add(name + ": " + value);
                }
            }
            Part part = body(message, List.of("plain", "html"));
            String body = "";
            boolean fromHtml = false;
            if (part != null) {
                body = content(part);
                if (type(part).equals("text/html") && !Py.blank(body)) {
                    body = Html.text(Html.root(Html.parse(body)));
                    fromHtml = true;
                }
            }
            String heading = String.join("\n", lines);
            String text = Py.blank(body) ? heading : heading + "\n\n" + Py.strip(body);
            return new Read(text, fromHtml, warnings(message));
        } catch (MessagingException e) {
            throw new IOException("the message could not be read (" + e.getMessage() + ")", e);
        }
    }

    /** The HTML part of the body, or null when there is none. */
    public static String htmlBody(MimeMessage message) throws IOException {
        try {
            Part part = body(message, List.of("html"));
            return part == null ? null : content(part);
        } catch (MessagingException e) {
            throw new IOException("the message could not be read (" + e.getMessage() + ")", e);
        }
    }

    /** A header's value, unfolded and decoded, as Python's email policy gives it; null when absent. */
    private static String header(MimeMessage message, String name) {
        try {
            String[] values = message.getHeader(name);
            if (values == null || values.length == 0) {
                return null;
            }
            return Py.strip(MimeUtility.decodeText(MimeUtility.unfold(values[0])));
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    private static String type(Part part) throws MessagingException {
        try {
            return new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
        } catch (MessagingException | RuntimeException e) {
            return "text/plain";
        }
    }

    private static boolean attachment(Part part) throws MessagingException {
        String disposition = part.getDisposition();
        return disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT);
    }

    /** {@code EmailMessage.get_body}: the best part by the preference list, skipping attachments. */
    static Part body(Part message, List<String> preference) throws MessagingException, IOException {
        Part best = null;
        int bestPriority = preference.size();
        for (Object[] found : candidates(message, preference)) {
            int priority = (int) found[0];
            if (priority < bestPriority) {
                bestPriority = priority;
                best = (Part) found[1];
                if (priority == 0) {
                    break;
                }
            }
        }
        return best;
    }

    private static List<Object[]> candidates(Part part, List<String> preference) throws MessagingException, IOException {
        List<Object[]> found = new ArrayList<>();
        if (attachment(part)) {
            return found;
        }
        String[] kind = type(part).split("/", 2);
        if (kind[0].equals("text")) {
            if (kind.length > 1 && preference.contains(kind[1])) {
                found.add(new Object[] {preference.indexOf(kind[1]), part});
            }
            return found;
        }
        if (!kind[0].equals("multipart") || !(part.getContent() instanceof Multipart multipart)) {
            return found;
        }
        if (!(kind.length > 1 && kind[1].equals("related"))) {
            for (int i = 0; i < multipart.getCount(); i++) {
                found.addAll(candidates(multipart.getBodyPart(i), preference));
            }
            return found;
        }
        if (preference.contains("related")) {
            found.add(new Object[] {preference.indexOf("related"), part});
        }
        BodyPart candidate = null;
        String start = new ContentType(part.getContentType()).getParameter("start");
        if (start != null) {
            for (int i = 0; i < multipart.getCount() && candidate == null; i++) {
                String[] ids = multipart.getBodyPart(i).getHeader("Content-ID");
                if (ids != null && ids.length > 0 && ids[0].equals(start)) {
                    candidate = multipart.getBodyPart(i);
                }
            }
        }
        if (candidate == null && multipart.getCount() > 0) {
            candidate = multipart.getBodyPart(0);
        }
        if (candidate != null) {
            found.addAll(candidates(candidate, preference));
        }
        return found;
    }

    /** A text part's content, decoded; bytes it cannot decode are read as UTF-8. */
    private static String content(Part part) throws MessagingException, IOException {
        Object content;
        try {
            content = part.getContent();
        } catch (IOException | MessagingException | RuntimeException e) {
            content = null;
        }
        if (content instanceof String text) {
            return text;
        }
        try (InputStream in = part.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static List<String> warnings(MimeMessage message) throws MessagingException, IOException {
        List<String> types = new ArrayList<>();
        attachments(message, types);
        if (types.isEmpty()) {
            return List.of();
        }
        String kinds = String.join(", ", new TreeSet<>(types));
        String verb = types.size() == 1 ? "was" : "were";
        return List.of(types.size() + " attachment" + (types.size() == 1 ? "" : "s") + " (" + kinds + ") " + verb
                + " not read. Save attachments and audit them separately.");
    }

    private static void attachments(Part part, List<String> types) throws MessagingException, IOException {
        if (attachment(part)) {
            types.add(type(part));
            return;
        }
        if (type(part).startsWith("multipart/") && part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                attachments(multipart.getBodyPart(i), types);
            }
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
