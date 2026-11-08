// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
}

// Suppress known harmless disposal race on AWT-EventQueue in KSP (KT-81874)
val rootHandler = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    if (thread.name.contains("AWT-EventQueue") &&
        (throwable is NullPointerException || throwable.stackTrace.any { it.className.contains("BinaryFileTypeDecompilers") || it.className.contains("FileDocumentManager") })
    ) {
        return@setDefaultUncaughtExceptionHandler
    }
    rootHandler?.uncaughtException(thread, throwable)
}
