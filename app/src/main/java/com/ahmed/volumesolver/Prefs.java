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

    static String thinking(Context c) {
        return sp(c).getString("thinking", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    static boolean active(Context c) {
        return sp(c).getBoolean("active", true);
    }

    /** The API route that last worked with the saved key and model ("" = not known yet). */
    static String route(Context c) {
        return sp(c).getString("route", "");
    }

    static void setRoute(Context c, String route) {
        sp(c).edit().putString("route", route).apply();
    }

    /**
     * Models whose daily quota ran out are remembered until it resets, so the next key press goes straight to
     * the next model. Stored as "model|untilMillis;model|untilMillis".
     */
    static long skipUntil(Context c, String model) {
        for (String entry : sp(c).getString("skip", "").split(";")) {
            int bar = entry.lastIndexOf('|');
            if (bar > 0 && entry.substring(0, bar).equals(model)) {
                try {
                    return Long.parseLong(entry.substring(bar + 1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    static void setSkipUntil(Context c, String model, long untilMillis) {
        writeSkips(c, model, untilMillis);
    }

    static void clearSkip(Context c, String model) {
        writeSkips(c, model, 0);
    }

    /** Rewrites the skip list: drops expired entries, replaces the entry of {@code model} (0 = remove it). */
    private static void writeSkips(Context c, String model, long untilMillis) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (String entry : sp(c).getString("skip", "").split(";")) {
            int bar = entry.lastIndexOf('|');
            if (bar <= 0) {
                continue;
            }
            long until;
            try {
                until = Long.parseLong(entry.substring(bar + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!entry.substring(0, bar).equals(model) && until > now) {
                sb.append(entry).append(';');
            }
        }
        if (untilMillis > now) {
            sb.append(model).append('|').append(untilMillis);
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
