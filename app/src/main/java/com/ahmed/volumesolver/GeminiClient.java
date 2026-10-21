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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
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
 */
final class GeminiClient {

    static final class Answer {
        boolean found;
        String text = "";
        String raw = "";
        /** The model that produced the answer (can be a fallback). */
        String model = "";
        /** Name of the reference file that was sent with the question, "" if none. */
        String reference = "";
        /** The passage of the reference file the model says supports its answer. */
        String evidence = "";
        /** Whether that passage really occurs in the file: null when it cannot be checked (PDF, image, no quote). */
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

    static final String ROUTE_INTERACTIONS = "interactions";
    static final String ROUTE_GENERATE = "generateContent";

    private static final String TAG = "Gemini";
    private static final String API_ROOT = "https://generativelanguage.googleapis.com/v1beta/";
    private static final int MAX_LOGGED_BODY = 8000;
    private static final long MODEL_MISSING_SKIP_MS = 30 * 60 * 1000L;
    private static final long UNKNOWN_RATE_LIMIT_SKIP_MS = 60 * 1000L;
    private static final long MAX_RATE_LIMIT_SKIP_MS = 120 * 1000L;

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
        // The optional reference file goes first, then the screenshot, then the instructions.
        Reference.Payload ref = null;
        try {
            ref = Reference.load(ctx);
        } catch (IOException e) {
            Logs.e(ctx, TAG, "The attached reference file could not be loaded, so this question is sent without it", e);
        }
        List<Piece> pieces = new ArrayList<>();
        if (ref != null) {
            pieces.addAll(referencePieces(ref));
            Logs.i(ctx, TAG, "Sending the reference file with the question: " + ref.info.describe());
        }
        pieces.add(Piece.image("image/jpeg", jpeg));
        pieces.add(Piece.text(ref == null ? PROMPT : referencePrompt(ref.info.name)));

        Reply r = generate(ctx, apiKey, model, pieces, true, false);
        String reply = r.text;
        Logs.i(ctx, TAG, "Raw reply from " + r.model + ": " + reply);

        String trimmed = reply.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("Reply from " + r.model + " is not JSON: " + reply);
        }
        JSONObject json = new JSONObject(trimmed.substring(start, end + 1));

        Answer a = new Answer();
        a.raw = reply;
        a.model = r.model;
        a.found = json.optBoolean("found", true);
        a.text = json.optString("answer", "");
        if (ref != null) {
            a.reference = ref.info.name;
            a.evidence = json.optString("evidence", "").trim();
            a.evidenceInReference = Reference.quoteInText(ref.text, a.evidence);
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
     * Used by the "Test API key" button when a reference file is attached: sends the file with a tiny question to
     * prove that Google accepts it and that the model can read it.
     */
    static Reply checkReference(Context ctx, String apiKey, String model) throws IOException, JSONException {
        Reference.Payload ref = Reference.load(ctx);
        if (ref == null) {
            throw new IOException("No reference file is attached.");
        }
        List<Piece> pieces = new ArrayList<>(referencePieces(ref));
        pieces.add(Piece.text("Quote the first sentence of the reference document word for word (at most 25 words) "
                + "and write nothing else."));
        Reply r = generate(ctx, apiKey, model, pieces, false, true);
        return new Reply(r.text.trim(), r.model, r.notes);
    }

    private static List<Piece> referencePieces(Reference.Payload ref) {
        List<Piece> pieces = new ArrayList<>();
        if (ref.info.kind.equals(Reference.KIND_TEXT)) {
            pieces.add(Piece.text("REFERENCE DOCUMENT \"" + ref.info.name + "\" (the trusted source for the answer):\n"
                    + ref.text + "\n--- END OF REFERENCE DOCUMENT ---"));
        } else if (ref.info.kind.equals(Reference.KIND_PDF)) {
            pieces.add(Piece.document(ref.info.mime, ref.bytes));
        } else {
            pieces.add(Piece.image(ref.info.mime, ref.bytes));
        }
        return pieces;
    }

    private static String referencePrompt(String fileName) {
        return "This is a screenshot of a phone that shows a question with several selectable answer options. "
                + "A reference document (" + fileName + ") was provided before the screenshot. It is the trusted "
                + "source: answer from it, not from memory. Find the passage of the document that answers the "
                + "question, then pick the option that passage supports. "
                + "Reply with JSON only (no other text) using exactly these keys, in this order: "
                + "\"found\": true if a question with selectable options is visible, otherwise false; "
                + "\"evidence\": a short quote (at most 200 characters) copied word for word from the reference "
                + "document that answers the question, or an empty string if the document does not cover the question; "
                + "\"answer\": the exact text of the correct option, or only its letter or number if that is all the "
                + "screen shows; "
                + "\"box_2d\": [ymin, xmin, ymax, xmax], the tappable area of that option (the whole row or button, "
                + "not just its letter or number), as integers from 0 to 1000 relative to the image height and width. "
                + "Only if the document does not cover the question, use your own knowledge and leave evidence empty. "
                + "If you are unsure, pick the most likely option. "
                + "If no question is visible, reply {\"found\": false, \"evidence\": \"\", \"answer\": \"\", "
                + "\"box_2d\": [0, 0, 0, 0]}.";
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
        ApiException last = null;
        for (int i = 0; i < chain.size(); i++) {
            String m = chain.get(i);
            long until = Prefs.skipUntil(ctx, m);
            if (useSkips && until > now) {
                Logs.i(ctx, TAG, "Skipping " + m + " until " + stamp(until)
                        + " (an earlier error marked it as unavailable).");
                notes.add(m + ": skipped until " + stamp(until));
                continue;
            }
            try {
                String text = generateWith(ctx, apiKey, m, pieces, json);
                if (until > 0) {
                    Prefs.clearSkip(ctx, m);
                }
                if (i > 0) {
                    Logs.i(ctx, TAG, "Answered by fallback model " + m + " (main model: " + chain.get(0) + ").");
                }
                return new Reply(text, m, join(notes));
            } catch (ApiException e) {
                String why = moveOnReason(ctx, m, e);
                if (why == null) {
                    throw e;
                }
                boolean more = i < chain.size() - 1;
                Logs.i(ctx, TAG, m + " cannot answer: " + why + (more ? ". Trying the next model." : ".")
                        + "\n" + e.getMessage());
                notes.add(m + ": " + why);
                last = e;
            }
        }

        StringBuilder sb = new StringBuilder("No model could answer. Tried in this order:");
        for (String n : notes) {
            sb.append("\n- ").append(n);
        }
        if (last != null) {
            sb.append("\n\nLast error in full:\n").append(last.getMessage());
        }
        throw new IOException(sb.toString());
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
                Prefs.setSkipUntil(ctx, model, until);
                return "daily quota used up (HTTP 429), skipped until " + stamp(until);
            }
            long wait = retryDelayMs(e.body);
            long ms = wait > 0 ? Math.min(wait + 1000, MAX_RATE_LIMIT_SKIP_MS) : UNKNOWN_RATE_LIMIT_SKIP_MS;
            Prefs.setSkipUntil(ctx, model, now + ms);
            return "rate limit reached (HTTP 429), skipped for " + Math.round(ms / 1000.0) + " s";
        }
        if (e.code >= 500) {
            return "server error (HTTP " + e.code + ")";
        }
        if (e.code == 404 || (e.code == 400 && mentionsMissingModel(b))) {
            Prefs.setSkipUntil(ctx, model, now + MODEL_MISSING_SKIP_MS);
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
                                       boolean json) throws IOException, JSONException {
        String thinking = Prefs.thinking(ctx);

        String known = Prefs.route(ctx);
        String first = !known.isEmpty() ? known
                : (apiKey.startsWith("AQ.") ? ROUTE_INTERACTIONS : ROUTE_GENERATE);
        String second = first.equals(ROUTE_INTERACTIONS) ? ROUTE_GENERATE : ROUTE_INTERACTIONS;

        ApiException firstError;
        try {
            String reply = viaRoute(ctx, first, apiKey, name, pieces, json, thinking);
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
            String reply = viaRoute(ctx, second, apiKey, name, pieces, json, thinking);
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
                                   boolean json, String thinking) throws IOException, JSONException {
        boolean interactions = route.equals(ROUTE_INTERACTIONS);
        String url = interactions ? API_ROOT + "interactions" : API_ROOT + "models/" + name + ":generateContent";
        boolean options = true;
        while (true) {
            JSONObject body = interactions
                    ? interactionsBody(name, pieces, thinking, options)
                    : generateBody(pieces, json, thinking, options);
            try {
                String response = post(ctx, url, route, name, apiKey, body);
                return interactions ? interactionsText(response) : generateText(response);
            } catch (ApiException e) {
                boolean hasOptions = interactions || !thinking.isEmpty();
                if (!(options && hasOptions && e.code == 400 && !isKeyProblem(e.body))) {
                    throw e;
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

    private static String post(Context ctx, String url, String route, String name, String apiKey, JSONObject body)
            throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        ApiException lastError = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            long t0 = SystemClock.elapsedRealtime();
            Logs.i(ctx, TAG, "POST " + name + " via " + route + " (attempt " + attempt + ", "
                    + (payload.length < 1024 ? payload.length + " bytes" : (payload.length / 1024) + " KB") + ")");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
                int code;
                try {
                    code = conn.getResponseCode();
                } catch (IOException first) {
                    // Android quirk: a 401 without a WWW-Authenticate header can make the first call throw
                    // "No authentication challenges found"; asking again returns the real status.
                    try {
                        code = conn.getResponseCode();
                    } catch (IOException second) {
                        throw first;
                    }
                }
                String responseBody = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                Logs.i(ctx, TAG, "HTTP " + code + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
                if (code < 400) {
                    return responseBody;
                }
                String reason = conn.getResponseMessage();
                lastError = new ApiException(code, "Gemini returned HTTP " + code
                        + (reason == null || reason.isEmpty() ? "" : " " + reason)
                        + " (" + route + " route)\n" + cap(responseBody), responseBody);
                // A used-up quota will not be back in 1.5 s: the next model is the better answer to that.
                boolean quota = code == 429 && responseBody.toLowerCase(Locale.ROOT).contains("quota");
                boolean retryable = code >= 500 || (code == 429 && !quota);
                if (!retryable || attempt == 2) {
                    throw lastError;
                }
                Logs.i(ctx, TAG, "HTTP " + code + " is retryable, trying once more in 1.5 s");
                SystemClock.sleep(1500);
            } finally {
                conn.disconnect();
            }
        }
        throw lastError != null ? lastError : new IOException("Request failed");
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
