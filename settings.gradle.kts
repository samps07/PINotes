// Suppress known harmless disposal race on AWT-EventQueue in KSP (KT-81874)
val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    if (thread.name.contains("AWT-EventQueue") &&
        (throwable is NullPointerException || throwable.stackTrace.any { it.className.contains("BinaryFileTypeDecompilers") || it.className.contains("FileDocumentManager") })
    ) {
        // Silently ignore harmless disposal race on AWT thread during KSP task completion
        return@setDefaultUncaughtExceptionHandler
    }
    previousHandler?.uncaughtException(thread, throwable)
}

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "PINotes"

include(":app")
