package com.ahmed.volumesolver;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent in-app log. Everything the app does, and every error, is written here
 * (and to Logcat) instead of being shown on screen.
 */
final class Logs {

    interface Listener {
        void onLogChanged();
    }

    private static final String FILE_NAME = "volume_solver.log";
    private static final int MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();
    private static volatile Listener listener;

    private Logs() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static void i(Context c, String tag, String message) {
        write(c, 'I', tag, message, null);
    }

    static void e(Context c, String tag, String message, Throwable error) {
        write(c, 'E', tag, message, error);
    }

    static String read(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists()) {
                return "";
            }
            try {
                return new String(readBytes(f), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                return "Could not read the log file: " + ex;
            }
        }
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            file(c).delete();
        }
        Prefs.clearUnread(c);
        notifyChanged();
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    private static void write(Context c, char level, String tag, String message, Throwable error) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()))
                .append(' ').append(level).append('/').append(tag).append(": ").append(message).append('\n');
        if (error != null) {
            String trace = Log.getStackTraceString(error);
            sb.append(trace);
            if (!trace.endsWith("\n")) {
                sb.append('\n');
            }
            Log.e("VolumeSolver", tag + ": " + message, error);
        } else if (level == 'E') {
            Log.e("VolumeSolver", tag + ": " + message);
        } else {
            Log.i("VolumeSolver", tag + ": " + message);
        }

        synchronized (LOCK) {
            try {
                File f = file(c);
                if (f.length() > MAX_BYTES) {
                    trim(f);
                }
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // nothing sensible to do if the log itself cannot be written
            }
        }
        if (level == 'E') {
            Prefs.addUnreadError(c);
        }
        notifyChanged();
    }

    /** Keeps the newest half of the file, cut at a line boundary. */
    private static void trim(File f) throws IOException {
        byte[] all = readBytes(f);
        int start = all.length / 2;
        while (start < all.length && all[start] != '\n') {
            start++;
        }
        start = Math.min(start + 1, all.length);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(all, start, all.length - start);
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void notifyChanged() {
        Listener l = listener;
        if (l != null) {
            l.onLogChanged();
        }
    }
}
