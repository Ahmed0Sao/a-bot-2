package com.ahmed.volumesolver;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Gemini REST client.
 * <p>
 * Models: the main model is tried first. When it has used up its quota (or is overloaded, or does not exist for
 * this key) the next model of the fallback list is tried, and so on. Free-tier quotas are counted per model, so
 * every extra model adds its own daily allowance. A model whose daily quota is used up is skipped until the quota
 * resets (midnight Pacific time) so the next key press does not waste a request on it.
 * <p>
 * Routes (per model):
 * <ul>
 * <li>"interactions": POST /v1beta/interactions, Google's current API. Its key documentation uses this route.</li>
 * <li>"generateContent": POST /v1beta/models/{model}:generateContent, the older route that is still supported.</li>
 * </ul>
 * The new "AQ." keys are only documented for the first route and older keys are known to work with the second.
 * So the likelier route goes first, a rejection (400/401/403/404 ...) falls back to the other one, and the
 * route that worked is remembered. Every attempt is written to the in-app log.
 * <p>
 * Speed: the models are not asked one after the other with a timeout each, because a request that is given up has
 * already done most of its work. Instead the first model is asked, and when it has not answered after the
 * "ask the next model after" time (a little longer for very large requests) the next model is asked as well. The
 * first answer wins and the other request is cancelled. A model that lost this way because it was slow is skipped
 * for a while, so the next key press goes straight to the model that is fast right now. At most two requests are in
 * flight at once, and none is started once the time allowed for one key press is used up. The log shows where the
 * time went: connect, send and wait for the model, and download, plus the tokens the model used (thinking tokens
 * are what makes a model slow).
 */
final class GeminiClient {

    static final class Answer {
        boolean found;
        String text = "";
        String raw = "";
        /** The model that produced the answer (can be a fallback). */
        String model = "";
        /** Names of the reference files that were sent with the question, separated by commas; "" if none. */
        String reference = "";
        /** The passage of the reference files the model says supports its answer. */
        String evidence = "";
        /**
         * The file the quote was found in, or else the file the model named as its source ("" if neither). Only
         * the first of the two is certain.
         */
        String evidenceSource = "";
        /**
         * Whether that passage really occurs in the attached text files: null when it cannot be checked (the
         * quote is empty, or PDFs and images are attached that the app cannot search).
         */
        Boolean evidenceInReference;
        double yMin;
        double xMin;
        double yMax;
        double xMax;
    }

    /** Text produced by one model. */
    static final class Reply {
        final String text;
        final String model;
        /** Models that were tried or skipped before this one, one line each; empty if the first model answered. */
        final String notes;

        Reply(String text, String model, String notes) {
            this.text = text;
            this.model = model;
            this.notes = notes;
        }
    }

    /** One piece of the request content, in the order it is sent. */
    private static final class Piece {
        static final String TEXT = "text";
        static final String IMAGE = "image";
        static final String DOCUMENT = "document";

        final String type;
        /** MIME type of image and document pieces. */
        final String mime;
        /** The text, or the base64 data of an image or document. */
        final String data;

        private Piece(String type, String mime, String data) {
            this.type = type;
            this.mime = mime;
            this.data = data;
        }

        static Piece text(String text) {
            return new Piece(TEXT, null, text);
        }

        static Piece image(String mime, byte[] bytes) {
            return new Piece(IMAGE, mime, Base64.encodeToString(bytes, Base64.NO_WRAP));
        }

        static Piece document(String mime, byte[] bytes) {
            return new Piece(DOCUMENT, mime, Base64.encodeToString(bytes, Base64.NO_WRAP));
        }
    }

    /** HTTP error from the API. Keeps the status code and body so the caller can decide what to try next. */
    private static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        final int code;
        final String body;

        ApiException(int code, String message, String body) {
            super(message);
            this.code = code;
            this.body = body;
        }
    }

    /**
     * The model did not reply in the time that was left for this key press. The connection itself was fine, the
     * answer just did not come, so this is not an HTTP error and trying the other route would not help.
     */
    private static final class SlowResponseException extends IOException {
        private static final long serialVersionUID = 1L;
        final long waitedMs;

        SlowResponseException(String message, long waitedMs) {
            super(message);
            this.waitedMs = waitedMs;
        }
    }

    /** The request was cancelled by this app because another model had already answered. */
    private static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        CancelledException(String model) {
            super("The request to " + model + " was cancelled.");
        }
    }

    /** The time and the cancel switch of one request to one model. */
    private static final class Attempt {
        /** {@link SystemClock#elapsedRealtime()} value at which the request is given up. */
        final long deadline;
        final long limitMs;
        /** The same model is asked a second time after a server error only when no other model is left. */
        final boolean retryServerErrors;
        volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        Attempt(long deadline, long limitMs, boolean retryServerErrors) {
            this.deadline = deadline;
            this.limitMs = limitMs;
            this.retryServerErrors = retryServerErrors;
        }

        /** Called with each connection the request opens, so that {@link #cancel()} can reach it. */
        void track(HttpURLConnection c) {
            connection = c;
            if (cancelled) {
                c.disconnect(); // cancelled between the checks: the request ends with an IOException right away
            }
        }

        /** Abandons the request: the thread blocked in it gets an IOException and ends. Safe to call twice. */
        void cancel() {
            cancelled = true;
            HttpURLConnection c = connection;
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** One request to one model, running in its own thread so that two models can be asked at once. */
    private static final class Racer implements Runnable {
        final Context ctx;
        final String apiKey;
        final String model;
        final List<Piece> pieces;
        final boolean json;
        final Attempt attempt;
        final BlockingQueue<Racer> done;
        /** Position of the model in the list the user configured (0 = the main model). */
        final int position;
        final long startedAt = SystemClock.elapsedRealtime();
        /** 0 while the request is running. Set by the thread just before it reports. */
        volatile long finishedAt;
        /** Set by the thread before it reports; read by the caller after it took this racer from the queue. */
        String text;
        Throwable error;

        Racer(Context ctx, String apiKey, String model, int position, List<Piece> pieces, boolean json,
              Attempt attempt, BlockingQueue<Racer> done) {
            this.ctx = ctx;
            this.apiKey = apiKey;
            this.model = model;
            this.position = position;
            this.pieces = pieces;
            this.json = json;
            this.attempt = attempt;
            this.done = done;
        }

        void start() {
            Thread t = new Thread(this, "gemini-" + model);
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            try {
                text = generateWith(ctx, apiKey, model, pieces, json, attempt);
            } catch (Throwable t) {
                error = t;
            } finally {
                finishedAt = SystemClock.elapsedRealtime();
                done.add(this);
            }
        }
    }

    static final String ROUTE_INTERACTIONS = "interactions";
    static final String ROUTE_GENERATE = "generateContent";

    private static final String TAG = "Gemini";
    private static final String API_ROOT = "https://generativelanguage.googleapis.com/v1beta/";
    private static final int MAX_LOGGED_BODY = 8000;
    private static final long MODEL_MISSING_SKIP_MS = 30 * 60 * 1000L;
    private static final long UNKNOWN_RATE_LIMIT_SKIP_MS = 60 * 1000L;
    private static final long MAX_RATE_LIMIT_SKIP_MS = 120 * 1000L;
    /** A model that lost a race because it was slow is not asked first again for this long. */
    private static final long SLOW_MODEL_SKIP_MS = 30 * 60 * 1000L;
    /** A model that answered with a server error (overloaded ...) is left alone for this long. */
    private static final long SERVER_ERROR_SKIP_MS = 2 * 60 * 1000L;

    private static final long CONNECT_TIMEOUT_MS = 15000;
    /** No new model is started when less than this is left of the time for one key press. */
    private static final long MIN_ATTEMPT_MS = 3000;
    private static final long MIN_HTTP_MS = 1000;
    /** Requests up to this size (a screenshot) wait the normal time; every 100 KB beyond adds one second. */
    private static final long BONUS_FREE_CHARS = 600000;
    private static final long MAX_BONUS_MS = 40000;
    private static final long MAX_BUDGET_MS = 140000;
    /** Two requests at most are in flight at once. */
    private static final int MAX_IN_FLIGHT = 2;
    /** Bigger requests (large PDFs or images) are not sent to two models at once: every copy takes memory. */
    static long raceMaxChars = 4000000;
    /** The whole key press may spend at least this long on models (changed by the tests). */
    static long budgetFloorMs = 75000;
    /** When above zero, replaces the "ask the next model after" setting (changed by the tests). */
    static long staggerOverrideMs = 0;

    /** "retryDelay": "49s" in the error details, or "Please retry in 49.0s" in the message. */
    private static final Pattern RETRY_DELAY =
            Pattern.compile("retry(?:delay\"\\s*:\\s*\"|\\s+in\\s+)(\\d+(?:\\.\\d+)?)s", Pattern.CASE_INSENSITIVE);

    private static final String PROMPT =
            "This is a screenshot of a phone that shows a question with several selectable answer options. "
            + "Decide which single option is correct. "
            + "Reply with JSON only (no other text) using exactly these keys: "
            + "\"found\": true if a question with selectable options is visible, otherwise false; "
            + "\"answer\": the exact text of the correct option, or only its letter or number if that is all the screen shows; "
            + "\"box_2d\": [ymin, xmin, ymax, xmax], the tappable area of that option (the whole row or button, "
            + "not just its letter or number), as integers from 0 to 1000 relative to the image height and width. "
            + "If you are unsure, pick the most likely option. "
            + "If no question is visible, reply {\"found\": false, \"answer\": \"\", \"box_2d\": [0, 0, 0, 0]}.";

    private GeminiClient() {
    }

    static Answer solve(Context ctx, String apiKey, String model, byte[] jpeg) throws IOException, JSONException {
        // The optional reference files go first, then the screenshot, then the instructions.
        List<String> problems = new ArrayList<>();
        List<Reference.Payload> refs = Reference.load(ctx, problems);
        for (String problem : problems) {
            Logs.e(ctx, TAG, problem + " This question is sent without that file.", null);
        }
        List<Piece> pieces = new ArrayList<>();
        if (!refs.isEmpty()) {
            pieces.addAll(referencePieces(refs));
            StringBuilder sent = new StringBuilder();
            for (Reference.Payload p : refs) {
                sent.append("\n- ").append(p.info.describe());
            }
            Logs.i(ctx, TAG, "Sending " + refs.size() + (refs.size() == 1 ? " reference file" : " reference files")
                    + " with the question:" + sent);
        }
        pieces.add(Piece.image("image/jpeg", jpeg));
        pieces.add(Piece.text(refs.isEmpty() ? PROMPT : referencePrompt(refs)));

        Reply r = generate(ctx, apiKey, model, pieces, true, false);
        String reply = r.text;
        Logs.i(ctx, TAG, "Raw reply from " + r.model + ": " + reply);

        JSONObject json = parseReply(ctx, r.model, reply);

        Answer a = new Answer();
        a.raw = reply;
        a.model = r.model;
        a.found = json.optBoolean("found", true);
        a.text = json.optString("answer", "");
        if (!refs.isEmpty()) {
            a.reference = Reference.names(refs);
            a.evidence = json.optString("evidence", "").trim();
            String named = json.optString("source", "").trim();
            Reference.QuoteCheck check = Reference.checkQuote(refs, a.evidence, named);
            a.evidenceInReference = check.found;
            a.evidenceSource = check.file.isEmpty() ? named : check.file;
        }
        JSONArray box = json.optJSONArray("box_2d");
        if (box != null && box.length() >= 4) {
            a.yMin = box.optDouble(0, 0);
            a.xMin = box.optDouble(1, 0);
            a.yMax = box.optDouble(2, 0);
            a.xMax = box.optDouble(3, 0);
        }
        if (a.found && !(a.xMax > a.xMin && a.yMax > a.yMin)) {
            throw new IOException("Reply from " + r.model + " has no usable box_2d: " + reply);
        }
        return a;
    }

    /** The model's reply as JSON. Takes the part between the first { and the last }. */
    private static JSONObject parseReply(Context ctx, String model, String reply) throws IOException, JSONException {
        String trimmed = reply.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("Reply from " + model + " is not JSON: " + reply);
        }
        String body = trimmed.substring(start, end + 1);
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            // Small models sometimes put unescaped quotes into the quoted evidence. The answer and the box are what
            // matters, so take them out of the text directly.
            JSONObject rescued = rescue(body);
            if (rescued == null) {
                throw new IOException("Reply from " + model + " is not valid JSON (" + e.getMessage() + "): " + reply);
            }
            Logs.i(ctx, TAG, "The reply from " + model + " is not valid JSON (" + e.getMessage()
                    + "); its answer and box were taken from the text anyway.");
            return rescued;
        }
    }

    private static final Pattern FOUND = Pattern.compile("\"found\"\\s*:\\s*(true|false)");
    private static final Pattern ANSWER = Pattern.compile("\"answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);
    private static final Pattern SOURCE = Pattern.compile("\"source\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);
    private static final Pattern EVIDENCE = Pattern.compile("\"evidence\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"answer\"",
            Pattern.DOTALL);
    private static final Pattern BOX = Pattern.compile("\"box_2d\"\\s*:\\s*\\[\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,"
            + "\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*\\]");

    /** Null if the text has no usable answer and box. */
    private static JSONObject rescue(String body) throws JSONException {
        Matcher f = FOUND.matcher(body);
        boolean found = !f.find() || f.group(1).equals("true");
        JSONObject out = new JSONObject().put("found", found);
        Matcher a = ANSWER.matcher(body);
        boolean hasAnswer = a.find();
        if (hasAnswer) {
            out.put("answer", unescape(a.group(1)));
        }
        Matcher s = SOURCE.matcher(body);
        if (s.find()) {
            out.put("source", unescape(s.group(1)));
        }
        Matcher e = EVIDENCE.matcher(body);
        if (e.find()) {
            out.put("evidence", unescape(e.group(1)));
        }
        Matcher b = BOX.matcher(body);
        boolean hasBox = b.find();
        if (hasBox) {
            JSONArray box = new JSONArray();
            try {
                for (int i = 1; i <= 4; i++) {
                    box.put(Double.valueOf(Double.parseDouble(b.group(i))));
                }
            } catch (NumberFormatException bad) {
                hasBox = false;
            }
            out.put("box_2d", box);
        }
        return !found || (hasAnswer && hasBox) ? out : null;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) {
                sb.append(ch);
                continue;
            }
            char next = s.charAt(++i);
            if (next == 'n') {
                sb.append('\n');
            } else if (next == 't') {
                sb.append('\t');
            } else if (next == 'r') {
                sb.append('\r');
            } else if (next == 'u' && isHex(s, i + 1)) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                sb.append(next);
            }
        }
        return sb.toString();
    }

    private static boolean isHex(String s, int from) {
        for (int i = from; i < from + 4; i++) {
            if (i >= s.length() || Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tiny text-only request used by the "Test API key" button. It ignores the "out of quota" memory so the test
     * always shows the current state, and it refreshes that memory.
     */
    static Reply ping(Context ctx, String apiKey, String model) throws IOException, JSONException {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(Piece.text("Reply with the single word OK."));
        Reply r = generate(ctx, apiKey, model, pieces, false, true);
        return new Reply(r.text.trim(), r.model, r.notes);
    }

    /**
     * Used by the file check button when reference files are attached: sends all of them with a tiny question to
     * prove that Google accepts them and that the model can read each one.
     */
    static Reply checkReference(Context ctx, String apiKey, String model) throws IOException, JSONException {
        List<String> problems = new ArrayList<>();
        List<Reference.Payload> refs = Reference.load(ctx, problems);
        if (refs.isEmpty()) {
            throw new IOException(problems.isEmpty() ? "No reference file is attached." : join(problems));
        }
        for (String problem : problems) {
            Logs.e(ctx, TAG, problem + " It is left out of this check.", null);
        }
        List<Piece> pieces = new ArrayList<>(referencePieces(refs));
        pieces.add(Piece.text("Quote the first sentence of each reference document word for word (at most 20 words "
                + "each). Write one line per document, in the order the documents were given, in the form: "
                + "<document name>: \"<quote>\". Write nothing else."));
        Reply r = generate(ctx, apiKey, model, pieces, false, true);
        return new Reply(r.text.trim(), r.model, r.notes);
    }

    /**
     * The reference files in the order they are sent. Every file starts with a line that names it, so that the model
     * can say which one it quoted; PDFs and images cannot carry a name themselves.
     */
    private static List<Piece> referencePieces(List<Reference.Payload> refs) {
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            Reference.Payload ref = refs.get(i);
            String title = "REFERENCE DOCUMENT " + (i + 1) + " of " + refs.size() + ", named \"" + ref.info.name + "\"";
            if (ref.info.kind.equals(Reference.KIND_TEXT)) {
                pieces.add(Piece.text(title + " (a trusted source for the answer):\n" + ref.text
                        + "\n--- END OF " + title + " ---"));
            } else {
                boolean pdf = ref.info.kind.equals(Reference.KIND_PDF);
                pieces.add(Piece.text(title + " (a trusted source for the answer). It is the " + (pdf ? "PDF" : "image")
                        + " that follows this line:"));
                pieces.add(pdf ? Piece.document(ref.info.mime, ref.bytes) : Piece.image(ref.info.mime, ref.bytes));
            }
        }
        return pieces;
    }

    private static String referencePrompt(List<Reference.Payload> refs) {
        boolean many = refs.size() > 1;
        return "This is a screenshot of a phone that shows a question with several selectable answer options. "
                + (many
                ? refs.size() + " reference documents (" + Reference.names(refs) + ") were provided before the "
                + "screenshot. They are the trusted sources: answer from them, not from memory. Find the passage of "
                + "the documents that answers the question (if they disagree, use the one that answers it most "
                + "directly), then pick the option that passage supports. "
                : "A reference document (" + refs.get(0).info.name + ") was provided before the screenshot. It is the "
                + "trusted source: answer from it, not from memory. Find the passage of the document that answers the "
                + "question, then pick the option that passage supports. ")
                + "Reply with JSON only (no other text) using exactly these keys, in this order: "
                + "\"found\": true if a question with selectable options is visible, otherwise false; "
                + (many ? "\"source\": the exact name of the reference document the passage comes from, or an empty "
                + "string if none of them covers the question; " : "")
                + "\"evidence\": a short quote (at most 200 characters) copied word for word from the reference "
                + (many ? "documents" : "document") + " that answers the question, or an empty string if "
                + (many ? "they do" : "the document does") + " not cover the question; "
                + "\"answer\": the exact text of the correct option, or only its letter or number if that is all the "
                + "screen shows; "
                + "\"box_2d\": [ymin, xmin, ymax, xmax], the tappable area of that option (the whole row or button, "
                + "not just its letter or number), as integers from 0 to 1000 relative to the image height and width. "
                + "Only if the " + (many ? "documents do" : "document does") + " not cover the question, use your own "
                + "knowledge and leave evidence empty. "
                + "If you are unsure, pick the most likely option. "
                + "If no question is visible, reply {\"found\": false, " + (many ? "\"source\": \"\", " : "")
                + "\"evidence\": \"\", \"answer\": \"\", \"box_2d\": [0, 0, 0, 0]}.";
    }

    /** The main model first, then the fallbacks, without duplicates. Fallbacks may be separated by , ; space or newline. */
    static List<String> modelChain(String primary, String fallbacks) {
        List<String> chain = new ArrayList<>();
        addModel(chain, primary);
        for (String m : fallbacks.split("[,;\\s]+")) {
            addModel(chain, m);
        }
        return chain;
    }

    private static void addModel(List<String> chain, String raw) {
        String m = raw.trim();
        if (m.startsWith("models/")) {
            m = m.substring("models/".length());
        }
        if (!m.isEmpty() && !chain.contains(m)) {
            chain.add(m);
        }
    }

    // ---------------------------------------------------------------- model selection

    private static Reply generate(Context ctx, String apiKey, String primary, List<Piece> pieces, boolean json,
                                  boolean ignoreSkips) throws IOException, JSONException {
        List<String> chain = modelChain(primary, Prefs.fallbacks(ctx));
        long now = System.currentTimeMillis();

        // Models that ran out of quota are skipped until it resets, unless that would leave nothing to try.
        boolean useSkips = !ignoreSkips;
        if (useSkips) {
            boolean anyUsable = false;
            for (String m : chain) {
                if (Prefs.skipUntil(ctx, m) <= now) {
                    anyUsable = true;
                }
            }
            if (!anyUsable) {
                Logs.i(ctx, TAG, "Every model in the list is marked as unavailable right now; trying them again.");
                useSkips = false;
            }
        }

        List<String> notes = new ArrayList<>();
        List<Integer> order = new ArrayList<>(); // positions in the list of the models that are going to be asked
        for (int i = 0; i < chain.size(); i++) {
            String m = chain.get(i);
            long until = Prefs.skipUntil(ctx, m);
            if (useSkips && until > now) {
                String reason = Prefs.skipReason(ctx, m);
                Logs.i(ctx, TAG, "Skipping " + m + " until " + stamp(until) + " ("
                        + (reason.isEmpty() ? "an earlier error marked it as unavailable" : reason) + ").");
                notes.add(m + ": skipped until " + stamp(until));
            } else {
                order.add(i);
            }
        }

        long chars = payloadChars(pieces);
        long bonusMs = sizeBonusMs(chars);
        long staggerMs = (staggerOverrideMs > 0 ? staggerOverrideMs : Prefs.waitSeconds(ctx) * 1000L) + bonusMs;
        long budgetMs = Math.min(MAX_BUDGET_MS, budgetFloorMs + bonusMs);
        int maxInFlight = chars > raceMaxChars ? 1 : MAX_IN_FLIGHT;
        if (maxInFlight == 1 && order.size() > 1) {
            Logs.i(ctx, TAG, "The request is " + sizeText(chars) + ", too big to send to two models at once; the "
                    + "models are asked one after the other.");
        }
        long started = SystemClock.elapsedRealtime();
        long end = started + budgetMs;

        BlockingQueue<Racer> done = new LinkedBlockingQueue<>();
        List<Racer> running = new ArrayList<>();
        int next = 0; // position in `order` of the model to ask next
        int asked = 0;
        long newestStart = started;
        String lastDetail = "";
        try {
            while (true) {
                long t = SystemClock.elapsedRealtime();

                // The next model is asked when nothing is running, or when the newest request has waited too long.
                if (next < order.size() && running.size() < maxInFlight
                        && (running.isEmpty() || t - newestStart >= staggerMs)) {
                    if (asked > 0 && end - t < MIN_ATTEMPT_MS) {
                        String used = "the " + Math.round(budgetMs / 1000.0) + " s allowed for one key press are "
                                + "used up";
                        for (; next < order.size(); next++) {
                            String m = chain.get(order.get(next));
                            Logs.i(ctx, TAG, "Not asking " + m + ": " + used + ".");
                            notes.add(m + ": not asked, " + used);
                        }
                    } else {
                        int position = order.get(next++);
                        String m = chain.get(position);
                        if (!running.isEmpty()) {
                            Racer waiting = running.get(0);
                            Logs.i(ctx, TAG, waiting.model + " has not answered after "
                                    + secondsText(t - waiting.startedAt) + "; also asking " + m + ". The first answer "
                                    + "is used and the other request is cancelled.");
                        }
                        Attempt attempt = new Attempt(end, end - t, next == order.size());
                        Racer racer = new Racer(ctx, apiKey, m, position, pieces, json, attempt, done);
                        running.add(racer);
                        asked++;
                        newestStart = t;
                        racer.start();
                        continue;
                    }
                }
                if (running.isEmpty()) {
                    break; // nothing is running and nothing is left to ask
                }

                // Wait for an answer, but only until the next model is due or the time is used up.
                long waitUntil = end;
                if (next < order.size() && running.size() < maxInFlight) {
                    waitUntil = Math.min(end, newestStart + staggerMs);
                }
                long waitMs = waitUntil - SystemClock.elapsedRealtime();
                Racer r;
                try {
                    r = waitMs > 0 ? done.poll(waitMs, TimeUnit.MILLISECONDS) : done.poll();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for the model.");
                }

                if (r == null) {
                    if (SystemClock.elapsedRealtime() < end) {
                        continue; // the next model is due
                    }
                    long stopped = SystemClock.elapsedRealtime();
                    for (Racer waiting : running) {
                        waiting.attempt.cancel();
                        String why = "no answer after " + secondsText(stopped - waiting.startedAt)
                                + ", the request was cancelled";
                        Logs.i(ctx, TAG, waiting.model + " cannot answer: " + why + ".");
                        notes.add(waiting.model + ": " + why);
                    }
                    running.clear();
                    lastDetail = "No model answered within the " + Math.round(budgetMs / 1000.0) + " s allowed for "
                            + "one key press.";
                    continue; // the loop ends: nothing is running, and nothing new is started this late
                }

                running.remove(r);
                Throwable error = r.error;
                if (error == null) {
                    List<Racer> losers = new ArrayList<>(running);
                    running.clear();
                    return winner(ctx, chain, r, losers, notes, started, asked);
                }
                if (error instanceof CancelledException) {
                    continue;
                }
                if (error instanceof ApiException) {
                    ApiException e = (ApiException) error;
                    String why = moveOnReason(ctx, r.model, e);
                    if (why == null) {
                        throw e; // not about this model (bad key, bad request ...): the other models would say the same
                    }
                    Logs.i(ctx, TAG, r.model + " cannot answer: " + why
                            + (running.isEmpty() && next < order.size() ? ". Trying the next model." : ".")
                            + "\n" + e.getMessage());
                    notes.add(r.model + ": " + why);
                    lastDetail = e.getMessage();
                    continue;
                }
                if (error instanceof SlowResponseException) {
                    // The time for the key press ran out inside this request. The same words as when the clock
                    // above runs out first, because which of the two happens first is a matter of milliseconds.
                    String why = "no answer after " + secondsText(r.finishedAt - r.startedAt)
                            + ", the request was cancelled";
                    Logs.i(ctx, TAG, r.model + " cannot answer: " + why + ".\n" + error.getMessage());
                    notes.add(r.model + ": " + why);
                    lastDetail = "No model answered within the " + Math.round(budgetMs / 1000.0) + " s allowed for "
                            + "one key press.";
                    continue;
                }
                rethrow(error); // a network failure, an unreadable reply ...: ends the key press like before
            }
        } finally {
            for (Racer waiting : running) {
                waiting.attempt.cancel(); // an error or an interrupt ended the key press: stop what is still running
            }
        }

        StringBuilder sb = new StringBuilder("No model could answer. Tried in this order:");
        for (String n : notes) {
            sb.append("\n- ").append(n);
        }
        if (!lastDetail.isEmpty()) {
            sb.append("\n\nLast error in full:\n").append(lastDetail);
        }
        throw new IOException(sb.toString());
    }

    /**
     * The first model to answer wins: the requests still running are cancelled. A model that was asked earlier and
     * is still waiting is the slow one, and it is skipped for a while so the next key press does not wait for it.
     */
    private static Reply winner(Context ctx, List<String> chain, Racer won, List<Racer> losers, List<String> notes,
                                long started, int asked) {
        long tookMs = won.finishedAt - won.startedAt;
        for (Racer lost : losers) {
            lost.attempt.cancel();
            long waited = won.finishedAt - lost.startedAt;
            // A request that finished a moment after the winner was not slow, it just lost by a hair.
            if (lost.startedAt < won.startedAt && lost.finishedAt == 0) {
                String why = "slower than " + won.model + " (no answer after " + secondsText(waited) + ")";
                Prefs.setSkipUntil(ctx, lost.model, System.currentTimeMillis() + SLOW_MODEL_SKIP_MS, why);
                String skipped = "cancelled, skipped for " + SLOW_MODEL_SKIP_MS / 60000 + " min";
                Logs.i(ctx, TAG, won.model + " answered first, after " + secondsText(tookMs) + ". " + lost.model
                        + " had been waiting for " + secondsText(waited) + " and was " + skipped + " so the next key "
                        + "press goes straight to a model that is fast right now.");
                notes.add(lost.model + ": " + why + ", " + skipped);
            } else {
                Logs.i(ctx, TAG, won.model + " answered first, after " + secondsText(tookMs) + ". The request to "
                        + lost.model + ", started " + secondsText(waited) + " ago, was cancelled.");
                notes.add(lost.model + ": cancelled, " + won.model + " answered first");
            }
        }
        if (Prefs.skipUntil(ctx, won.model) > 0) {
            Prefs.clearSkip(ctx, won.model); // it answered, so whatever marked it as unavailable is over
        }
        if (won.position > 0) {
            Logs.i(ctx, TAG, "Answered by fallback model " + won.model + " (main model: " + chain.get(0) + ").");
        }
        if (asked > 1) {
            Logs.i(ctx, TAG, "Time spent on models for this key press: "
                    + (SystemClock.elapsedRealtime() - started) + " ms (" + asked + " models asked).");
        }
        return new Reply(won.text, won.model, join(notes));
    }

    private static void rethrow(Throwable t) throws IOException, JSONException {
        if (t instanceof IOException) {
            throw (IOException) t;
        }
        if (t instanceof JSONException) {
            throw (JSONException) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new IOException(t);
    }

    private static long payloadChars(List<Piece> pieces) {
        long chars = 0;
        for (Piece p : pieces) {
            chars += p.data.length();
        }
        return chars;
    }

    /** Large requests (a big reference file) need more time before the first word of the answer. */
    private static long sizeBonusMs(long chars) {
        return Math.max(0, Math.min(MAX_BONUS_MS, (chars - BONUS_FREE_CHARS) / 100000 * 1000));
    }

    /**
     * Why the next model should be tried after this error, or null when the error is not about this model (bad
     * key, bad request ...) and trying other models would only repeat it. Also remembers for how long this model
     * should be skipped.
     */
    private static String moveOnReason(Context ctx, String model, ApiException e) {
        long now = System.currentTimeMillis();
        String b = e.body.toLowerCase(Locale.ROOT);
        if (e.code == 429) {
            if (b.contains("perday") || b.contains("per_day") || b.contains("per day") || b.contains("limit: 0")) {
                long until = nextPacificMidnight(now);
                Prefs.setSkipUntil(ctx, model, until, "daily quota used up");
                return "daily quota used up (HTTP 429), skipped until " + stamp(until);
            }
            long wait = retryDelayMs(e.body);
            long ms = wait > 0 ? Math.min(wait + 1000, MAX_RATE_LIMIT_SKIP_MS) : UNKNOWN_RATE_LIMIT_SKIP_MS;
            Prefs.setSkipUntil(ctx, model, now + ms, "rate limit reached");
            return "rate limit reached (HTTP 429), skipped for " + Math.round(ms / 1000.0) + " s";
        }
        if (e.code >= 500) {
            Prefs.setSkipUntil(ctx, model, now + SERVER_ERROR_SKIP_MS, "server error HTTP " + e.code);
            return "server error (HTTP " + e.code + "), skipped for " + SERVER_ERROR_SKIP_MS / 60000 + " min";
        }
        if (e.code == 404 || (e.code == 400 && mentionsMissingModel(b))) {
            Prefs.setSkipUntil(ctx, model, now + MODEL_MISSING_SKIP_MS, "model not available for this key");
            return "model not available for this key (HTTP " + e.code + "), skipped for 30 min";
        }
        return null;
    }

    private static boolean mentionsMissingModel(String lowerBody) {
        return lowerBody.contains("model") && (lowerBody.contains("not found") || lowerBody.contains("not supported")
                || lowerBody.contains("does not exist") || lowerBody.contains("is not available"));
    }

    private static long retryDelayMs(String body) {
        Matcher m = RETRY_DELAY.matcher(body);
        if (m.find()) {
            try {
                return Math.round(Double.parseDouble(m.group(1)) * 1000);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** Free-tier daily quotas reset at midnight Pacific time. */
    private static long nextPacificMidnight(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static String stamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis)) + " (device time)";
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- route selection (one model)

    /** Sends the pieces (reference file, screenshot, instructions) to one model and returns its text. */
    private static String generateWith(Context ctx, String apiKey, String name, List<Piece> pieces,
                                       boolean json, Attempt attempt) throws IOException, JSONException {
        String thinking = thinkingFor(ctx, name, Prefs.thinking(ctx));

        String known = Prefs.route(ctx);
        String first = !known.isEmpty() ? known
                : (apiKey.startsWith("AQ.") ? ROUTE_INTERACTIONS : ROUTE_GENERATE);
        String second = first.equals(ROUTE_INTERACTIONS) ? ROUTE_GENERATE : ROUTE_INTERACTIONS;

        ApiException firstError;
        try {
            String reply = viaRoute(ctx, first, apiKey, name, pieces, json, thinking, attempt);
            remember(ctx, first);
            return reply;
        } catch (ApiException e) {
            if (e.code == 429) {
                remember(ctx, first); // the key was accepted on this route, only the quota is used up
            }
            if (!routeMayBeWrong(e.code)) {
                throw e;
            }
            firstError = e;
        }

        Logs.i(ctx, TAG, "The " + first + " route was rejected for " + name + ", trying the " + second
                + " route instead. First error:\n" + firstError.getMessage());
        try {
            String reply = viaRoute(ctx, second, apiKey, name, pieces, json, thinking, attempt);
            remember(ctx, second);
            return reply;
        } catch (ApiException e) {
            if (e.code == 429) {
                remember(ctx, second);
            }
            // The status that decides what happens next. A quota or server error from the second route proves the
            // key and model are accepted there, so it wins. Otherwise a 404 on either route usually means this
            // model name is not available for the key.
            boolean keyProblem = isKeyProblem(firstError.body) || isKeyProblem(e.body);
            int code = e.code == 429 || e.code >= 500 ? e.code
                    : (firstError.code == 404 || e.code == 404) && !keyProblem ? 404 : e.code;
            throw new ApiException(code, "Both API routes failed for " + name + ".\n\n--- " + first
                    + " route ---\n" + firstError.getMessage() + "\n\n--- " + second + " route ---\n"
                    + e.getMessage(), firstError.body + "\n" + e.body);
        }
    }

    /**
     * The thinking level to send to this model. "minimal" does not exist on every model (Gemini 3.8 Flash starts
     * at "low"); asking for it would be rejected and the model's own, slower default would be used instead.
     */
    private static String thinkingFor(Context ctx, String model, String level) {
        String m = model.toLowerCase(Locale.ROOT);
        if (level.equals("minimal") && (m.startsWith("gemini-3.8") || m.contains("-pro"))) {
            Logs.i(ctx, TAG, model + " has no \"minimal\" thinking level; using \"low\", its fastest.");
            return "low";
        }
        return level;
    }

    /** Client errors that another route might not have: bad request, unauthorized, forbidden, not found... */
    private static boolean routeMayBeWrong(int code) {
        return code == 400 || code == 401 || code == 403 || code == 404 || code == 405 || code == 415;
    }

    private static boolean isKeyProblem(String body) {
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("api key") || b.contains("api_key") || b.contains("access_token")
                || b.contains("unauthenticated") || b.contains("credential");
    }

    private static void remember(Context ctx, String route) {
        if (!route.equals(Prefs.route(ctx))) {
            Prefs.setRoute(ctx, route);
            Logs.i(ctx, TAG, "The " + route + " route works with this key; using it first from now on.");
        }
    }

    private static String viaRoute(Context ctx, String route, String apiKey, String name, List<Piece> pieces,
                                   boolean json, String thinking, Attempt attempt) throws IOException, JSONException {
        boolean interactions = route.equals(ROUTE_INTERACTIONS);
        String url = interactions ? API_ROOT + "interactions" : API_ROOT + "models/" + name + ":generateContent";
        boolean options = true;
        String level = thinking;
        while (true) {
            JSONObject body = interactions
                    ? interactionsBody(name, pieces, level, options)
                    : generateBody(pieces, json, level, options);
            try {
                String response = post(ctx, url, route, name, apiKey, body, attempt);
                return interactions ? interactionsText(response) : generateText(response);
            } catch (ApiException e) {
                boolean hasOptions = interactions || !level.isEmpty();
                if (!(options && hasOptions && e.code == 400 && !isKeyProblem(e.body))) {
                    throw e;
                }
                if (level.equals("minimal") && e.body.toLowerCase(Locale.ROOT).contains("thinking")) {
                    // No "minimal" on this model: "low" is the next fastest level. Dropping the setting would give
                    // the model's own default, which is slower.
                    Logs.i(ctx, TAG, "HTTP 400 on the " + route + " route: " + name + " does not accept the thinking "
                            + "level \"minimal\". Retrying with \"low\".\n" + e.getMessage());
                    level = "low";
                    continue;
                }
                Logs.i(ctx, TAG, "HTTP 400 on the " + route + " route. Retrying once without the optional "
                        + "settings.\n" + e.getMessage());
                options = false;
            }
        }
    }

    // ---------------------------------------------------------------- request bodies

    private static JSONObject generateBody(List<Piece> pieces, boolean json, String thinking, boolean options)
            throws JSONException {
        JSONArray parts = new JSONArray();
        for (Piece p : pieces) {
            if (p.type.equals(Piece.TEXT)) {
                parts.put(new JSONObject().put("text", p.data));
            } else {
                JSONObject inline = new JSONObject().put("mime_type", p.mime).put("data", p.data);
                parts.put(new JSONObject().put("inline_data", inline));
            }
        }
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)));

        JSONObject generation = new JSONObject();
        if (json) {
            generation.put("responseMimeType", "application/json");
        }
        if (options && !thinking.isEmpty()) {
            generation.put("thinkingConfig", new JSONObject().put("thinkingLevel", thinking));
        }
        if (generation.length() > 0) {
            body.put("generationConfig", generation);
        }
        return body;
    }

    private static JSONObject interactionsBody(String model, List<Piece> pieces, String thinking, boolean options)
            throws JSONException {
        JSONArray input = new JSONArray();
        for (Piece p : pieces) {
            if (p.type.equals(Piece.TEXT)) {
                input.put(new JSONObject().put("type", "text").put("text", p.data));
            } else {
                input.put(new JSONObject().put("type", p.type).put("data", p.data).put("mime_type", p.mime));
            }
        }
        JSONObject body = new JSONObject().put("model", model).put("input", input);
        if (options) {
            body.put("store", false); // do not keep the screenshot in an interaction history
            if (!thinking.isEmpty()) {
                body.put("generation_config", new JSONObject().put("thinking_level", thinking));
            }
        }
        return body;
    }

    // ---------------------------------------------------------------- HTTP

    private static String post(Context ctx, String url, String route, String name, String apiKey, JSONObject body,
                               Attempt attempt) throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        ApiException lastError = null;

        for (int tryNo = 1; tryNo <= 2; tryNo++) {
            if (attempt.cancelled) {
                throw new CancelledException(name);
            }
            long t0 = SystemClock.elapsedRealtime();
            long remaining = attempt.deadline - t0;
            if (remaining < MIN_HTTP_MS) {
                throw new SlowResponseException("The " + Math.round(attempt.limitMs / 1000.0) + " s given to " + name
                        + " were used up before this request could start.", attempt.limitMs);
            }
            Logs.i(ctx, TAG, "POST " + name + " via " + route + " (attempt " + tryNo + ", " + sizeText(payload.length)
                    + ", waiting at most " + Math.round(remaining / 1000.0) + " s)");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            attempt.track(conn);
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remaining));
                conn.setReadTimeout((int) remaining);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                conn.connect(); // TCP + TLS; a failure here is a network problem, not a slow model
                long connected = SystemClock.elapsedRealtime();
                if (attempt.cancelled) {
                    throw new CancelledException(name); // cancel() cannot stop a connect that is still in progress
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
                int code;
                try {
                    code = conn.getResponseCode();
                } catch (SocketTimeoutException timeout) {
                    throw slow(name, attempt, t0, "waiting for the reply");
                } catch (IOException first) {
                    if (attempt.cancelled) {
                        throw first; // the connection was closed on purpose
                    }
                    // Android quirk: a 401 without a WWW-Authenticate header can make the first call throw
                    // "No authentication challenges found"; asking again returns the real status.
                    try {
                        code = conn.getResponseCode();
                    } catch (IOException second) {
                        throw first;
                    }
                }
                long headers = SystemClock.elapsedRealtime();
                String responseBody;
                try {
                    responseBody = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                } catch (SocketTimeoutException timeout) {
                    throw slow(name, attempt, t0, "downloading the reply");
                }
                long done = SystemClock.elapsedRealtime();
                if (attempt.cancelled) {
                    throw new CancelledException(name); // another model answered first; say nothing about this one
                }
                Logs.i(ctx, TAG, "HTTP " + code + " in " + (done - t0) + " ms (" + name + ": connect "
                        + (connected - t0) + " ms, send and wait for the model " + (headers - connected)
                        + " ms, download " + (done - headers) + " ms, reply " + sizeText(responseBody.length()) + ")");
                if (code < 400) {
                    logUsage(ctx, name, responseBody);
                    return responseBody;
                }
                String reason = conn.getResponseMessage();
                lastError = new ApiException(code, "Gemini returned HTTP " + code
                        + (reason == null || reason.isEmpty() ? "" : " " + reason)
                        + " (" + route + " route)\n" + cap(responseBody), responseBody);
                // A used-up quota will not be back in 1.5 s: the next model is the better answer to that. The same
                // goes for an overloaded model while another model is still waiting to be asked.
                boolean quota = code == 429 && responseBody.toLowerCase(Locale.ROOT).contains("quota");
                boolean retryable = code >= 500 || (code == 429 && !quota);
                if (!retryable || tryNo == 2 || !attempt.retryServerErrors) {
                    throw lastError;
                }
                Logs.i(ctx, TAG, "HTTP " + code + " from " + name + " is retryable and no other model is left, "
                        + "trying once more in 1.5 s");
                pause(attempt, name, 1500);
            } catch (IOException failure) {
                if (attempt.cancelled && !(failure instanceof CancelledException)) {
                    throw new CancelledException(name); // cancel() closed the connection under the blocked call
                }
                throw failure;
            } finally {
                conn.disconnect();
            }
        }
        throw lastError != null ? lastError : new IOException("Request failed");
    }

    /** Sleeps, but ends early with a {@link CancelledException} when the request is cancelled. */
    private static void pause(Attempt attempt, String name, long ms) throws CancelledException {
        long until = SystemClock.elapsedRealtime() + ms;
        while (true) {
            if (attempt.cancelled) {
                throw new CancelledException(name);
            }
            long left = until - SystemClock.elapsedRealtime();
            if (left <= 0) {
                return;
            }
            SystemClock.sleep(Math.min(left, 100));
        }
    }

    private static SlowResponseException slow(String name, Attempt attempt, long t0, String phase) {
        long waited = SystemClock.elapsedRealtime() - t0;
        return new SlowResponseException("No reply from " + name + " after " + secondsText(waited) + " while " + phase
                + " (limit " + Math.round(attempt.limitMs / 1000.0) + " s). The request was cancelled.", waited);
    }

    private static final String[] USAGE_FIELDS = {"promptTokenCount", "candidatesTokenCount", "thoughtsTokenCount",
            "totalTokenCount", "total_input_tokens", "total_output_tokens", "total_thought_tokens", "total_tokens"};

    /** What the model used for this answer. Thinking tokens are what makes a model slow. */
    private static void logUsage(Context ctx, String model, String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject usage = root.optJSONObject("usageMetadata"); // generateContent
            if (usage == null) {
                usage = root.optJSONObject("usage"); // interactions
            }
            if (usage == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String field : USAGE_FIELDS) {
                if (usage.has(field)) {
                    sb.append(sb.length() > 0 ? ", " : "").append(field).append('=').append(usage.opt(field));
                }
            }
            if (sb.length() == 0) {
                String all = usage.toString();
                sb.append(all.length() > 300 ? all.substring(0, 300) + "..." : all);
            }
            Logs.i(ctx, TAG, "Tokens used by " + model + ": " + sb);
        } catch (JSONException ignored) {
            // the answer itself is parsed (and reported) elsewhere
        }
    }

    private static String sizeText(long bytes) {
        return bytes < 1024 ? bytes + " bytes" : (bytes / 1024) + " KB";
    }

    private static String secondsText(long ms) {
        return String.format(Locale.US, "%.1f s", ms / 1000.0);
    }

    // ---------------------------------------------------------------- responses

    /** generateContent: candidates[0].content.parts[].text, skipping "thought" parts. */
    private static String generateText(String response) throws IOException, JSONException {
        JSONObject root = new JSONObject(response);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            JSONObject feedback = root.optJSONObject("promptFeedback");
            String why = feedback == null ? "" : feedback.optString("blockReason", "");
            throw new IOException("Gemini returned no candidates" + (why.isEmpty() ? "" : " (blockReason=" + why + ")")
                    + "\n" + cap(response));
        }
        JSONObject first = candidates.getJSONObject(0);
        JSONObject content = first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) {
            throw new IOException("Gemini returned a candidate without content (finishReason="
                    + first.optString("finishReason", "?") + ")\n" + cap(response));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (!part.optBoolean("thought", false)) {
                sb.append(part.optString("text", ""));
            }
        }
        return sb.toString();
    }

    /** interactions: steps[] with type "model_output", whose content[] items of type "text" hold the answer. */
    private static String interactionsText(String response) throws IOException, JSONException {
        JSONObject root = new JSONObject(response);
        String status = root.optString("status", "");
        if (status.equals("failed") || status.equals("cancelled")) {
            throw new IOException("Interaction " + status + "\n" + cap(response));
        }
        StringBuilder sb = new StringBuilder();
        JSONArray steps = root.optJSONArray("steps");
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step != null && step.optString("type", "").equals("model_output")) {
                    appendText(sb, step.opt("content"));
                }
            }
        }
        if (sb.length() == 0) {
            appendText(sb, root.opt("outputs")); // shape used by the earlier preview of this API
        }
        if (sb.length() == 0) {
            appendText(sb, root.opt("output_text"));
        }
        if (sb.length() == 0) {
            throw new IOException("Interactions API returned no text (status=" + status + ")\n" + cap(response));
        }
        return sb.toString();
    }

    private static void appendText(StringBuilder sb, Object content) {
        if (content instanceof String) {
            sb.append((String) content);
        } else if (content instanceof JSONObject) {
            appendItem(sb, (JSONObject) content);
        } else if (content instanceof JSONArray) {
            JSONArray items = (JSONArray) content;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    appendItem(sb, item);
                }
            }
        }
    }

    private static void appendItem(StringBuilder sb, JSONObject item) {
        if (item.optString("type", "text").equals("text")) {
            sb.append(item.optString("text", ""));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static String cap(String s) {
        if (s.length() <= MAX_LOGGED_BODY) {
            return s;
        }
        return s.substring(0, MAX_LOGGED_BODY) + "\n... (truncated, " + s.length() + " chars in total)";
    }
}
