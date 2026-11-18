# 📌 PINotes - GitHub CI/CD, APK Releases & Web Hosting

This repository includes two automated **GitHub Actions** pipelines:
1. **Android APK Release** (`.github/workflows/build-and-release-apk.yml`) — compiles the Android app and publishes an installable APK to **GitHub Releases**.
2. **Web App Deployment** (`.github/workflows/deploy-web.yml`) — builds the cross-device PINotes Web dashboard (`web/`) and publishes it live on **GitHub Pages**.

---

## 1. Pushing Code from AI Studio to GitHub

In Google AI Studio:
1. Open the project options or settings menu in the top bar.
2. Select **Push to GitHub** (or **Export to GitHub**).
3. Connect your GitHub account and select your repository.

---

## 2. PINotes Web App (GitHub Pages Setup — One-Time)

Once pushed to GitHub, enable GitHub Pages once:
1. Open your repository on GitHub → **Settings** → **Pages**.
2. Under **Build and deployment → Source**, select **GitHub Actions**.
3. Go to the **Actions** tab → **Deploy PINotes Web to GitHub Pages** → **Run workflow** (or push any change under `web/`).
4. Once complete, your web app is live at:
   `https://<your-github-username>.github.io/<your-repo-name>/`

### Allow Browser Sign-In in Firebase (One-Time)
To sign in from your GitHub Pages website:
1. Open the **Firebase Console** for the project you use with PINotes.
2. Go to **Authentication** → **Settings** → **Authorized domains** → **Add domain**.
3. Add `<your-github-username>.github.io`.
4. First time setup: switch to **Create Account** (if new) or expand Firebase config on **Sign In**, enter your Firebase config once, and sign in. Your browser remembers your Firebase config per email so subsequent **Sign In** attempts only ask for Email & Password.
5. **Signing in on another device (phone/tablet) without re-typing Firebase config:**
   - **Option A (Quick-Login Link or Account Code):** In PINotes Web **Settings** → click **Copy Quick-Login Link** (open it on your phone/laptop) or **Copy Account Code** (`PINOTES1:...`) and paste it once on the Sign In screen.
   - **Option B (Zero-Prompt Across All Devices — Recommended):** In GitHub repo → **Settings → Secrets and variables → Actions → Variables**, add `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_APP_ID`, and `VITE_FIREBASE_STORAGE_BUCKET` (or `VITE_REGISTRY_FIREBASE_*` for multi-project directory discovery) so every brand-new device signs in immediately with only Email & Password.
6. **Web AI Analysis API Key:** Open **Settings** on the Web dashboard → **AI API Key Config (OpenRouter / Groq)** to save a browser-local API key (`sk-or-...` or `gsk_...`) and optionally customize the AI system prompt independently of your Android app.

---

## 3. Android APK Releases

Every time Android code changes are pushed to `main` or `master`:
1. GitHub Actions compiles `:app:assembleDebug` using Java 21 + Gradle 9.3.1.
2. Tags the release as **`PINotes-v<version_number>.<build_number>`**.
3. Attaches **`PINotes-v<version_number>.apk`** directly under the repository's **Releases** tab for direct download and installation on Android devices.
