# Agent Instructions — Pierre & Ebba Shared Lists PWA & Android App

## 1. Automatic Deployment to GitHub Pages (Required)

The live app is hosted on GitHub Pages at:
https://pierre38610.github.io/pierre-ebba-lists/

GitHub Pages serves directly from the `main` branch (`/` root) of `https://github.com/Pierre38610/pierre-ebba-lists.git`.

**Whenever you modify the app** (`index.html`, `widget.html`, `manifest.json`, `sw.js`, `version.json`, `android/**`, icons, or any other app asset), you **MUST** deploy the updated version automatically before finishing your task — **without waiting for the user to ask**:

1. **Bump the Service Worker cache version** in `sw.js`:
   - Increment `CACHE_NAME` (e.g., `'pierre-ebba-v9'` -> `'pierre-ebba-v10'`).
   - This ensures installed PWAs and the Android WebView detect the update, clear old caches on `activate`, and serve the latest files.
2. **Stage and commit all changes** on `main`:
   ```powershell
   git status
   git add -A
   git commit -m "<concise description of the changes>"
   ```
3. **Push to `origin main`**:
   ```powershell
   git push origin main
   ```
4. **Verify GitHub Pages deployment**:
   - Check the latest Pages build status with:
     ```powershell
     gh api repos/Pierre38610/pierre-ebba-lists/pages/builds/latest
     ```
   - Confirm `"status": "built"` (or wait briefly and re-check if `"building"`) and that the deployed commit matches `git rev-parse HEAD`.
   - If `android/**` was modified, also verify the `Build Android Companion APK` workflow succeeds (`gh -R Pierre38610/pierre-ebba-lists run list --limit 3`) so the updated `pierre-ebba.apk` is published to the `android-latest` release.
   - Report the live URL `https://pierre38610.github.io/pierre-ebba-lists/` in your final response.

---

## 2. Update Popup & Versioning Checklist (Required for Every Release)

The app includes an automatic update checker (`version.json` + `#apkUpdateModal` in `index.html` + native fallback in `MainActivity.kt`) so Pierre and Ebba see a popup with a summary of what changed whenever a new version is released.

- **For Web-only updates** (`index.html`, `widget.html`, `sw.js`):
  1. Update `webVersion` in `version.json` (e.g., `"2026-11-20.3"` -> `"2026-11-23.1"`).
  2. Update `WEB_BUILD_VERSION` in `index.html` to the exact same string.
  3. Bump `CACHE_NAME` in `sw.js`.

- **For Android APK updates** (any change under `android/**`):
  1. Increment `versionCode` (e.g., `3` -> `4`) and bump `versionName` (e.g., `"1.2.0"` -> `"1.3.0"`) in `android/app/build.gradle.kts`.
  2. Update `getAppVersion()` and `CURRENT_VERSION_CODE` in `android/app/src/main/java/com/pierreebba/sharedlists/MainActivity.kt` to match.
  3. Update `version.json`:
     - Set `"apkVersion"` to the new `versionName` and `"apkVersionCode"` to the new `versionCode`.
     - Update `"webVersion"`.
     - Update `"notes"` with a short, clear list of the new features and bug fixes (displayed inside the `#apkUpdateModal` popup).
  4. Update `LATEST_APK_VERSION` and `WEB_BUILD_VERSION` in `index.html` to match `version.json`.
  5. Bump `CACHE_NAME` in `sw.js`.

---

## 3. Data Preservation Rules (Critical)

Pierre and Ebba rely on this app daily for shared lists, recipes, daily words, streaks, quizzes, voice clips, and Notes of the Day. **Updates MUST NEVER lose user data:**

1. **In-place Android upgrades**:
   - **Never change** `applicationId` (`"com.pierreebba.sharedlists"`) in `android/app/build.gradle.kts` or the CI release keystore (`ANDROID_KEYSTORE_BASE64`). Keeping both unchanged lets Android install new APKs directly over older ones without uninstalling or wiping WebView `localStorage`, `IndexedDB`, or `SharedPreferences`.
2. **Storage & Cloud Sync keys**:
   - **Never rename or delete** existing `STORAGE_KEYS` (`pe_shared_state_v2`, `pe_current_user_v1`, `pe_theme_v1`, `pe_pantry_ingredients_v1`, `pe_notif_prefs_v1`, `pe_pending_audio_v1`), IndexedDB database `pe_audio_db`, or cloud KV keys (`ebba_v2_meta`, `ebba_v2_wn_meta`, `ebba_aud_*`) without writing a backward-compatible migration.
3. **CRDT `mergeStates()` compatibility**:
   - Keep `mergeStates(local, incoming)` backward-compatible across all collections: `cards`, `recipes`, `words` (including `date` for streaks, `masteredBy`, and `audioId`/`audioDuration`/`audioBy`), `quizResults`, `petitMot`, and `partnerNotes`.
4. **Prevent empty-state cloud overwrites**:
   - Keep the `!initialCloudSyncDone && !hadLocalDataAtBoot` guard in `flushKvSave()` and `flushAllDataBeforeUpdate()` before APK downloads so a fresh/empty client never overwrites existing cloud data.
   - Keep heavy binary data (such as recorded voice clips) out of `state` JSON (`ebba_v2_meta`); store audio separately in IndexedDB + `ebba_aud_<id>_*` KV keys.
