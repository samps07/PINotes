package com.example.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

data class FirebaseConfig(
    val apiKey: String = "",
    val appId: String = "",
    val projectId: String = "",
    val storageBucket: String = "",
    val isEnabled: Boolean = false,
    val userEmail: String = "",
    val userPassword: String = ""
) {
    fun isValid(): Boolean {
        return apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank()
    }

    companion object {
        private const val PREFS_NAME = "firebase_sync_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_PROJECT_ID = "project_id"
        private const val KEY_STORAGE_BUCKET = "storage_bucket"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PASSWORD = "user_password"

        const val APP_NAME = "UserSyncApp"

        fun normalizeStorageBucket(rawBucket: String, projectId: String): String {
            val trimmedProject = projectId.trim()
            var cleaned = rawBucket.trim()
            if (cleaned.startsWith("gs://", ignoreCase = true)) {
                cleaned = cleaned.substring(5)
            } else if (cleaned.startsWith("https://", ignoreCase = true)) {
                cleaned = cleaned.substring(8)
            } else if (cleaned.startsWith("http://", ignoreCase = true)) {
                cleaned = cleaned.substring(7)
            }
            cleaned = cleaned.trim().trimStart('/')
            if (cleaned.contains('/')) {
                cleaned = cleaned.substringBefore('/')
            }
            return if (cleaned.isNotBlank()) {
                cleaned
            } else if (trimmedProject.isNotBlank()) {
                "$trimmedProject.firebasestorage.app"
            } else {
                ""
            }
        }

        fun sanitized(config: FirebaseConfig): FirebaseConfig {
            val cleanProjectId = config.projectId.trim()
            return config.copy(
                apiKey = config.apiKey.trim(),
                appId = config.appId.trim(),
                projectId = cleanProjectId,
                storageBucket = normalizeStorageBucket(config.storageBucket, cleanProjectId),
                userEmail = config.userEmail.trim()
            )
        }

        fun load(context: Context): FirebaseConfig {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawProjectId = prefs.getString(KEY_PROJECT_ID, "") ?: ""
            val rawBucket = prefs.getString(KEY_STORAGE_BUCKET, "") ?: ""
            return FirebaseConfig(
                apiKey = (prefs.getString(KEY_API_KEY, "") ?: "").trim(),
                appId = (prefs.getString(KEY_APP_ID, "") ?: "").trim(),
                projectId = rawProjectId.trim(),
                storageBucket = normalizeStorageBucket(rawBucket, rawProjectId),
                isEnabled = prefs.getBoolean(KEY_ENABLED, false),
                userEmail = (prefs.getString(KEY_USER_EMAIL, "") ?: "").trim(),
                userPassword = prefs.getString(KEY_USER_PASSWORD, "") ?: ""
            )
        }

        fun save(context: Context, config: FirebaseConfig) {
            val cleanConfig = sanitized(config)
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_API_KEY, cleanConfig.apiKey)
                .putString(KEY_APP_ID, cleanConfig.appId)
                .putString(KEY_PROJECT_ID, cleanConfig.projectId)
                .putString(KEY_STORAGE_BUCKET, cleanConfig.storageBucket)
                .putBoolean(KEY_ENABLED, cleanConfig.isEnabled)
                .putString(KEY_USER_EMAIL, cleanConfig.userEmail)
                .putString(KEY_USER_PASSWORD, cleanConfig.userPassword)
                .apply()

            if (cleanConfig.isValid() && cleanConfig.isEnabled) {
                val existingApp = getApp()
                val optionsChanged = existingApp == null || run {
                    val opts = existingApp.options
                    opts.apiKey != cleanConfig.apiKey ||
                        opts.applicationId != cleanConfig.appId ||
                        opts.projectId != cleanConfig.projectId ||
                        (opts.storageBucket ?: "") != cleanConfig.storageBucket
                }
                if (optionsChanged) {
                    if (existingApp != null) {
                        try {
                            existingApp.delete()
                        } catch (_: Exception) {
                        }
                    }
                    initialize(context, cleanConfig)
                }
            } else {
                try {
                    getApp()?.delete()
                } catch (_: Exception) {
                }
            }
        }

        fun initialize(context: Context, config: FirebaseConfig): FirebaseApp? {
            val cleanConfig = sanitized(config)
            if (!cleanConfig.isValid() || !cleanConfig.isEnabled) return null
            val existingApp = getApp()
            if (existingApp != null) {
                val opts = existingApp.options
                val sameOptions = opts.apiKey == cleanConfig.apiKey &&
                    opts.applicationId == cleanConfig.appId &&
                    opts.projectId == cleanConfig.projectId &&
                    (opts.storageBucket ?: "") == cleanConfig.storageBucket
                if (sameOptions) {
                    return existingApp
                }
                try {
                    existingApp.delete()
                } catch (_: Exception) {
                }
            }
            return try {
                val builder = FirebaseOptions.Builder()
                    .setApiKey(cleanConfig.apiKey)
                    .setApplicationId(cleanConfig.appId)
                    .setProjectId(cleanConfig.projectId)

                if (cleanConfig.storageBucket.isNotBlank()) {
                    builder.setStorageBucket(cleanConfig.storageBucket)
                }

                FirebaseApp.initializeApp(context.applicationContext, builder.build(), APP_NAME)
            } catch (ex: Throwable) {
                ex.printStackTrace()
                null
            }
        }

        fun getApp(): FirebaseApp? {
            return try {
                FirebaseApp.getInstance(APP_NAME)
            } catch (_: Exception) {
                null
            }
        }
    }
}
