# Agent Instructions — Pierre & Ebba Shared Lists PWA

## Automatic Deployment to GitHub Pages (Required)

The live app is hosted on GitHub Pages at:
https://pierre38610.github.io/pierre-ebba-lists/

GitHub Pages serves directly from the `main` branch (`/` root) of `https://github.com/Pierre38610/pierre-ebba-lists.git`.

**Whenever you modify the app** (`index.html`, `manifest.json`, `sw.js`, icons, or any other app asset), you **MUST** deploy the updated version automatically before finishing your task — **without waiting for the user to ask**:

1. **Bump the Service Worker cache version** in `sw.js`:
   - Increment `CACHE_NAME` (e.g., `'pierre-ebba-v3'` -> `'pierre-ebba-v4'`) if it has not already been incremented for the current set of changes.
   - This ensures installed PWAs detect the update, clear old caches on `activate`, and serve the latest files.
2. **Stage and commit all changes** on `main`:
   ```powershell
   git status
   git add index.html manifest.json sw.js
   git commit -m "<concise description of the changes>"
   ```
   (Include any other modified or newly added app files as needed.)
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
   - Report the live URL `https://pierre38610.github.io/pierre-ebba-lists/` in your final response.
