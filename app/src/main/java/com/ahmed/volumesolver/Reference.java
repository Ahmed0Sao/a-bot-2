package com.ahmed.volumesolver;

import android.content.Context;
import android.content.SharedPreferences;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

/**
 * The optional reference file ("Attach file" in the app). When one is attached, every question is sent together
 * with it and the model is told to answer from it. With no file attached nothing changes.
 * <p>
 * The file is converted once, when it is attached, and the result is kept in the app's private storage:
 * <ul>
 * <li>PDF and images are stored as they are and sent to Gemini as they are.</li>
 * <li>Text files (any encoding), Word (.docx) and PowerPoint (.pptx) files are turned into plain text.</li>
 * </ul>
 * Nothing in here needs Android classes besides Context, so it can be tested on a plain JVM.
 */
final class Reference {

    static final String KIND_TEXT = "text";
    static final String KIND_PDF = "pdf";
    static final String KIND_IMAGE = "image";

    /** Bigger files are refused outright. */
    static final long MAX_FILE_BYTES = 40L * 1024 * 1024;
    /** PDF and images are sent inline as base64, which makes them a third bigger. */
    static final int MAX_INLINE_BYTES = 15 * 1024 * 1024;
    /** About 200k tokens of English text. Longer text is cut and the log says so. */
    static final int MAX_TEXT_CHARS = 800000;
    private static final int MAX_XML_BYTES = 60 * 1024 * 1024;

    private static final String FILE_NAME = "reference.dat";
    private static final String K_NAME = "ref_name";
    private static final String K_KIND = "ref_kind";
    private static final String K_MIME = "ref_mime";
    private static final String K_SIZE = "ref_size";
    private static final String K_CHARS = "ref_chars";
    private static final String K_NOTE = "ref_note";

    private Reference() {
    }

    /** What is attached. */
    static final class Info {
        final String name;
        final String kind;
        /** MIME type used when the file is sent (text is sent as text). */
        final String mime;
        /** Bytes kept in the app's storage. */
        final int size;
        /** Characters of text (text kind only). */
        final int chars;
        /** How the file was read, e.g. "Word document" or "text, UTF-8, cut to the first 800,000 of 1,200,000 characters". */
        final String note;

        Info(String name, String kind, String mime, int size, int chars, String note) {
            this.name = name;
            this.kind = kind;
            this.mime = mime;
            this.size = size;
            this.chars = chars;
            this.note = note;
        }

        String describe() {
            if (kind.equals(KIND_TEXT)) {
                return name + " (" + note + ", " + String.format(Locale.US, "%,d", chars) + " characters)";
            }
            return name + " (" + note + ", " + readableSize(size) + ")";
        }
    }

    /** The attached file, loaded for a request. */
    static final class Payload {
        final Info info;
        /** The text, for the text kind. */
        final String text;
        /** The raw bytes, for PDF and images. */
        final byte[] bytes;

        Payload(Info info, String text, byte[] bytes) {
            this.info = info;
            this.text = text;
            this.bytes = bytes;
        }
    }

    // ---------------------------------------------------------------- stored state

    /** The attached file, or null if there is none. */
    static Info info(Context c) {
        SharedPreferences p = Prefs.store(c);
        String kind = p.getString(K_KIND, "");
        if (kind.isEmpty()) {
            return null;
        }
        return new Info(p.getString(K_NAME, "attachment"), kind, p.getString(K_MIME, ""),
                p.getInt(K_SIZE, 0), p.getInt(K_CHARS, 0), p.getString(K_NOTE, ""));
    }

    static String describe(Context c) {
        Info i = info(c);
        return i == null ? "none" : i.describe();
    }

    /** Loads the attached file for a request. Returns null if nothing is attached. */
    static Payload load(Context c) throws IOException {
        Info info = info(c);
        if (info == null) {
            return null;
        }
        File f = file(c);
        if (!f.isFile()) {
            throw new IOException("The reference file \"" + info.name + "\" is marked as attached but its stored copy "
                    + "is missing. Remove it and attach it again.");
        }
        byte[] data;
        try (InputStream in = new FileInputStream(f)) {
            data = readLimited(in, MAX_FILE_BYTES);
        }
        if (info.kind.equals(KIND_TEXT)) {
            return new Payload(info, new String(data, StandardCharsets.UTF_8), null);
        }
        return new Payload(info, null, data);
    }

    static void remove(Context c) {
        file(c).delete();
        Prefs.store(c).edit().remove(K_NAME).remove(K_KIND).remove(K_MIME).remove(K_SIZE).remove(K_CHARS)
                .remove(K_NOTE).apply();
        Logs.i(c, "Reference", "Reference file removed; questions are answered from the model's own knowledge.");
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    // ---------------------------------------------------------------- attaching

    /**
     * Converts and stores a file. Throws an IOException whose message says what is wrong with the file.
     *
     * @param name     file name as shown by the picker (may be null)
     * @param mimeHint MIME type reported by the picker (may be null; the content decides)
     */
    static Info attach(Context c, String name, String mimeHint, byte[] data) throws IOException {
        if (data.length == 0) {
            throw new IOException("The file is empty.");
        }
        String shown = clean(name);
        String kind;
        String mime;
        byte[] store;
        int chars = 0;
        String note;

        String imageMime = imageMime(data);
        if (isPdf(data)) {
            requireInline(data.length, "PDF");
            kind = KIND_PDF;
            mime = "application/pdf";
            store = data;
            note = "PDF";
        } else if (imageMime != null) {
            requireInline(data.length, "image");
            kind = KIND_IMAGE;
            mime = imageMime;
            store = data;
            note = "image";
        } else {
            Text t = isZip(data) ? officeText(data) : plainText(data, mimeHint);
            String text = t.text;
            note = t.note;
            if (text.length() > MAX_TEXT_CHARS) {
                int cut = MAX_TEXT_CHARS;
                if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                    cut--;
                }
                note += ", cut to the first " + String.format(Locale.US, "%,d", cut) + " of "
                        + String.format(Locale.US, "%,d", text.length()) + " characters";
                text = text.substring(0, cut);
            }
            kind = KIND_TEXT;
            mime = "text/plain";
            store = text.getBytes(StandardCharsets.UTF_8);
            chars = text.length();
        }

        try (FileOutputStream out = new FileOutputStream(file(c), false)) {
            out.write(store);
        }
        Prefs.store(c).edit().putString(K_NAME, shown).putString(K_KIND, kind).putString(K_MIME, mime)
                .putInt(K_SIZE, store.length).putInt(K_CHARS, chars).putString(K_NOTE, note).apply();
        Info info = new Info(shown, kind, mime, store.length, chars, note);
        Logs.i(c, "Reference", "Reference file attached: " + info.describe() + ". Every question is now answered "
                + "from it.");
        return info;
    }

    private static void requireInline(int bytes, String what) throws IOException {
        if (bytes > MAX_INLINE_BYTES) {
            throw new IOException("This " + what + " is " + readableSize(bytes) + ". Files are sent with every question, "
                    + "so the limit is " + readableSize(MAX_INLINE_BYTES) + ". Split it or attach a smaller part.");
        }
    }

    /** Reads at most {@code limit} bytes and refuses anything bigger. */
    static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > limit) {
                throw new IOException("The file is bigger than " + readableSize(limit) + ", which is the most this "
                        + "app reads.");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- file type detection

    private static boolean isPdf(byte[] d) {
        int end = Math.min(d.length - 4, 1024);
        for (int i = 0; i <= end; i++) {
            if (d[i] == '%' && d[i + 1] == 'P' && d[i + 2] == 'D' && d[i + 3] == 'F') {
                return true;
            }
        }
        return false;
    }

    private static String imageMime(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean isZip(byte[] d) {
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    // ---------------------------------------------------------------- text extraction

    private static final class Text {
        final String text;
        final String note;

        Text(String text, String note) throws IOException {
            String t = text.replace("\r\n", "\n").replace('\r', '\n').replace("\0", "").trim();
            if (t.isEmpty()) {
                throw new IOException("No text was found in the file.");
            }
            this.text = t;
            this.note = note;
        }
    }

    private static Text plainText(byte[] b, String mimeHint) throws IOException {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new Text(new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE), "text, UTF-16");
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new Text(new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE), "text, UTF-16");
        }
        int off = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        for (int i = off; i < Math.min(b.length, 8000); i++) {
            if (b[i] == 0) {
                throw new IOException("This is not a text file, PDF, image, Word (.docx) or PowerPoint (.pptx) "
                        + "file" + (mimeHint == null || mimeHint.isEmpty() ? "" : " (the picker says " + mimeHint + ")")
                        + ". Export it as PDF or text and attach that.");
            }
        }
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
            return new Text(s, "text, UTF-8");
        } catch (CharacterCodingException notUtf8) {
            Charset fallback;
            String label;
            try {
                fallback = Charset.forName("windows-1256"); // Arabic, the usual non-UTF-8 encoding here
                label = "text, windows-1256 (the file is not valid UTF-8)";
            } catch (RuntimeException unavailable) {
                fallback = StandardCharsets.ISO_8859_1;
                label = "text, ISO-8859-1 (the file is not valid UTF-8)";
            }
            return new Text(new String(b, off, b.length - off, fallback), label);
        }
    }

    /** Word and PowerPoint files are ZIP archives of XML. Only the text is taken. */
    private static Text officeText(byte[] zip) throws IOException {
        byte[] document = null;
        TreeMap<Integer, byte[]> slides = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("word/document.xml")) {
                    document = readLimited(in, MAX_XML_BYTES);
                } else if (n.startsWith("ppt/slides/slide") && n.endsWith(".xml")) {
                    String number = n.substring("ppt/slides/slide".length(), n.length() - ".xml".length());
                    try {
                        slides.put(Integer.parseInt(number), readLimited(in, MAX_XML_BYTES));
                    } catch (NumberFormatException ignored) {
                        // not a numbered slide
                    }
                }
            }
        }
        if (document != null) {
            return new Text(xmlText(document), "Word document");
        }
        if (!slides.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<Integer, byte[]> slide : slides.entrySet()) {
                sb.append("--- Slide ").append(slide.getKey()).append(" ---\n").append(xmlText(slide.getValue()))
                        .append('\n');
            }
            return new Text(sb.toString(), "PowerPoint, " + slides.size() + (slides.size() == 1 ? " slide" : " slides"));
        }
        throw new IOException("This ZIP-based file is not a Word (.docx) or PowerPoint (.pptx) document. "
                + "Export it as PDF or text and attach that.");
    }

    /** Collects the text of w:t / a:t elements (Word / PowerPoint) with a line break after every paragraph. */
    private static String xmlText(byte[] xml) throws IOException {
        final StringBuilder out = new StringBuilder();
        DefaultHandler handler = new DefaultHandler() {
            private boolean inText;

            /** The element name without prefix, whether or not the parser reports namespaces. */
            private String local(String localName, String qName) {
                if (localName != null && !localName.isEmpty()) {
                    return localName;
                }
                return qName.substring(qName.indexOf(':') + 1);
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                String n = local(localName, qName);
                if (n.equals("t")) {
                    inText = true;
                } else if (n.equals("tab")) {
                    out.append('\t');
                } else if (n.equals("br") || n.equals("cr")) {
                    out.append('\n');
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                String n = local(localName, qName);
                if (n.equals("t")) {
                    inText = false;
                } else if (n.equals("p")) {
                    out.append('\n');
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inText) {
                    out.append(ch, start, length);
                }
            }
        };
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            // Office files never need a DTD or external entities. Parsers that do not know a feature just ignore it.
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.newSAXParser().parse(new InputSource(new ByteArrayInputStream(xml)), handler);
        } catch (Exception e) {
            throw new IOException("The document's XML could not be read: " + e, e);
        }
        return out.toString();
    }

    private static void setFeature(SAXParserFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // feature not supported by this parser
        }
    }

    // ---------------------------------------------------------------- checking the model's evidence

    /**
     * Whether a quote the model gave as evidence really occurs in the text. Punctuation, case, accents and spacing
     * are ignored. "..." between fragments is allowed. Null = cannot tell (no quote, or not a text file).
     */
    static Boolean quoteInText(String documentText, String quote) {
        if (documentText == null || quote == null) {
            return null;
        }
        String doc = normalize(documentText);
        boolean checked = false;
        for (String part : quote.split("\\.\\.\\.|…")) {
            String p = normalize(part);
            if (p.length() < 3) {
                continue;
            }
            checked = true;
            if (!doc.contains(p)) {
                return Boolean.FALSE;
            }
        }
        return checked ? Boolean.TRUE : null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{M}ـ]+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    // ---------------------------------------------------------------- small helpers

    private static String clean(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}\"]+", " ").trim();
        if (n.isEmpty()) {
            return "attachment";
        }
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    static String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
