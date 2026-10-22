package com.ahmed.volumesolver;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
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
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQUEST_PICK_FILES = 41;

    private TextView status;
    private TextView testOutput;
    private TextView referenceStatus;
    private LinearLayout fileList;
    private EditText keyField;
    private EditText modelField;
    private EditText fallbackField;
    private EditText thinkingField;
    private EditText waitField;
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

        add(col, label("Thinking level: minimal, low, medium or high. Lower is faster, \"low\" is a good "
                + "balance. Empty = the model's own default, which is slower. (gemini-3.8-flash has no "
                + "\"minimal\"; the app uses \"low\" for it.)"), 12);
        thinkingField = new EditText(this);
        thinkingField.setSingleLine(true);
        thinkingField.setText(Prefs.thinking(this));
        add(col, thinkingField, 0);

        add(col, label("Ask the next model after this many seconds (" + Prefs.MIN_WAIT_SECONDS + " to "
                + Prefs.MAX_WAIT_SECONDS + "). If a model has not answered by then, the next model in the list is "
                + "asked as well. The first answer is used and the other request is cancelled; a model that lost "
                + "because it was slow is skipped for 30 minutes. A shorter time means faster answers when a model "
                + "is slow, but uses more of your free requests."), 12);
        waitField = new EditText(this);
        waitField.setSingleLine(true);
        waitField.setInputType(InputType.TYPE_CLASS_NUMBER);
        waitField.setText(String.valueOf(Prefs.waitSeconds(this)));
        add(col, waitField, 0);

        add(col, label("Reference files (optional). Attach your study material (PDF, text, Word, PowerPoint or "
                + "image), up to " + Reference.MAX_FILES + " files, and every question is answered from them, "
                + "because small models are not reliable on their own. You can pick several files at once. With no "
                + "file attached the model answers from its own knowledge."), 20);
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.addView(button("Attach files", v -> pickFiles()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fileRow.addView(button("Remove all", v -> removeAllFiles()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(col, fileRow, 4);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        add(col, fileList, 4);
        add(col, button("Check the files with Gemini (uses 1 request)", v -> checkFiles()), 4);
        referenceStatus = new TextView(this);
        referenceStatus.setTextIsSelectable(true);
        add(col, referenceStatus, 4);
        showReference(null);

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
        Prefs.setWait(this, waitField.getText().toString());
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
                long started = SystemClock.elapsedRealtime();
                GeminiClient.Reply r = GeminiClient.ping(ctx, key, model);
                long took = SystemClock.elapsedRealtime() - started;
                String route = Prefs.route(ctx);
                out = "OK. " + r.model + " replied: " + r.text + " in " + took + " ms (route: " + route + ")";
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

    private static String checkAttachedFiles(Context ctx, String key, String model, List<Reference.Info> attached) {
        try {
            GeminiClient.Reply r = GeminiClient.checkReference(ctx, key, model);
            Logs.i(ctx, "Test", "Reference files check OK. " + r.model + " read " + Reference.describe(ctx)
                    + " and quoted:\n" + r.text);
            return "Check OK. " + r.model + " read " + attached.size() + (attached.size() == 1 ? " file" : " files")
                    + " and quoted:\n" + r.text;
        } catch (Throwable t) {
            Logs.e(ctx, "Test", "Reference files check failed for " + Reference.describe(ctx), t);
            return "Check failed (full details in the log): " + shorten(t);
        }
    }

    /** The attached files, one row each with its own Remove button, and {@code message} (if any) under them. */
    private void showReference(String message) {
        fileList.removeAllViews();
        List<Reference.Info> files = Reference.list(this);
        for (final Reference.Info info : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(info.describe());
            name.setTextIsSelectable(true);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(button("Remove", v -> removeFile(info)), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            add(fileList, row, 2);
        }
        String summary = files.isEmpty()
                ? "No file attached: questions are answered from the model's own knowledge."
                : files.size() + (files.size() == 1 ? " file is" : " files are") + " attached and used for every "
                + "question.";
        referenceStatus.setText(message == null ? summary : summary + "\n" + message);
    }

    /** Sends the attached files with a tiny question, so a problem with them shows up now and not during the exam. */
    private void checkFiles() {
        save();
        final Context ctx = getApplicationContext();
        final List<Reference.Info> attached = Reference.list(ctx);
        final String key = Prefs.apiKey(ctx);
        final String model = Prefs.model(ctx);
        if (attached.isEmpty()) {
            showReference("Attach a file first.");
            return;
        }
        if (key.isEmpty()) {
            showReference("Paste your API key first.");
            return;
        }
        showReference("Checking with Gemini...");
        new Thread(() -> {
            final String result = checkAttachedFiles(ctx, key, model, attached);
            runOnUiThread(() -> {
                showReference(result);
                refreshStatus();
            });
        }).start();
    }

    private void pickFiles() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, REQUEST_PICK_FILES);
        } catch (Throwable t) {
            Logs.e(this, "Reference", "Could not open the file picker", t);
            showReference("Could not open the file picker (details in the log): " + shorten(t));
        }
    }

    private void removeFile(Reference.Info info) {
        Reference.remove(this, info.id);
        showReference(info.name + " removed.");
    }

    private void removeAllFiles() {
        Reference.removeAll(this);
        showReference(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILES || resultCode != RESULT_OK || data == null) {
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        final Context ctx = getApplicationContext();
        showReference("Reading " + uris.size() + (uris.size() == 1 ? " file..." : " files..."));
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            int failed = 0;
            for (Uri uri : uris) {
                Meta meta = meta(ctx.getContentResolver(), uri);
                try {
                    out.append("Attached: ").append(readAndAttach(ctx, uri, meta).describe()).append('\n');
                } catch (Throwable t) {
                    failed++;
                    Logs.e(ctx, "Reference", "Could not attach " + meta.name, t);
                    out.append("Could not attach ").append(meta.name).append(": ").append(shorten(t)).append('\n');
                }
            }
            if (failed > 0) {
                out.append("(Full details of the ").append(failed == 1 ? "failure are" : "failures are")
                        .append(" in the log.)");
            }
            final String text = out.toString().trim();
            runOnUiThread(() -> {
                showReference(text);
                refreshStatus(); // the unread-errors counter on the log button may have changed
            });
        }).start();
    }

    /** What the picker knows about a file. */
    private static final class Meta {
        String name = "attachment";
        /** -1 if the picker does not say. */
        long size = -1;
    }

    private static Meta meta(ContentResolver cr, Uri uri) {
        Meta m = new Meta();
        String last = uri.getLastPathSegment();
        if (last != null) {
            m.name = last;
        }
        try (Cursor cursor = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    m.name = cursor.getString(nameColumn);
                }
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    m.size = cursor.getLong(sizeColumn);
                }
            }
        } catch (RuntimeException ignored) {
            // the name from the Uri is used, and the size is found out while reading
        }
        return m;
    }

    private static Reference.Info readAndAttach(Context ctx, Uri uri, Meta meta) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        if (meta.size > Reference.MAX_FILE_BYTES) {
            throw new IOException("The file is " + Reference.readableSize(meta.size) + ", which is more than this app "
                    + "reads (" + Reference.readableSize(Reference.MAX_FILE_BYTES) + ").");
        }
        byte[] data;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("The file could not be opened.");
            }
            data = Reference.readLimited(in, Reference.MAX_FILE_BYTES);
        }
        return Reference.attach(ctx, meta.name, cr.getType(uri), data);
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
