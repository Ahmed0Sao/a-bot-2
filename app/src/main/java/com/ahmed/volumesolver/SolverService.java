package com.ahmed.volumesolver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for Volume Down (and, when switched on, several quick taps on the screen), screenshots the display, asks
 * Gemini for the correct answer and taps it. Nothing is ever shown on screen: all progress and errors go to
 * {@link Logs}.
 */
public class SolverService extends AccessibilityService {

    private static final String TAG = "Service";

    /** Taps this far apart (in dp) count as "the same place" when Android reports positions (it does not for other apps). */
    private static final float SAME_SPOT_DP = 64f;
    /** After a run the tap trigger ignores touches for this long, so the answer tap itself is never counted. */
    private static final long QUIET_AFTER_RUN_MS = 1500;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long quietUntil;

    private TapDetector taps;
    private View touchWatcher;
    private boolean firstTouchLogged;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Logs.i(this, TAG, "Service connected. model=" + Prefs.model(this)
                + ", fallbacks=[" + Prefs.fallbacks(this) + "]"
                + ", reference files=" + Reference.describe(this)
                + ", active=" + Prefs.active(this)
                + ", tap trigger=" + (Prefs.tapTrigger(this) ? Prefs.tapCount(this) + " quick taps" : "off")
                + ", API key " + (Prefs.apiKey(this).isEmpty() ? "NOT set" : "set"));
        try {
            taps = new TapDetector(SAME_SPOT_DP * getResources().getDisplayMetrics().density, Prefs.tapCount(this));
            // The preference store keeps only a weak reference to the listener, so it is kept in a field.
            prefListener = (prefs, key) -> {
                if ("tap_trigger".equals(key)) {
                    syncTouchWatcher();
                }
            };
            Prefs.store(this).registerOnSharedPreferenceChangeListener(prefListener);
            syncTouchWatcher();
        } catch (Throwable t) {
            Logs.e(this, TAG, "Could not set up the tap trigger. Volume Down still works.", t);
        }
    }

    /**
     * Screen events that show a series of touches is not a plain series of taps (scrolling, typing, a long press) or
     * that the taps hit different buttons. Nothing from the screen is read or stored.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        TapDetector detector = taps;
        if (event == null || detector == null || detector.count() == 0) {
            return;
        }
        try {
            long when = event.getEventTime();
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                    detector.onClick(when, elementId(event));
                    break;
                case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED:
                    detector.onLongPress(when);
                    break;
                case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                    detector.onScroll(when);
                    break;
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    detector.onTyping(when);
                    break;
                default:
                    return;
            }
            logTapNote();
        } catch (Throwable t) {
            Logs.e(this, TAG, "Tap trigger: error while handling a screen event", t);
        }
    }

    /** What was clicked, as a number: a hash of its class, description and text (never stored or logged). */
    private static int elementId(AccessibilityEvent event) {
        int id = 17;
        id = 31 * id + String.valueOf(event.getClassName()).hashCode();
        id = 31 * id + String.valueOf(event.getContentDescription()).hashCode();
        List<CharSequence> text = event.getText();
        if (text != null) {
            for (CharSequence part : text) {
                id = 31 * id + String.valueOf(part).hashCode();
            }
        }
        return id;
    }

    @Override
    public void onInterrupt() {
        // not used
    }

    @Override
    public void onDestroy() {
        try {
            if (prefListener != null) {
                Prefs.store(this).unregisterOnSharedPreferenceChangeListener(prefListener);
            }
            removeTouchWatcher(true);
        } catch (Throwable t) {
            Logs.e(this, TAG, "Error while shutting the tap trigger down", t);
        }
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
            start("Volume Down", "Volume Down pressed");
        }
        // Swallow both DOWN and UP so the system volume does not change.
        return true;
    }

    // ---- tap trigger ----------------------------------------------------------------------------------------

    /** Adds or removes the invisible overlay so that it matches the "tap trigger" setting. Main thread only. */
    private void syncTouchWatcher() {
        boolean wanted = Prefs.tapTrigger(this);
        if (wanted && touchWatcher == null) {
            addTouchWatcher();
        } else if (!wanted && touchWatcher != null) {
            removeTouchWatcher(false);
            Logs.i(this, TAG, "Tap trigger switched off: the invisible window was removed.");
        }
    }

    /**
     * Adds a 1x1 pixel window that is not drawn and does not take touches, but is told when a finger comes down
     * anywhere else on the screen (FLAG_WATCH_OUTSIDE_TOUCH). Android reports those touches without a position when
     * they belong to another app, so only their timing is used.
     */
    private void addTouchWatcher() {
        try {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            View view = new View(this);
            view.setOnTouchListener((v, event) -> {
                onScreenTouch(event);
                return false;
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.setTitle("lower volume tap counter");
            windowManager.addView(view, params);
            touchWatcher = view;
            firstTouchLogged = false;
            Logs.i(this, TAG, "Tap trigger on: " + Prefs.tapCount(this) + " quick taps (each within "
                    + TapDetector.MAX_GAP_MS + " ms of the one before) start the same routine as Volume Down. "
                    + "An invisible 1x1 pixel window counts the touches. Android does not say where touches on other "
                    + "apps are, so the spot is not checked; the first touch it sees is logged below.");
        } catch (Throwable t) {
            Logs.e(this, TAG, "Could not add the invisible window that counts taps. The tap trigger will not work "
                    + "(Volume Down still does).", t);
        }
    }

    /** @param shuttingDown true when the service is being stopped: the system may already have removed the window */
    private void removeTouchWatcher(boolean shuttingDown) {
        View view = touchWatcher;
        touchWatcher = null;
        main.removeCallbacks(settleCheck);
        if (taps != null) {
            taps.reset();
        }
        if (view == null) {
            return;
        }
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(view);
        } catch (Throwable t) {
            if (shuttingDown) {
                Logs.i(this, TAG, "The invisible tap-counting window was already gone when the service stopped ("
                        + t + ")");
            } else {
                Logs.e(this, TAG, "Could not remove the invisible tap-counting window", t);
            }
        }
    }

    /** A finger came down somewhere else on the screen (called on the main thread by the invisible window). */
    private void onScreenTouch(MotionEvent event) {
        try {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) {
                return;
            }
            boolean ownScreen = App.screensOpen() > 0;
            float x = event.getRawX();
            float y = event.getRawY();
            if (!firstTouchLogged && !ownScreen) {
                firstTouchLogged = true;
                boolean hidden = x == 0f && y == 0f;
                Logs.i(this, TAG, "Tap trigger: the invisible window received its first touch (position reported: x="
                        + x + ", y=" + y + "). " + (hidden
                        ? "Android hides where you touch other apps, so only the timing of the taps is used."
                        : "Android gave a position, so taps more than " + SAME_SPOT_DP + " dp apart count as "
                        + "different places."));
            }
            TapDetector detector = taps;
            long now = SystemClock.uptimeMillis();
            if (detector == null || !Prefs.tapTrigger(this) || busy.get() || now < quietUntil) {
                return;
            }
            detector.setNeeded(Prefs.tapCount(this));
            detector.onTap(event.getEventTime(), x, y);
            logTapNote();
            long wait = detector.waitMs(now);
            if (wait > 0) {
                main.removeCallbacks(settleCheck);
                main.postDelayed(settleCheck, wait);
            }
        } catch (Throwable t) {
            Logs.e(this, TAG, "Tap trigger: error while counting a touch", t);
        }
    }

    /** Runs when the taps have been quiet for a moment: starts the solver if they make a complete series. */
    private final Runnable settleCheck = new Runnable() {
        @Override
        public void run() {
            try {
                TapDetector detector = taps;
                if (detector == null) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                TapDetector.Series series = detector.take(now);
                if (series == null) {
                    long wait = detector.waitMs(now);
                    if (wait > 0) {
                        main.postDelayed(this, wait);
                    }
                    return;
                }
                if (!Prefs.tapTrigger(SolverService.this) || busy.get()) {
                    return;
                }
                if (App.screensOpen() > 0) {
                    Logs.i(SolverService.this, TAG, "Tap trigger test: " + series.describe() + " were seen on this "
                            + "app's own screen, so nothing was started (the trigger is off while this app is open).");
                    return;
                }
                if (keyboardShowing()) {
                    Logs.i(SolverService.this, TAG, "Tap trigger ignored: " + series.describe() + " were seen but "
                            + "the keyboard is open, and typing looks like tapping. Close the keyboard and tap again.");
                    return;
                }
                start("Tap trigger", "Tap trigger: " + series.describe() + ". Starting the same routine as "
                        + "Volume Down.");
            } catch (Throwable t) {
                Logs.e(SolverService.this, TAG, "Tap trigger: error while starting the solver", t);
            }
        }
    };

    private void logTapNote() {
        TapDetector detector = taps;
        String note = detector == null ? null : detector.takeNote();
        if (note != null) {
            Logs.i(this, TAG, "Tap trigger: " + note);
        }
    }

    private boolean keyboardShowing() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Logs.e(this, TAG, "Could not check whether the keyboard is open (the tap trigger goes ahead)", t);
        }
        return false;
    }

    // ---- solver ---------------------------------------------------------------------------------------------

    /** One key press can spend up to 140 s on models; after that the busy flag is assumed to be stuck. */
    private static final long STUCK_AFTER_MS = 150000;
    private volatile long busySince;

    /**
     * Starts one run: screenshot, model, tap.
     *
     * @param name    what started it, for the "ignored" message
     * @param started the log line that says it started
     */
    private void start(String name, String started) {
        if (!busy.compareAndSet(false, true)) {
            long age = SystemClock.elapsedRealtime() - busySince;
            if (age < STUCK_AFTER_MS) {
                Logs.i(this, TAG, name + " ignored: previous request is still running (" + age + " ms)");
                return;
            }
            Logs.e(this, TAG, "The previous request never finished (" + age + " ms); starting a new one", null);
        }
        busySince = SystemClock.elapsedRealtime();
        Logs.i(this, TAG, started);
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
            final long captured = SystemClock.elapsedRealtime();
            Logs.i(this, TAG, "Screenshot " + w + "x" + h + " captured in " + (captured - startedAt) + " ms");
            final boolean blank = looksBlank(shot);
            final byte[] jpeg = toJpeg(shot);
            shot.recycle();
            final long prepared = SystemClock.elapsedRealtime();
            Logs.i(this, TAG, "Image prepared in " + (prepared - captured) + " ms (" + jpeg.length / 1024
                    + " KB JPEG). Asking the model.");

            GeminiClient.Answer answer = GeminiClient.solve(this, Prefs.apiKey(this), Prefs.model(this), jpeg);
            final long answered = SystemClock.elapsedRealtime();
            logEvidence(answer);
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
            Logs.i(this, TAG, "Timing: screenshot " + (captured - startedAt) + " ms + image " + (prepared - captured)
                    + " ms + model " + (answered - prepared) + " ms = " + (answered - startedAt)
                    + " ms from the key press to the tap");
            main.post(() -> tap(x, y));
        } catch (Throwable t) {
            fail("Request failed after " + (SystemClock.elapsedRealtime() - startedAt) + " ms", t);
        }
    }

    /**
     * With reference files attached: what the model quoted from them, which file it came from, and whether the quote
     * is really in the files.
     */
    private void logEvidence(GeminiClient.Answer answer) {
        if (answer.reference.isEmpty()) {
            return;
        }
        if (answer.evidence.isEmpty()) {
            Logs.i(this, TAG, "Reference files (" + answer.reference + "): " + answer.model + " found no passage "
                    + "that answers this question and used its own knowledge.");
            return;
        }
        String from = answer.evidenceSource.isEmpty() ? answer.reference : answer.evidenceSource;
        String check;
        if (answer.evidenceInReference == null) {
            check = "not checked: the quote is empty, or the app cannot search PDFs and images";
        } else if (answer.evidenceInReference) {
            check = "this quote is in the file";
        } else {
            check = "WARNING: this quote was NOT found in any of the attached text files, the model may have made it up";
        }
        Logs.i(this, TAG, "Evidence from " + from + ": \"" + answer.evidence + "\" (" + check + ")");
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
                    finish();
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
        finish();
        Logs.e(this, TAG, message, error);
    }

    /** The run is over (answered, failed or cancelled): allow the next one, after a short quiet time for taps. */
    private void finish() {
        quietUntil = SystemClock.uptimeMillis() + QUIET_AFTER_RUN_MS;
        busy.set(false);
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
