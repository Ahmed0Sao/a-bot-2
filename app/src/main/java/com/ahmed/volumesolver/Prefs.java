package com.ahmed.volumesolver;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {

    static final String DEFAULT_MODEL = "gemini-3.8-flash";

    /**
     * Models with a free tier (per Google's pricing page), best first. Each model has its own free quota, so when
     * the main model is used up the next one takes over.
     */
    static final String DEFAULT_FALLBACKS =
            "gemini-3.6-flash, gemini-3-flash-preview, gemini-3.5-flash-lite, gemini-3.1-flash-lite";

    static final String DEFAULT_THINKING = "low";
    /** Seconds a model gets to answer before the next model is asked as well (see {@link GeminiClient}). */
    static final int DEFAULT_WAIT_SECONDS = 10;
    static final int MIN_WAIT_SECONDS = 3;
    static final int MAX_WAIT_SECONDS = 60;
    /** Quick taps in a row that start the solver ("more than 3"), and the range the settings accept. */
    static final int DEFAULT_TAPS = 4;
    static final int MIN_TAPS = 3;
    static final int MAX_TAPS = 10;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("volume_solver", Context.MODE_PRIVATE);
    }

    /** The raw settings store, for classes that keep their own keys (see {@link Reference}). */
    static SharedPreferences store(Context c) {
        return sp(c);
    }

    static String apiKey(Context c) {
        return sp(c).getString("api_key", "").trim();
    }

    static String model(Context c) {
        String m = sp(c).getString("model", DEFAULT_MODEL).trim();
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }

    /** Fallback models. The default list is used until the user saves their own (an empty list means none). */
    static String fallbacks(Context c) {
        SharedPreferences p = sp(c);
        return p.contains("fallbacks") ? p.getString("fallbacks", "").trim() : DEFAULT_FALLBACKS;
    }

    /**
     * Thinking level sent to the model. "low" (the fastest level every current Flash model accepts) until the user
     * saves their own value; an empty saved value means "the model's own default", which is slower.
     */
    static String thinking(Context c) {
        SharedPreferences p = sp(c);
        String level = p.contains("thinking") ? p.getString("thinking", "") : DEFAULT_THINKING;
        return level.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** How long a model is given, in seconds, before the next model is asked as well. */
    static int waitSeconds(Context c) {
        int seconds = sp(c).getInt("wait_seconds", DEFAULT_WAIT_SECONDS);
        return seconds < 1 ? DEFAULT_WAIT_SECONDS : Math.min(seconds, MAX_WAIT_SECONDS);
    }

    /** Saves the text of the settings field; anything that is not a number goes back to the default. */
    static void setWait(Context c, String text) {
        int seconds = DEFAULT_WAIT_SECONDS;
        try {
            seconds = Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            // keeps the default
        }
        seconds = Math.max(MIN_WAIT_SECONDS, Math.min(MAX_WAIT_SECONDS, seconds));
        sp(c).edit().putInt("wait_seconds", seconds).apply();
    }

    static boolean active(Context c) {
        return sp(c).getBoolean("active", true);
    }

    /** Whether several quick taps on the screen start the solver, like Volume Down does. */
    static boolean tapTrigger(Context c) {
        return sp(c).getBoolean("tap_trigger", true);
    }

    /** How many quick taps in a row start the solver. */
    static int tapCount(Context c) {
        return Math.max(MIN_TAPS, Math.min(MAX_TAPS, sp(c).getInt("tap_count", DEFAULT_TAPS)));
    }

    /** Saves the tap trigger switch and the text of the taps field; anything that is not a number gives the default. */
    static void setTap(Context c, boolean on, String countText) {
        int taps = DEFAULT_TAPS;
        try {
            taps = Integer.parseInt(countText.trim());
        } catch (NumberFormatException ignored) {
            // keeps the default
        }
        taps = Math.max(MIN_TAPS, Math.min(MAX_TAPS, taps));
        sp(c).edit().putBoolean("tap_trigger", on).putInt("tap_count", taps).apply();
    }

    /** The API route that last worked with the saved key and model ("" = not known yet). */
    static String route(Context c) {
        return sp(c).getString("route", "");
    }

    static void setRoute(Context c, String route) {
        sp(c).edit().putString("route", route).apply();
    }

    /**
     * Models that cannot answer right now (daily quota used up, too slow, overloaded ...) are remembered for a
     * while, so the next key press goes straight to the next model. Stored as
     * "model|untilMillis|reason;model|untilMillis|reason".
     */
    static long skipUntil(Context c, String model) {
        String[] entry = findSkip(c, model);
        return entry == null ? 0 : Long.parseLong(entry[1]);
    }

    /** Why the model is being skipped ("" if it is not or the reason is not known). */
    static String skipReason(Context c, String model) {
        String[] entry = findSkip(c, model);
        return entry == null ? "" : entry[2];
    }

    static void setSkipUntil(Context c, String model, long untilMillis) {
        writeSkips(c, model, untilMillis, "");
    }

    static void setSkipUntil(Context c, String model, long untilMillis, String reason) {
        writeSkips(c, model, untilMillis, reason);
    }

    static void clearSkip(Context c, String model) {
        writeSkips(c, model, 0, "");
    }

    /** {model, untilMillis, reason} of one stored entry, or null if the text is not a valid entry. */
    private static String[] parseSkip(String raw) {
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 2 || parts[0].isEmpty()) {
            return null;
        }
        try {
            Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{parts[0], parts[1], parts.length > 2 ? parts[2] : ""};
    }

    private static String[] findSkip(Context c, String model) {
        for (String raw : sp(c).getString("skip", "").split(";")) {
            String[] entry = parseSkip(raw);
            if (entry != null && entry[0].equals(model)) {
                return entry;
            }
        }
        return null;
    }

    /** Rewrites the skip list: drops expired entries, replaces the entry of {@code model} (0 = remove it). */
    private static void writeSkips(Context c, String model, long untilMillis, String reason) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (String raw : sp(c).getString("skip", "").split(";")) {
            String[] entry = parseSkip(raw);
            if (entry != null && !entry[0].equals(model) && Long.parseLong(entry[1]) > now) {
                sb.append(entry[0]).append('|').append(entry[1]).append('|').append(entry[2]).append(';');
            }
        }
        if (untilMillis > now) {
            String clean = reason.replace(';', ',').replace('|', '/').replace('\n', ' ').trim();
            if (clean.length() > 120) {
                clean = clean.substring(0, 120);
            }
            sb.append(model).append('|').append(untilMillis).append('|').append(clean);
        }
        sp(c).edit().putString("skip", sb.toString()).apply();
    }

    static void save(Context c, String apiKey, String model, String fallbacks, String thinking, boolean active) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        boolean keyChanged = !apiKey.trim().equals(p.getString("api_key", ""));
        if (keyChanged || !model.trim().equals(p.getString("model", ""))) {
            e.remove("route"); // a different key or model: find the working route again
        }
        if (keyChanged) {
            e.remove("skip"); // another key may belong to another project with its own quota
        }
        e.putString("api_key", apiKey.trim())
                .putString("model", model.trim())
                .putString("fallbacks", fallbacks.trim())
                .putString("thinking", thinking.trim())
                .putBoolean("active", active)
                .apply();
    }

    static int unreadErrors(Context c) {
        return sp(c).getInt("unread_errors", 0);
    }

    static void addUnreadError(Context c) {
        SharedPreferences p = sp(c);
        p.edit().putInt("unread_errors", p.getInt("unread_errors", 0) + 1).apply();
    }

    static void clearUnread(Context c) {
        sp(c).edit().putInt("unread_errors", 0).apply();
    }
}
