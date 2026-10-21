package com.ahmed.volumesolver;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows the persistent log. This is the only place errors are ever displayed. */
public class LogActivity extends Activity implements Logs.Listener {

    private static final int MAX_SHOWN_CHARS = 60000;

    private TextView logView;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Log");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button("Copy all", v -> copyAll()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(button("Clear", v -> {
            Logs.clear(this);
            refresh();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logs.setListener(this);
        Prefs.clearUnread(this);
        refresh();
    }

    @Override
    protected void onPause() {
        Logs.setListener(null);
        super.onPause();
    }

    @Override
    public void onLogChanged() {
        runOnUiThread(() -> {
            Prefs.clearUnread(this);
            refresh();
        });
    }

    private void refresh() {
        String text = Logs.read(this);
        if (text.isEmpty()) {
            text = "(log is empty)";
        } else if (text.length() > MAX_SHOWN_CHARS) {
            text = "... older entries hidden, use Copy all to get everything ...\n"
                    + text.substring(text.length() - MAX_SHOWN_CHARS);
        }
        logView.setText(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Volume Solver log", Logs.read(this)));
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
