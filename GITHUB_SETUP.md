# 📌 PINotes - GitHub CI/CD & APK Release Setup

This repository is configured with an automated **GitHub Actions CI/CD Pipeline** (`.github/workflows/build-and-release-apk.yml`) that builds an installable Android APK and publishes a **GitHub Release** on every push.

---

## 1. Pushing Code from AI Studio to GitHub

In Google AI Studio:
1. Open the project options or settings menu in the top bar.
2. Select **Push to GitHub** (or **Export to GitHub**).
3. Connect your GitHub account and select your repository.
4. Whenever changes are pushed from AI Studio, GitHub Actions will trigger automatically.

---

## 2. Automated Build & Release Features

Every time code is pushed to `main`, `master`, or triggered manually via **Run workflow**:
1. **GitHub Actions Runner** spins up on **Ubuntu + Java 21**.
2. **Keystore Guarantee**: Ensures a valid debug keystore is decoded from base64 or generated via `keytool` so signing validation always passes.
3. **Environment Setup**: Restores `.env` from `.env.example` so the Secrets Gradle Plugin builds without error.
4. **Build**: Compiles the APK via Gradle (`:app:assembleDebug`).
5. **Release Tagging**: Tags the release as **`PINotes-v<version_number>.<build_number>`** (e.g. `PINotes-v1.0.2`).
6. **Release Assets**: Attaches **`PINotes-v<version_number>.apk`** directly to the GitHub Release.
7. **Artifact Backup**: Stores a workflow artifact copy for 30 days.

---

## 3. Downloading and Installing on Android

1. Open your GitHub repository on your Android phone or tablet.
2. Go to the **Releases** section on the repository homepage (or navigate to `https://github.com/<username>/<repo>/releases`).
3. Under the latest release (e.g., **`PINotes v1.0 (Build #2)`**), tap **`PINotes-v1.0.apk`** to download it.
4. When prompted by Android, grant permission to **Install unknown apps** for your browser.
5. Tap **Install** to open and use PINotes.
