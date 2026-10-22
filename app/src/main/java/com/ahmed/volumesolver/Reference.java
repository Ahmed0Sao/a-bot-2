package com.ahmed.volumesolver;

import android.content.Context;
import android.content.SharedPreferences;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

/**
 * The optional reference files ("Attach files" in the app). When some are attached, every question is sent together
 * with them and the model is told to answer from them. With no file attached nothing changes.
 * <p>
 * Each file is converted once, when it is attached, and the result is kept in the app's private storage:
 * <ul>
 * <li>PDF and images are stored as they are and sent to Gemini as they are.</li>
 * <li>Text files (any encoding), Word (.docx) and PowerPoint (.pptx) files are turned into plain text.</li>
 * </ul>
 * Attaching a file with the name of one that is already attached replaces it (the newer version). Nothing in here
 * needs Android classes besides Context, so it can be tested on a plain JVM.
 */
final class Reference {

    static final String KIND_TEXT = "text";
    static final String KIND_PDF = "pdf";
    static final String KIND_IMAGE = "image";

    /** Bigger files are refused outright. */
    static final long MAX_FILE_BYTES = 40L * 1024 * 1024;
    /** All PDFs and images together; they are sent inline as base64, which makes them a third bigger. */
    static final int MAX_INLINE_BYTES = 15 * 1024 * 1024;
    /** About 200k tokens of English text in one file. Longer text is cut and the log says so. */
    static final int MAX_TEXT_CHARS = 800000;
    /** All text files together (about 250k tokens of English text). */
    static final int MAX_TOTAL_TEXT_CHARS = 1000000;
    static final int MAX_FILES = 10;
    private static final int MAX_XML_BYTES = 60 * 1024 * 1024;

    private static final Object LOCK = new Object();

    private static final String K_LIST = "ref_files";
    private static final String K_NEXT_ID = "ref_next_id";
    // Used by the version that kept a single file. Read once, to carry that file over.
    private static final String OLD_FILE_NAME = "reference.dat";
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
        final int id;
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

        Info(int id, String name, String kind, String mime, int size, int chars, String note) {
            this.id = id;
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

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("kind", kind).put("mime", mime)
                    .put("size", size).put("chars", chars).put("note", note);
        }

        static Info fromJson(JSONObject o) {
            return new Info(o.optInt("id"), o.optString("name", "attachment"), o.optString("kind", KIND_TEXT),
                    o.optString("mime", ""), o.optInt("size"), o.optInt("chars"), o.optString("note", ""));
        }
    }

    /** One attached file, loaded for a request. */
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

    /** The attached files in the order they were attached; empty if there are none. */
    static List<Info> list(Context c) {
        synchronized (LOCK) {
            return new ArrayList<>(read(c));
        }
    }

    /** "none", the one file, or the names of several. */
    static String describe(Context c) {
        List<Info> files = list(c);
        if (files.isEmpty()) {
            return "none";
        }
        if (files.size() == 1) {
            return files.get(0).describe();
        }
        StringBuilder sb = new StringBuilder(files.size() + " files: ");
        for (int i = 0; i < files.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(files.get(i).name);
        }
        return sb.toString();
    }

    /** The names of the payloads, separated by commas. */
    static String names(List<Payload> payloads) {
        StringBuilder sb = new StringBuilder();
        for (Payload p : payloads) {
            sb.append(sb.length() > 0 ? ", " : "").append(p.info.name);
        }
        return sb.toString();
    }

    /**
     * Loads the attached files for a request. A file whose stored copy is missing or unreadable is left out and
     * described in {@code problems}, so one broken file does not stop the others from being used.
     */
    static List<Payload> load(Context c, List<String> problems) {
        List<Payload> out = new ArrayList<>();
        for (Info info : list(c)) {
            File f = file(c, info.id);
            if (!f.isFile()) {
                problems.add("The reference file \"" + info.name + "\" is marked as attached but its stored copy "
                        + "is missing. Remove it and attach it again.");
                continue;
            }
            try (InputStream in = new FileInputStream(f)) {
                byte[] data = readLimited(in, MAX_FILE_BYTES);
                if (info.kind.equals(KIND_TEXT)) {
                    out.add(new Payload(info, new String(data, StandardCharsets.UTF_8), null));
                } else {
                    out.add(new Payload(info, null, data));
                }
            } catch (IOException e) {
                problems.add("The reference file \"" + info.name + "\" could not be read: " + e.getMessage());
            }
        }
        return out;
    }

    static void remove(Context c, int id) {
        synchronized (LOCK) {
            List<Info> files = read(c);
            Info gone = null;
            for (Info f : files) {
                if (f.id == id) {
                    gone = f;
                }
            }
            if (gone == null) {
                return;
            }
            file(c, id).delete();
            files.remove(gone);
            write(c, files);
            Logs.i(c, "Reference", "Reference file removed: " + gone.name + ". "
                    + (files.isEmpty() ? "No file is attached; questions are answered from the model's own knowledge."
                    : files.size() + (files.size() == 1 ? " file is" : " files are") + " still attached."));
        }
    }

    static void removeAll(Context c) {
        synchronized (LOCK) {
            for (Info f : read(c)) {
                file(c, f.id).delete();
            }
            write(c, new ArrayList<Info>());
            Logs.i(c, "Reference", "All reference files removed; questions are answered from the model's own knowledge.");
        }
    }

    private static File file(Context c, int id) {
        return new File(c.getApplicationContext().getFilesDir(), "reference_" + id + ".dat");
    }

    private static List<Info> read(Context c) {
        SharedPreferences p = Prefs.store(c);
        if (!p.contains(K_LIST)) {
            migrate(c);
        }
        List<Info> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(K_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(Info.fromJson(a.getJSONObject(i)));
            }
        } catch (JSONException damaged) {
            Logs.e(c, "Reference", "The list of attached files is damaged, so no file is attached. Attach them again.",
                    damaged);
            out.clear();
        }
        return out;
    }

    private static void write(Context c, List<Info> files) {
        JSONArray a = new JSONArray();
        try {
            for (Info f : files) {
                a.put(f.toJson());
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Prefs.store(c).edit().putString(K_LIST, a.toString()).apply();
    }

    private static int nextId(Context c) {
        SharedPreferences p = Prefs.store(c);
        int id = Math.max(1, p.getInt(K_NEXT_ID, 1));
        p.edit().putInt(K_NEXT_ID, id + 1).apply();
        return id;
    }

    /** The previous version kept one file in reference.dat. It becomes the first file of the list. */
    private static void migrate(Context c) {
        SharedPreferences p = Prefs.store(c);
        List<Info> files = new ArrayList<>();
        String kind = p.getString(K_KIND, "");
        if (!kind.isEmpty()) {
            int id = nextId(c);
            File old = new File(c.getApplicationContext().getFilesDir(), OLD_FILE_NAME);
            if (old.isFile() && old.renameTo(file(c, id))) {
                files.add(new Info(id, p.getString(K_NAME, "attachment"), kind, p.getString(K_MIME, ""),
                        p.getInt(K_SIZE, 0), p.getInt(K_CHARS, 0), p.getString(K_NOTE, "")));
                Logs.i(c, "Reference", "The reference file from the previous version was carried over: "
                        + files.get(0).describe());
            } else {
                Logs.e(c, "Reference", "The reference file \"" + p.getString(K_NAME, "attachment")
                        + "\" of the previous version could not be carried over. Attach it again.", null);
            }
        }
        p.edit().remove(K_NAME).remove(K_KIND).remove(K_MIME).remove(K_SIZE).remove(K_CHARS).remove(K_NOTE).apply();
        write(c, files);
    }

    // ---------------------------------------------------------------- attaching

    /**
     * Converts and stores a file next to the ones already attached (a file with the same name replaces the old copy).
     * Throws an IOException whose message says what is wrong with the file or why it does not fit.
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

        synchronized (LOCK) {
            List<Info> files = read(c);
            Info replaced = null;
            for (Info f : files) {
                if (f.name.equalsIgnoreCase(shown)) {
                    replaced = f;
                }
            }
            if (replaced == null && files.size() >= MAX_FILES) {
                throw new IOException("There are already " + MAX_FILES + " files attached, which is the most. "
                        + "Remove one first.");
            }
            long binary = kind.equals(KIND_TEXT) ? 0 : store.length;
            long textChars = chars;
            for (Info f : files) {
                if (f == replaced) {
                    continue;
                }
                if (f.kind.equals(KIND_TEXT)) {
                    textChars += f.chars;
                } else {
                    binary += f.size;
                }
            }
            if (binary > MAX_INLINE_BYTES) {
                throw new IOException("The PDFs and images together would be " + readableSize(binary) + ". They are "
                        + "sent with every question, so the limit for all of them is " + readableSize(MAX_INLINE_BYTES)
                        + ". Remove a file first or attach a smaller one.");
            }
            if (textChars > MAX_TOTAL_TEXT_CHARS) {
                throw new IOException("The text files together would have " + String.format(Locale.US, "%,d", textChars)
                        + " characters, and the limit for all of them is " + String.format(Locale.US, "%,d",
                        MAX_TOTAL_TEXT_CHARS) + " (about 250 pages). Remove a file first or attach a shorter one.");
            }

            int id = nextId(c);
            try (FileOutputStream out = new FileOutputStream(file(c, id), false)) {
                out.write(store);
            }
            if (replaced != null) {
                file(c, replaced.id).delete();
                files.remove(replaced);
            }
            Info info = new Info(id, shown, kind, mime, store.length, chars, note);
            files.add(info);
            write(c, files);
            Logs.i(c, "Reference", "Reference file " + (replaced != null ? "replaced" : "attached") + ": "
                    + info.describe() + ". " + files.size() + (files.size() == 1 ? " file is" : " files are")
                    + " now used to answer every question.");
            return info;
        }
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

    /** Result of {@link #checkQuote}. */
    static final class QuoteCheck {
        /** TRUE found, FALSE the model made it up, null cannot be checked. */
        final Boolean found;
        /** The text file the quote was found in ("" if not found or not checked). */
        final String file;

        QuoteCheck(Boolean found, String file) {
            this.found = found;
            this.file = file;
        }
    }

    /**
     * Checks the quote the model gave as evidence against the attached files. The file the model named as its
     * source is tried first, then every other text file. Quotes from a PDF or an image cannot be checked here, so
     * when such files are attached and the quote is in none of the text files, the answer is "cannot tell".
     */
    static QuoteCheck checkQuote(List<Payload> payloads, String quote, String source) {
        if (quote == null || quote.trim().isEmpty()) {
            return new QuoteCheck(null, "");
        }
        boolean hasText = false;
        boolean hasBinary = false;
        boolean unclear = false;
        String wanted = source == null ? "" : source.trim();
        if (!wanted.isEmpty()) {
            for (Payload p : payloads) {
                if (p.info.kind.equals(KIND_TEXT) && p.info.name.equalsIgnoreCase(wanted)) {
                    Boolean r = quoteInText(p.text, quote);
                    if (Boolean.TRUE.equals(r)) {
                        return new QuoteCheck(Boolean.TRUE, p.info.name);
                    }
                }
            }
        }
        for (Payload p : payloads) {
            if (!p.info.kind.equals(KIND_TEXT)) {
                hasBinary = true;
                continue;
            }
            hasText = true;
            Boolean r = quoteInText(p.text, quote);
            if (Boolean.TRUE.equals(r)) {
                return new QuoteCheck(Boolean.TRUE, p.info.name);
            }
            if (r == null) {
                unclear = true;
            }
        }
        if (!hasText || hasBinary || unclear) {
            return new QuoteCheck(null, "");
        }
        return new QuoteCheck(Boolean.FALSE, "");
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
