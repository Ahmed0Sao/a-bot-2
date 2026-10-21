package com.ahmed.volumesolver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for Volume Down, screenshots the display, asks Gemini for the correct answer
 * and taps it. Nothing is ever shown on screen: all progress and errors go to {@link Logs}.
 */
public class SolverService extends AccessibilityService {

    private static final String TAG = "Service";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Logs.i(this, TAG, "Service connected. model=" + Prefs.model(this)
                + ", fallbacks=[" + Prefs.fallbacks(this) + "]"
                + ", active=" + Prefs.active(this)
                + ", API key " + (Prefs.apiKey(this).isEmpty() ? "NOT set" : "set"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // not used
    }

    @Override
    public void onInterrupt() {
        // not used
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        Logs.i(this, TAG, "Service destroyed");
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN || !Prefs.active(this)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            start();
        }
        // Swallow both DOWN and UP so the system volume does not change.
        return true;
    }

    private static final long STUCK_AFTER_MS = 90000;
    private volatile long busySince;

    private void start() {
        if (!busy.compareAndSet(false, true)) {
            long age = SystemClock.elapsedRealtime() - busySince;
            if (age < STUCK_AFTER_MS) {
                Logs.i(this, TAG, "Volume Down ignored: previous request is still running (" + age + " ms)");
                return;
            }
            Logs.e(this, TAG, "The previous request never finished (" + age + " ms); starting a new one", null);
        }
        busySince = SystemClock.elapsedRealtime();
        Logs.i(this, TAG, "Volume Down pressed");
        if (Prefs.apiKey(this).isEmpty()) {
            fail("No Gemini API key saved. Open the app, paste the key and tap Test API key.", null);
            return;
        }
        final long startedAt = SystemClock.elapsedRealtime();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    process(result, startedAt);
                }

                @Override
                public void onFailure(int errorCode) {
                    fail("takeScreenshot failed, errorCode=" + errorCode + " (" + screenshotError(errorCode) + ")", null);
                }
            });
        } catch (Throwable t) {
            fail("takeScreenshot threw an exception", t);
        }
    }

    private void process(AccessibilityService.ScreenshotResult result, long startedAt) {
        try {
            Bitmap shot = toSoftwareBitmap(result);
            if (shot == null) {
                fail("The screenshot came back empty", null);
                return;
            }
            final int w = shot.getWidth();
            final int h = shot.getHeight();
            Logs.i(this, TAG, "Screenshot " + w + "x" + h + " captured in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            final boolean blank = looksBlank(shot);
            final byte[] jpeg = toJpeg(shot);
            shot.recycle();

            GeminiClient.Answer answer = GeminiClient.solve(this, Prefs.apiKey(this), Prefs.model(this), jpeg);
            if (!answer.found) {
                fail(blank
                        ? "The screenshot is completely black: this app probably blocks screen capture (FLAG_SECURE)."
                        : "Gemini did not find a question with selectable answers. Raw reply: " + answer.raw, null);
                return;
            }

            double yMin = answer.yMin;
            double xMin = answer.xMin;
            double yMax = answer.yMax;
            double xMax = answer.xMax;
            if (Math.max(Math.max(yMin, xMin), Math.max(yMax, xMax)) <= 1.0) {
                // the model answered in 0..1 instead of 0..1000
                yMin *= 1000;
                xMin *= 1000;
                yMax *= 1000;
                xMax *= 1000;
            }
            final float x = clamp((float) ((xMin + xMax) / 2000.0 * w), 1f, w - 1f);
            final float y = clamp((float) ((yMin + yMax) / 2000.0 * h), 1f, h - 1f);
            Logs.i(this, TAG, "Answer \"" + answer.text + "\" from " + answer.model + " box_2d=[" + yMin + ", " + xMin + ", " + yMax + ", "
                    + xMax + "] -> tap at (" + Math.round(x) + ", " + Math.round(y) + ") on " + w + "x" + h);
            main.post(() -> tap(x, y));
        } catch (Throwable t) {
            fail("Request failed", t);
        }
    }

    private void tap(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                    .build();
            boolean accepted = dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription description) {
                    busy.set(false);
                    Logs.i(SolverService.this, TAG, "Tap completed");
                }

                @Override
                public void onCancelled(GestureDescription description) {
                    fail("The tap gesture was cancelled by the system", null);
                }
            }, null);
            if (!accepted) {
                fail("dispatchGesture returned false (does the service have the gesture capability?)", null);
            }
        } catch (Throwable t) {
            fail("Tap threw an exception", t);
        }
    }

    private void fail(String message, Throwable error) {
        busy.set(false);
        Logs.e(this, TAG, message, error);
    }

    private static Bitmap toSoftwareBitmap(AccessibilityService.ScreenshotResult result) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        try {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) {
                return null;
            }
            Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            hardware.recycle();
            return software;
        } finally {
            buffer.close();
        }
    }

    /** True if a grid of sample pixels is all black, which is what a FLAG_SECURE window looks like. */
    private static boolean looksBlank(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        for (int i = 1; i < 24; i++) {
            for (int j = 1; j < 24; j++) {
                if ((b.getPixel(w * i / 24, h * j / 24) & 0x00FFFFFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static byte[] toJpeg(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();
        float scale = Math.min(1f, 1920f / Math.max(w, h));
        Bitmap bitmap = scale < 1f
                ? Bitmap.createScaledBitmap(source, Math.round(w * scale), Math.round(h * scale), true)
                : source;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
        if (bitmap != source) {
            bitmap.recycle();
        }
        return out.toByteArray();
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String screenshotError(int code) {
        switch (code) {
            case 1:
                return "internal error";
            case 2:
                return "service has no screenshot capability";
            case 3:
                return "pressed too soon after the previous screenshot";
            case 4:
                return "invalid display";
            default:
                return "unknown";
        }
    }
}
