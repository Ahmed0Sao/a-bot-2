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
import java.util.Locale;

/**
 * Minimal Gemini REST client with two routes:
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
        double yMin;
        double xMin;
        double yMax;
        double xMax;
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
        String image = Base64.encodeToString(jpeg, Base64.NO_WRAP);
        String reply = generate(ctx, apiKey, model, PROMPT, image, true);
        Logs.i(ctx, TAG, "Raw reply: " + reply);

        String trimmed = reply.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("Reply is not JSON: " + reply);
        }
        JSONObject json = new JSONObject(trimmed.substring(start, end + 1));

        Answer a = new Answer();
        a.raw = reply;
        a.found = json.optBoolean("found", true);
        a.text = json.optString("answer", "");
        JSONArray box = json.optJSONArray("box_2d");
        if (box != null && box.length() >= 4) {
            a.yMin = box.optDouble(0, 0);
            a.xMin = box.optDouble(1, 0);
            a.yMax = box.optDouble(2, 0);
            a.xMax = box.optDouble(3, 0);
        }
        if (a.found && !(a.xMax > a.xMin && a.yMax > a.yMin)) {
            throw new IOException("Reply has no usable box_2d: " + reply);
        }
        return a;
    }

    /** Tiny text-only request used by the "Test API key" button. */
    static String ping(Context ctx, String apiKey, String model) throws IOException, JSONException {
        return generate(ctx, apiKey, model, "Reply with the single word OK.", null, false).trim();
    }

    // ---------------------------------------------------------------- route selection

    /** Sends the prompt (and optional JPEG as base64) and returns the model's text. */
    private static String generate(Context ctx, String apiKey, String model, String prompt, String image,
                                   boolean json) throws IOException, JSONException {
        String name = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String thinking = Prefs.thinking(ctx);

        String known = Prefs.route(ctx);
        String first = !known.isEmpty() ? known
                : (apiKey.startsWith("AQ.") ? ROUTE_INTERACTIONS : ROUTE_GENERATE);
        String second = first.equals(ROUTE_INTERACTIONS) ? ROUTE_GENERATE : ROUTE_INTERACTIONS;

        ApiException firstError;
        try {
            String reply = viaRoute(ctx, first, apiKey, name, prompt, image, json, thinking);
            remember(ctx, first);
            return reply;
        } catch (ApiException e) {
            if (!routeMayBeWrong(e.code)) {
                throw e;
            }
            firstError = e;
        }

        Logs.i(ctx, TAG, "The " + first + " route was rejected, trying the " + second
                + " route instead. First error:\n" + firstError.getMessage());
        try {
            String reply = viaRoute(ctx, second, apiKey, name, prompt, image, json, thinking);
            remember(ctx, second);
            return reply;
        } catch (ApiException e) {
            throw new IOException("Both API routes failed.\n\n--- " + first + " route ---\n"
                    + firstError.getMessage() + "\n\n--- " + second + " route ---\n" + e.getMessage());
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

    private static String viaRoute(Context ctx, String route, String apiKey, String name, String prompt,
                                   String image, boolean json, String thinking) throws IOException, JSONException {
        boolean interactions = route.equals(ROUTE_INTERACTIONS);
        String url = interactions ? API_ROOT + "interactions" : API_ROOT + "models/" + name + ":generateContent";
        boolean options = true;
        while (true) {
            JSONObject body = interactions
                    ? interactionsBody(name, prompt, image, thinking, options)
                    : generateBody(prompt, image, json, thinking, options);
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

    private static JSONObject generateBody(String prompt, String image, boolean json, String thinking,
                                           boolean options) throws JSONException {
        JSONArray parts = new JSONArray();
        if (image != null) {
            JSONObject inline = new JSONObject().put("mime_type", "image/jpeg").put("data", image);
            parts.put(new JSONObject().put("inline_data", inline));
        }
        parts.put(new JSONObject().put("text", prompt));
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

    private static JSONObject interactionsBody(String model, String prompt, String image, String thinking,
                                               boolean options) throws JSONException {
        JSONArray input = new JSONArray();
        if (image != null) {
            input.put(new JSONObject().put("type", "image").put("data", image).put("mime_type", "image/jpeg"));
        }
        input.put(new JSONObject().put("type", "text").put("text", prompt));
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
                boolean retryable = code == 429 || code >= 500;
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
