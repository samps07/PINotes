# PinNotes - GitHub CI/CD & APK Release Setup

This repository is configured with an automated **GitHub Actions CI/CD Pipeline** (`.github/workflows/build-and-release-apk.yml`) that builds an installable Android APK and creates a **GitHub Release** on every push.

---

## 1. Pushing Code from AI Studio to GitHub

In Google AI Studio:
1. Open the project options or settings menu in the top bar.
2. Select **Push to GitHub** (or **Export to GitHub**).
3. Connect your GitHub account and select or create the target repository.
4. Whenever you push changes or sync your code from AI Studio, the GitHub Actions workflow will automatically run.

---

## 2. What Happens Automatically on Push

Every time changes are pushed to `main`, `master`, or triggered manually via **Run workflow**:
1. **GitHub Actions Runner** spins up with **Ubuntu + Java 21**.
2. Restores `.env` configuration and the debug signing keystore automatically.
3. Compiles the project using Gradle (`:app:assembleDebug`).
4. Generates a standalone, installable Android APK: `PinNotes-debug-v<build_number>.apk`.
5. Publishes a new **GitHub Release** under the repository's **Releases** tab with the APK attached.
6. Uploads the APK to the workflow run artifacts (available for 30 days).

---

## 3. Downloading and Installing the APK on Android

1. On your Android phone or tablet, open your GitHub repository in the browser.
2. Go to the **Releases** tab on the right side of the repository page (or under `https://github.com/<username>/<repo>/releases`).
3. Under the latest release (e.g., `PinNotes v1.0 (Build #...)`), tap on `PinNotes-debug-v...apk` under **Assets** to download it.
4. When prompted by Android, allow your browser or file manager to **Install unknown apps** in device settings.
5. Tap **Install** to open and use PinNotes on your device.

---

## 4. Manual Trigger

You can also trigger a fresh build without pushing code:
1. Navigate to the **Actions** tab in your GitHub repository.
2. Select **Build and Release Android APK** from the left sidebar.
3. Click **Run workflow** -> Select branch -> **Run workflow**.
