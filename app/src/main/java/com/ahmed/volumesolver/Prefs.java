package com.ahmed.volumesolver;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {

    static final String DEFAULT_MODEL = "gemini-3.8-flash";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("volume_solver", Context.MODE_PRIVATE);
    }

    static String apiKey(Context c) {
        return sp(c).getString("api_key", "").trim();
    }

    static String model(Context c) {
        String m = sp(c).getString("model", DEFAULT_MODEL).trim();
        return m.isEmpty() ? DEFAULT_MODEL : m;
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

    static void save(Context c, String apiKey, String model, String thinking, boolean active) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        if (!apiKey.trim().equals(p.getString("api_key", "")) || !model.trim().equals(p.getString("model", ""))) {
            e.remove("route"); // a different key or model: find the working route again
        }
        e.putString("api_key", apiKey.trim())
                .putString("model", model.trim())
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
