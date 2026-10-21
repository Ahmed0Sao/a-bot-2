package com.ahmed.volumesolver;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int REQUEST_PICK_FILE = 41;

    private TextView status;
    private TextView testOutput;
    private TextView referenceStatus;
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

        add(col, label("Reference file (optional). Attach your study material (PDF, text, Word, PowerPoint or "
                + "image) and every question is answered from it, because small models are not reliable on their "
                + "own. With no file attached the model answers from its own knowledge."), 20);
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.addView(button("Attach file", v -> pickFile()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fileRow.addView(button("Remove file", v -> removeFile()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(col, fileRow, 4);
        add(col, button("Check the file with Gemini (uses 1 request)", v -> checkFile()), 4);
        referenceStatus = new TextView(this);
        referenceStatus.setTextIsSelectable(true);
        add(col, referenceStatus, 4);
        showReference();

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
        refreshStatus();
    }

    /** Service on/off line and the unread-errors counter on the log button. */
    private void refreshStatus() {
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

    private static String checkAttachedFile(Context ctx, String key, String model, Reference.Info attached) {
        try {
            GeminiClient.Reply r = GeminiClient.checkReference(ctx, key, model);
            Logs.i(ctx, "Test", "Reference file check OK. " + r.model + " read " + attached.describe()
                    + " and quoted: " + r.text);
            return "File check OK. " + r.model + " read " + attached.name + " and quoted: " + r.text;
        } catch (Throwable t) {
            Logs.e(ctx, "Test", "Reference file check failed for " + attached.describe(), t);
            return "File check failed (full details in the log): " + shorten(t);
        }
    }

    private void showReference() {
        Reference.Info info = Reference.info(this);
        referenceStatus.setText(info == null
                ? "No file attached: questions are answered from the model's own knowledge."
                : "Attached: " + info.describe());
    }

    /** Sends the attached file with a tiny question, so a problem with it shows up now and not during the exam. */
    private void checkFile() {
        save();
        final Context ctx = getApplicationContext();
        final Reference.Info attached = Reference.info(ctx);
        final String key = Prefs.apiKey(ctx);
        final String model = Prefs.model(ctx);
        if (attached == null) {
            referenceStatus.setText("Attach a file first.");
            return;
        }
        if (key.isEmpty()) {
            referenceStatus.setText("Attached: " + attached.describe() + "\nPaste your API key first.");
            return;
        }
        referenceStatus.setText("Attached: " + attached.describe() + "\nChecking with Gemini...");
        new Thread(() -> {
            final String result = checkAttachedFile(ctx, key, model, attached);
            runOnUiThread(() -> {
                referenceStatus.setText("Attached: " + attached.describe() + "\n" + result);
                refreshStatus();
            });
        }).start();
    }

    private void pickFile() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        try {
            startActivityForResult(pick, REQUEST_PICK_FILE);
        } catch (Throwable t) {
            Logs.e(this, "Reference", "Could not open the file picker", t);
            referenceStatus.setText("Could not open the file picker (details in the log): " + shorten(t));
        }
    }

    private void removeFile() {
        Reference.remove(this);
        showReference();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final Context ctx = getApplicationContext();
        referenceStatus.setText("Reading the file...");
        new Thread(() -> {
            String out;
            try {
                out = "Attached: " + readAndAttach(ctx, uri).describe();
            } catch (Throwable t) {
                Logs.e(ctx, "Reference", "Could not attach the file", t);
                out = "Could not attach the file (full details in the log): " + shorten(t);
            }
            final String text = out;
            runOnUiThread(() -> {
                referenceStatus.setText(text);
                refreshStatus(); // the unread-errors counter on the log button may have changed
            });
        }).start();
    }

    private static Reference.Info readAndAttach(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String name = null;
        long size = -1;
        try (Cursor cursor = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    name = cursor.getString(nameColumn);
                }
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn);
                }
            }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        if (size > Reference.MAX_FILE_BYTES) {
            throw new IOException("The file is " + Reference.readableSize(size) + ", which is more than this app "
                    + "reads (" + Reference.readableSize(Reference.MAX_FILE_BYTES) + ").");
        }
        byte[] data;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("The file could not be opened.");
            }
            data = Reference.readLimited(in, Reference.MAX_FILE_BYTES);
        }
        return Reference.attach(ctx, name, cr.getType(uri), data);
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
