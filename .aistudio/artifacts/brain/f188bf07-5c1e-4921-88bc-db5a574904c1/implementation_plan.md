# Automated GitHub Actions APK Build & Release Pipeline

Automate the building and publishing of installable Android APKs directly to GitHub Releases whenever code is pushed to your GitHub repository.

## User Review & Critical Decisions

> [!IMPORTANT]
> The implementation plan incorporates your confirmed preferences:

- **Confirmed Decision 1**: Automated GitHub Release creation with attached installable APK triggered on every push.
- **Confirmed Decision 2**: Debug APK variant (`app-debug.apk`) for instant installation on Android devices without requiring keystore/signing secrets configuration in GitHub repository settings.
- **AI Studio Integration**: Pushing code from AI Studio to GitHub is performed via the AI Studio UI ("Push to GitHub" / "Export to GitHub" in the project menu). Once pushed, the GitHub Actions workflow in `.github/workflows/` automatically takes over to compile and release the APK.

---

### 1. Overview & Core Concept

- **What It Delivers**:
  - A production-ready GitHub Actions workflow (`.github/workflows/build-and-release-apk.yml`) that triggers on every push to the `main` or `master` branch (and optional release tags or manual workflow dispatches).
  - Automated JDK setup (JDK 17) and Android SDK build environment configuration on GitHub's `ubuntu-latest` runners.
  - Automatic compilation of the Android application via Gradle (`:app:assembleDebug`).
  - Automatic creation of a tagged GitHub Release (e.g. `v1.0-build-<run_number>`) with release notes containing commit information and timestamp.
  - Upload of the installable APK (`Notes-debug.apk` / `app-debug.apk`) directly to the GitHub Release assets so anyone with access to the repo can tap and install it on their Android device.
  - Secondary backup upload of the APK as a GitHub Actions build artifact retained for 30 days.

---

### 2. User Experience & Workflow

- **Developer Workflow**:
  1. Make changes in AI Studio.
  2. Push / Sync changes to your connected GitHub repository via AI Studio's GitHub integration.
  3. GitHub Actions automatically starts the `Build and Release Android APK` job in the background.
  4. Once completed (typically 1–2 minutes), navigate to the **Releases** tab of your GitHub repository.
  5. Tap the latest release and download the attached `.apk` file directly onto any Android phone or tablet to install the app.

- **Release Asset Packaging**:
  - Renamed artifact: `PinNotes-debug-v<run_number>.apk` for clear version identification.
  - Comprehensive release description containing commit hash, branch name, and direct download links.

---

### 3. Key Decisions & Technical Strategy

- **Decision 1: GitHub Actions vs. Committing Binary APKs into Git**:
  - *Chosen Approach*: GitHub Actions CI/CD creating GitHub Releases.
  - *Why*: Committing compiled binary `.apk` files directly into Git repository trees bloats repository size exponentially and degrades Git performance. GitHub Releases keep binary distributions clean, versioned, and easily downloadable via direct HTTP links.
- **Decision 2: Gradle Execution on GitHub Actions**:
  - *Chosen Approach*: Use `gradle/actions/setup-gradle@v4` with auto-caching.
  - *Why*: Speeds up subsequent build times by caching Gradle dependencies and wrapper distributions, reducing build times from minutes to seconds.
- **Decision 3: Flexible Branch & Tag Triggers**:
  - *Chosen Approach*: Support `push` on `main`, `master`, version tags (`v*`), plus `workflow_dispatch` (manual one-click trigger from the GitHub Actions UI tab).
  - *Why*: Allows you to test or trigger fresh APK releases on-demand without needing to commit new code.

---

### 4. Technical Architecture & CI/CD Pipeline

```
┌─────────────────────────────────────────────────────────┐
│                    Developer / User                     │
└────────────────────────────┬────────────────────────────┘
                             │ Push / Sync to GitHub
                             ▼
┌─────────────────────────────────────────────────────────┐
│              GitHub Repository (Remote)                 │
└────────────────────────────┬────────────────────────────┘
                             │ Triggers .github/workflows
                             ▼
┌─────────────────────────────────────────────────────────┐
│         GitHub Actions Runner (ubuntu-latest)           │
│  ├─ 1. Checkout repository                              │
│  ├─ 2. Setup Java 17 (Eclipse Temurin)                  │
│  ├─ 3. Setup Android SDK & Gradle cache                 │
│  ├─ 4. Build APK: gradle :app:assembleDebug             │
│  └─ 5. Sign-verify & package APK artifact               │
└────────────────────────────┬────────────────────────────┘
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
┌───────────────────────────────┐ ┌───────────────────────────────┐
│        GitHub Releases        │ │     GitHub Build Artifacts    │
│  • Tagged Release created     │ │  • 30-day retention          │
│  • Installable APK attached   │ │  • Downloadable zip backup    │
└───────────────────────────────┘ └───────────────────────────────┘
```

#### Workflow Configuration Details (`.github/workflows/build-and-release-apk.yml`)
- **Permissions**: `contents: write` (required to create releases and upload release assets).
- **Environment**: Ubuntu Latest with JDK 17, Gradle caching enabled.
- **Build Output Target**: `app/build/outputs/apk/debug/app-debug.apk`.
- **Release Action**: `softprops/action-gh-release@v2` (robust, widely trusted standard for GitHub release automation).
- **Documentation**: Provide a concise `GITHUB_SETUP.md` in the project root explaining how to push to GitHub from AI Studio and download releases on Android devices.
