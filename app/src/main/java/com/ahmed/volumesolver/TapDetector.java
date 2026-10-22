package com.ahmed.volumesolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recognises "several quick taps in a row" from what Android reports to the accessibility service.
 *
 * <p>Android does not tell an app where the user touched another app: the invisible overlay only learns that a
 * finger came down (the position arrives as 0,0). So taps are counted by timing, and a series is thrown away when the
 * system reports something that is not a plain series of taps: scrolling, a long press, typing, or a click on a
 * different button. If a position is reported (only for this app's own screens), taps far apart start a new series.
 *
 * <p>Plain Java with no Android classes, so it is tested offline. All calls must come from one thread.
 */
final class TapDetector {

    /** Longest pause between two taps of one series. */
    static final long MAX_GAP_MS = 700;
    /** The screen must stay quiet this long after the last tap before a series counts as finished. */
    static final long SETTLE_MS = 350;

    /** A finished series, for the log. */
    static final class Series {
        final int taps;
        final long spanMs;
        final List<Long> gapsMs;

        Series(int taps, long spanMs, List<Long> gapsMs) {
            this.taps = taps;
            this.spanMs = spanMs;
            this.gapsMs = gapsMs;
        }

        String describe() {
            StringBuilder pauses = new StringBuilder();
            for (long gap : gapsMs) {
                if (pauses.length() > 0) {
                    pauses.append(", ");
                }
                pauses.append(gap);
            }
            return String.format(Locale.US, "%d quick taps in %.2f s (pauses between them: %s ms)",
                    taps, spanMs / 1000.0, pauses);
        }
    }

    private final float sameSpotRadius;
    private int needed;

    private int count;
    private long firstAt;
    private long lastAt;
    private final List<Long> gaps = new ArrayList<>();
    private boolean lastKnown;
    private float lastX;
    private float lastY;
    private boolean haveClick;
    private int clickId;
    private String note;

    /**
     * @param sameSpotRadius taps further apart than this (in pixels) are not "the same place"; only used when
     *                       positions are reported
     * @param needed         how many taps make a series (at least 2)
     */
    TapDetector(float sameSpotRadius, int needed) {
        this.sameSpotRadius = sameSpotRadius;
        setNeeded(needed);
    }

    void setNeeded(int needed) {
        this.needed = Math.max(2, needed);
    }

    /** Taps in the series that is being counted (0 if none). */
    int count() {
        return count;
    }

    /** A finger came down. {@code x} and {@code y} are 0,0 (or NaN) when the position is not known. */
    void onTap(long now, float x, float y) {
        boolean known = !Float.isNaN(x) && !Float.isNaN(y) && (x != 0f || y != 0f);
        long gap = 0;
        if (count > 0) {
            gap = Math.max(0, now - lastAt);
            if (gap > MAX_GAP_MS) {
                clear(); // too slow: this tap starts a new series
            } else if (known && lastKnown && Math.hypot(x - lastX, y - lastY) > sameSpotRadius) {
                drop("the taps were in different places");
            }
        }
        if (count == 0) {
            firstAt = now;
            gaps.clear();
            haveClick = false;
        } else {
            gaps.add(gap);
        }
        count++;
        lastAt = now;
        lastKnown = known;
        lastX = x;
        lastY = y;
    }

    /**
     * The screen reported a click. {@code elementId} identifies what was clicked (a hash of its class, text and
     * description), so clicks on two different buttons can be told apart.
     */
    void onClick(long now, int elementId) {
        if (!alive(now) || now < firstAt) {
            return;
        }
        if (!haveClick) {
            haveClick = true;
            clickId = elementId;
            return;
        }
        if (clickId != elementId) {
            drop("the taps landed on different buttons or items");
            // the tap that made this click is the first tap of a new series
            count = 1;
            firstAt = lastAt;
            gaps.clear();
            haveClick = true;
            clickId = elementId;
        }
    }

    void onScroll(long now) {
        breakSeries(now, "the screen reported scrolling");
    }

    void onLongPress(long now) {
        breakSeries(now, "the screen reported a long press");
    }

    void onTyping(long now) {
        breakSeries(now, "the screen reported typing");
    }

    /**
     * The finished series, if there is one: at least {@code needed} taps and a quiet {@link #SETTLE_MS} after the last
     * one. The detector starts again from zero after this returns a series.
     */
    Series take(long now) {
        if (count < needed || now - lastAt < SETTLE_MS) {
            return null;
        }
        Series series = new Series(count, Math.max(0, lastAt - firstAt), new ArrayList<>(gaps));
        clear();
        return series;
    }

    /** Milliseconds until {@link #take} could return a series, or 0 if no series is waiting for the pause. */
    long waitMs(long now) {
        if (count < needed) {
            return 0;
        }
        return Math.max(1, SETTLE_MS - (now - lastAt));
    }

    /** Why a series of two or more taps was dropped (once), or null. */
    String takeNote() {
        String text = note;
        note = null;
        return text;
    }

    void reset() {
        clear();
        note = null;
    }

    private boolean alive(long now) {
        return count > 0 && now - lastAt <= MAX_GAP_MS;
    }

    private void breakSeries(long now, String why) {
        if (alive(now)) {
            drop(why);
        }
    }

    private void drop(String why) {
        if (count >= 2) {
            note = "A series of " + count + " taps was dropped because " + why + ".";
        }
        clear();
    }

    private void clear() {
        count = 0;
        gaps.clear();
        haveClick = false;
    }
}
