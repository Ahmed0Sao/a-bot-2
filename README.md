# lower volume

Press Volume Down, or tap the screen several times quickly -> the app screenshots the screen -> Gemini picks the correct answer option -> the app taps it.

The app is called "lower volume" on the phone (it used to be "Volume Solver"). Only the name changed: the package stays com.ahmed.volumesolver, so the new APK installs over the old one and keeps the API key, the settings and the accessibility permission.

Nothing is shown on screen when you press the button. Progress and every error (exception, stack trace, raw API response) go to the Log screen inside the app (Copy all / Clear).

## Build the APK

GitHub (nothing to install): create a repository, upload everything in this folder (including the hidden-looking .github folder and app/debug.keystore), then open the Actions tab. The "Build APK" workflow runs on every push (about 3 minutes). When it is green, download app-debug.apk from the repository's Releases page, or from the run's Artifacts.

Android Studio: File > Open this folder, wait for Gradle sync, then Build > Build Bundle(s) / APK(s) > Build APK(s).

Every build is signed with the same key (app/debug.keystore), so a new APK installs over the old one and keeps your settings and the accessibility permission. If Android says "App not installed" the first time, uninstall the copy that was signed with a different key, then install again.

## Set up on the phone

1. Install the APK (allow "install unknown apps" for the browser or file manager you used).
2. Open lower volume, paste a Gemini API key (https://aistudio.google.com/apikey) and tap Test API key. The result shows which API route worked.
3. Tap Open Accessibility settings, open lower volume and turn it on. If the switch is greyed out: App info > three-dot menu > Allow restricted settings, then try again.
4. Samsung: Settings > Apps > lower volume > Battery > Unrestricted, so the service is not stopped in the background.
5. Open a screen with a multiple-choice question and press Volume Down, or tap the screen 4 times quickly (see below).

While the service is on, Volume Down does not change the volume. Switch "Volume Down runs the solver" off in the app to get the normal volume button back.

## Tap trigger (tap the screen quickly)

Tapping the screen several times in a row starts exactly what Volume Down starts. Setting "Tapping the screen quickly runs the solver" (on by default) and "Taps needed in a row" (default 4, range 3 to 10) are in the app. Each tap must come within 0.7 s of the one before, and the solver starts after a short pause (0.35 s) once the last tap is done.

How it works and what it cannot do:

- The service adds an invisible window of 1x1 pixel that is not drawn and does not take touches. Android tells it each time a finger comes down on another window (FLAG_WATCH_OUTSIDE_TOUCH). While the setting is off the window is not there.
- **Android does not tell an app where you touched another app**: those touches arrive with the position 0,0 (this is a security rule in Android's input system). So the app cannot check that the taps are on the same spot. It counts quick taps instead, and ignores the series when the screen reports something that is not a plain series of taps: scrolling, typing, a long press, a click on a different button each time, or an open keyboard. Those reports come from the apps themselves, so an app or game that does not report them is not covered. If Android ever gives a position, taps more than 64 dp apart are treated as different places.
- Tap on an empty part of the screen (the question text, the background). The app under your finger receives the taps as well: tapping an answer option 4 times selects it 4 times.
- While a run is going on and for 1.5 s after it, taps are ignored (the answer tap that the app makes itself is not counted). While this app's own screens are open the trigger is off; 4 quick taps there only write a "Tap trigger test" line to the log, which is an easy way to check that taps are seen.
- The log shows: `Tap trigger on: ...` when the service starts, `Tap trigger: the invisible window received its first touch ...` at the first touch on another app, `Tap trigger: 4 quick taps in 0.84 s (pauses between them: ...)` when a series starts the solver, and `Tap trigger: A series of N taps was dropped because ...` when a series was thrown away. If the first-touch line never appears after you touched another app, the phone does not deliver touches to the invisible window and the tap trigger cannot work on it (Volume Down still does).
- To tell a series of taps from normal use, the service now has two more permissions in its configuration: it listens for scroll, click, long-press and typing events, and it may list the open windows (to see whether the keyboard is open). Screen text is never read, logged or stored; clicks are compared only as a number made from the button's class and label.
- Every start costs one request of your free quota, so a false start is not free. If you notice false starts, raise the number of taps or switch the setting off.

## API keys and routes

Google now issues keys that start with "AQ." (older ones start with "AIza"). The app has two ways to call Gemini:

- Interactions API (POST /v1beta/interactions), the one Google's current key documentation uses.
- generateContent (POST /v1beta/models/MODEL:generateContent), the older one.

For an "AQ." key the app tries the Interactions API first, for any other key generateContent first. If Google rejects the request (HTTP 400, 401, 403, 404...) it tries the other route, and the route that worked is remembered until you change the key or the model. Every attempt is in the log, and the Test API key button shows the route that worked.

If both routes fail, the log shows both error messages. Typical causes:

- HTTP 401 or 403 on both: the key is not accepted. Create a new key in AI Studio and paste it, or use a standard key that is restricted to the Gemini API.
- HTTP 404 mentioning the model: the model name is wrong or not available to your key. The model field is editable.
- HTTP 429: the free quota of that model is used up. The app then moves on to the next model of the fallback list (see below). The exact limits of each model are in AI Studio (aistudio.google.com/rate-limit).

## Free quota and fallback models

Free-tier limits are counted per model, so a model that has used up its daily requests does not affect the others. The app has a "Fallback models" list under the Model field. When the main model answers HTTP 429 (quota), is overloaded (5xx), or does not exist for your key (404), the next model of the list is tried automatically. The default list contains the models that Google's pricing page marks as free of charge: gemini-3.6-flash, gemini-3-flash-preview, gemini-3.5-flash-lite, gemini-3.1-flash-lite. You can edit it (comma separated, empty = no fallback).

- A model whose daily quota is used up is skipped until the quota resets (midnight Pacific time) so the next key press does not waste time on it. Short rate limits (per minute) are skipped for the delay Google reports.
- A wrong API key or a malformed request does not move on to other models, because they would fail the same way. The log shows the exact error.
- The log says which model answered and why the others were skipped. Test API key shows the same.
- If every model is used up the log lists them all with the reason, and the last HTTP response in full.
- Quotas change over time. Check what each model currently allows at aistudio.google.com/rate-limit. Linking a billing account to the project (Tier 1) removes the free-tier daily caps.

## Speed

A key press normally needs a few seconds. The log shows where the time goes, line by line:

- `HTTP 200 in 4210 ms (gemini-3.8-flash: connect 120 ms, send and wait for the model 4050 ms, download 8 ms, reply 1 KB)`: connect is the network, "send and wait" is mostly the model thinking, download is the reply coming back.
- `Tokens used by gemini-3.8-flash: ...`: what the model used, including its thinking tokens (thoughtsTokenCount or total_thought_tokens). A model that thinks for thousands of tokens is slow whatever the network does.
- `Timing: screenshot ... + image ... + model ... = ... ms from the key press to the tap`.

What the app does about slow models:

- The first model is asked at once. If it has not answered after "Ask the next model after" seconds (setting, default 10; a very large reference file gets one extra second per 100 KB above 600 KB), the next model of the list is asked as well. The first answer wins and the other request is cancelled. Giving up on the first model and starting over would throw away the work it has already done, so it keeps running while the second one is tried.
- A model that lost the race because it was slower is skipped for 30 minutes, so the next key press goes straight to a model that is fast right now. The log says so ("gemini-3.8-flash had been waiting for 10.1 s and was cancelled, skipped for 30 min").
- At most two requests run at once, and nothing new is started when less than 3 seconds are left. One key press never spends more than 75 seconds on models (a very large reference file gets more). Requests above about 4 MB (big PDFs or images) are not sent to two models at once.
- The price is free requests: when the first model is slow, the second model's daily allowance is used too. A longer setting (for example 20 seconds) uses fewer; a shorter one (3 to 5 seconds) answers sooner when a model is slow. If the first model answers within the time, no second request is made.
- A model that answers with a server error (overloaded) is skipped for 2 minutes and the next model is asked at once, instead of asking the same model again.
- Thinking level defaults to low. gemini-3.8-flash has no "minimal" level, so the app sends "low" for it; a model that rejects "minimal" is retried with "low" and not with its slower default.
- Test API key shows how long the reply took, and which models were cancelled or skipped and why.

## Reference files (Attach files)

Small models make mistakes when they answer from memory. Under "Reference files" in the app, tap Attach files and pick your study material (you can select several files at once, or attach more later). From then on every question is sent together with all the attached files, and the model is told to answer from them, not from memory. With no file attached nothing changes: the model answers from its own knowledge.

- Supported: PDF (scanned PDFs work too), text files in any encoding (UTF-8, UTF-16, Arabic windows-1256), Word (.docx), PowerPoint (.pptx), and images (PNG, JPEG, WEBP). Word and PowerPoint files are turned into plain text once, when you attach them. Old .doc/.ppt, Excel and other formats are refused with a message: export them as PDF or text first. If several files are picked and one is refused, the others are still attached and the message says which one failed and why.
- Up to 10 files. Every file has its own Remove button; Remove all clears the list. Attaching a file with the name of one that is already attached replaces it (handy for a newer version).
- Limits: PDFs and images together up to 15 MB (they are sent with every question), text files together up to 1,000,000 characters (about 250 pages), and one text file is cut after 800,000 characters (the line for the file shows when that happened). A PDF page costs about 258 tokens, so many or long PDFs use more of the per-minute token quota. The more you attach, the slower and more expensive every question is; attach what the exam covers.
- The files are stored only inside the app on the phone and are sent to Google's Gemini API with each question, like the screenshot.
- A file attached with the previous version of the app (one file) is carried over automatically.
- Check the files with Gemini sends all files with one tiny question (uses 1 request): the model quotes the first sentence of each, so a problem with a file shows up before you need it.
- The model must copy the passage it used into an "evidence" field, and with several files also name the file it came from. The log shows both. For text, Word and PowerPoint files the app checks that the quote really occurs in the attached text files and logs a WARNING when it does not (a sign the model made it up); if the model named the wrong file but the quote exists in another one, the log reports the file the quote is really in. PDFs and images cannot be searched on the phone, so a quote that is in none of the text files is reported as "not checked" when a PDF or image is attached. If the files do not cover a question, the model answers from its own knowledge and the log says so.

## Log

Open log in the app shows every step: key press, screenshot size and timing, which route was used, the model's raw reply, the tap coordinates, and any error with its full stack trace or HTTP response body. Copy all puts the whole log on the clipboard. Crashes are logged too.

## Notes

Screenshots are sent to Google's Gemini API with your own key (on the Interactions route they are sent with store=false, so no interaction history is kept). Apps that block screenshots (FLAG_SECURE) come back black; the log says so.

The API key is saved only on the phone, inside the app. It is not part of this project.

The model name is editable in the app (default gemini-3.8-flash; the fallback list is described above). Thinking level defaults to low; minimal is faster still on the models that have it, medium and high are slower and more careful, and empty means the model's own default. If the API rejects the value, the app retries once without it and the log shows what Google said.
