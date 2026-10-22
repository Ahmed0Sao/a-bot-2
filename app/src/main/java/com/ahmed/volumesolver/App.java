package com.ahmed.volumesolver;

import android.app.Application;

/** Writes any crash to the in-app log before the process dies. */
public class App extends Application {

    /** How many of this app's own screens are in front. The tap trigger does not start the solver while it is above 0. */
    private static volatile int screensOpen;

    /** Call from onResume of every activity (main thread). */
    static void screenOpened() {
        screensOpen++;
    }

    /** Call from onPause of every activity (main thread). */
    static void screenClosed() {
        if (screensOpen > 0) {
            screensOpen--;
        }
    }

    static int screensOpen() {
        return screensOpen;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Logs.e(this, "Crash", "Uncaught exception in thread " + thread.getName(), error);
            } catch (Throwable ignored) {
                // never let logging hide the original crash
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }
}
