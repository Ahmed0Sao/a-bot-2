package com.ahmed.volumesolver;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;
    private TextView testOutput;
    private EditText keyField;
    private EditText modelField;
    private EditText fallbackField;
    private EditText thinkingField;
    private Switch activeSwitch;
    private Button logButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(32));

        TextView title = new TextView(this);
        title.setText("Volume Solver");
        title.setTextSize(26);
        add(col, title, 0);

        TextView intro = new TextView(this);
        intro.setText("Press Volume Down on a screen with a multiple-choice question. The app screenshots it, "
                + "asks Gemini for the correct option and taps it. Nothing is shown on screen when you press "
                + "the button; all progress and every error go to the log (button at the bottom).");
        add(col, intro, 8);

        status = new TextView(this);
        status.setTextSize(16);
        add(col, status, 16);

        add(col, button("Open Accessibility settings",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), 8);

        TextView restricted = new TextView(this);
        restricted.setText("If the switch is greyed out (sideloaded app): App info > three-dot menu > "
                + "Allow restricted settings, then try again.");
        add(col, restricted, 8);
        add(col, button("Open App info", v -> openAppInfo()), 4);

        add(col, label("Gemini API key"), 20);
        keyField = new EditText(this);
        keyField.setSingleLine(true);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setText(Prefs.apiKey(this));
        add(col, keyField, 0);

        add(col, label("Model"), 12);
        modelField = new EditText(this);
        modelField.setSingleLine(true);
        modelField.setText(Prefs.model(this));
        add(col, modelField, 0);

        add(col, label("Fallback models, tried in this order when the model above has used up its free quota "
                + "(separate with commas; empty = none)"), 12);
        fallbackField = new EditText(this);
        fallbackField.setMinLines(2);
        fallbackField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        fallbackField.setText(Prefs.fallbacks(this));
        add(col, fallbackField, 0);

        add(col, label("Thinking level (optional: minimal, low, medium or high; empty = model default)"), 12);
        thinkingField = new EditText(this);
        thinkingField.setSingleLine(true);
        thinkingField.setText(Prefs.thinking(this));
        add(col, thinkingField, 0);

        activeSwitch = new Switch(this);
        activeSwitch.setText("Volume Down runs the solver");
        activeSwitch.setChecked(Prefs.active(this));
        activeSwitch.setOnCheckedChangeListener((view, on) -> save());
        add(col, activeSwitch, 16);

        add(col, button("Test API key", v -> runTest()), 12);
        testOutput = new TextView(this);
        testOutput.setTextIsSelectable(true);
        add(col, testOutput, 4);

        logButton = button("Open log", v -> startActivity(new Intent(this, LogActivity.class)));
        add(col, logButton, 20);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(isServiceEnabled()
                ? "Accessibility service: ON"
                : "Accessibility service: OFF - turn on \"Volume Solver\" in Accessibility settings");
        int unread = Prefs.unreadErrors(this);
        logButton.setText(unread > 0
                ? "Open log (" + unread + " new error" + (unread == 1 ? "" : "s") + ")"
                : "Open log");
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    private void save() {
        Prefs.save(this,
                keyField.getText().toString(),
                modelField.getText().toString(),
                fallbackField.getText().toString(),
                thinkingField.getText().toString(),
                activeSwitch.isChecked());
    }

    private void runTest() {
        save();
        final Context ctx = getApplicationContext();
        final String key = Prefs.apiKey(ctx);
        final String model = Prefs.model(ctx);
        if (key.isEmpty()) {
            testOutput.setText("Paste your API key first.");
            return;
        }
        testOutput.setText("Testing...");
        new Thread(() -> {
            String out;
            try {
                GeminiClient.Reply r = GeminiClient.ping(ctx, key, model);
                String route = Prefs.route(ctx);
                out = "OK. " + r.model + " replied: " + r.text + " (route: " + route + ")";
                if (!r.notes.isEmpty()) {
                    out += "\nNot used:\n" + r.notes;
                }
                Logs.i(ctx, "Test", "API key test OK. " + out);
            } catch (Throwable t) {
                Logs.e(ctx, "Test", "API key test failed for model " + model, t);
                out = "Failed (full details in the log): " + shorten(t);
            }
            final String text = out;
            runOnUiThread(() -> testOutput.setText(text));
        }).start();
    }

    private void openAppInfo() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName me = new ComponentName(this, SolverService.class);
        return enabled.contains(me.flattenToString()) || enabled.contains(me.flattenToShortString());
    }

    private static String shorten(Throwable t) {
        String m = t.getMessage();
        if (m == null) {
            m = t.getClass().getName();
        }
        return m.length() > 600 ? m.substring(0, 600) + "..." : m;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        return t;
    }

    private void add(LinearLayout col, View view, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        col.addView(view, lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
