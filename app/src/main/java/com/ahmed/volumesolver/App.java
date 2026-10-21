package com.ahmed.volumesolver;

import android.app.Application;

/** Writes any crash to the in-app log before the process dies. */
public class App extends Application {

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
