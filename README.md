# Volume Solver

Press Volume Down -> the app screenshots the screen -> Gemini picks the correct answer option -> the app taps it.

Nothing is shown on screen when you press the button. Progress and every error (exception, stack trace, raw API response) go to the Log screen inside the app (Copy all / Clear).

## Build the APK

GitHub (nothing to install): create a repository, upload everything in this folder (including the hidden-looking .github folder and app/debug.keystore), then open the Actions tab. The "Build APK" workflow runs on every push (about 3 minutes). When it is green, download app-debug.apk from the repository's Releases page, or from the run's Artifacts.

Android Studio: File > Open this folder, wait for Gradle sync, then Build > Build Bundle(s) / APK(s) > Build APK(s).

Every build is signed with the same key (app/debug.keystore), so a new APK installs over the old one and keeps your settings and the accessibility permission. If Android says "App not installed" the first time, uninstall the copy that was signed with a different key, then install again.

## Set up on the phone

1. Install the APK (allow "install unknown apps" for the browser or file manager you used).
2. Open Volume Solver, paste a Gemini API key (https://aistudio.google.com/apikey) and tap Test API key. The result shows which API route worked.
3. Tap Open Accessibility settings, open Volume Solver and turn it on. If the switch is greyed out: App info > three-dot menu > Allow restricted settings, then try again.
4. Samsung: Settings > Apps > Volume Solver > Battery > Unrestricted, so the service is not stopped in the background.
5. Open a screen with a multiple-choice question and press Volume Down.

While the service is on, Volume Down does not change the volume. Switch "Volume Down runs the solver" off in the app to get the normal volume button back.

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

## Log

Open log in the app shows every step: key press, screenshot size and timing, which route was used, the model's raw reply, the tap coordinates, and any error with its full stack trace or HTTP response body. Copy all puts the whole log on the clipboard. Crashes are logged too.

## Notes

Screenshots are sent to Google's Gemini API with your own key (on the Interactions route they are sent with store=false, so no interaction history is kept). Apps that block screenshots (FLAG_SECURE) come back black; the log says so.

The API key is saved only on the phone, inside the app. It is not part of this project.

The model name is editable in the app (default gemini-3.8-flash; the fallback list is described above). Thinking level is optional: leave it empty for the model default, or try low (or minimal) for faster answers. If the API rejects the value, the app retries once without it and the log shows what Google said.
