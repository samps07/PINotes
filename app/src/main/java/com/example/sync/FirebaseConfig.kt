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

        fun load(context: Context): FirebaseConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return FirebaseConfig(
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                appId = prefs.getString(KEY_APP_ID, "") ?: "",
                projectId = prefs.getString(KEY_PROJECT_ID, "") ?: "",
                storageBucket = prefs.getString(KEY_STORAGE_BUCKET, "") ?: "",
                isEnabled = prefs.getBoolean(KEY_ENABLED, false),
                userEmail = prefs.getString(KEY_USER_EMAIL, "") ?: "",
                userPassword = prefs.getString(KEY_USER_PASSWORD, "") ?: ""
            )
        }

        fun save(context: Context, config: FirebaseConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_API_KEY, config.apiKey)
                .putString(KEY_APP_ID, config.appId)
                .putString(KEY_PROJECT_ID, config.projectId)
                .putString(KEY_STORAGE_BUCKET, config.storageBucket)
                .putBoolean(KEY_ENABLED, config.isEnabled)
                .putString(KEY_USER_EMAIL, config.userEmail)
                .putString(KEY_USER_PASSWORD, config.userPassword)
                .apply()

            // If initialized, recreate client to apply new config
            if (config.isValid() && config.isEnabled) {
                try {
                    val app = FirebaseApp.getInstance(APP_NAME)
                    app.delete()
                } catch (e: Exception) {
                    // Not initialized yet
                }
                initialize(context, config)
            } else {
                // If disabled, delete existing app
                try {
                    val app = FirebaseApp.getInstance(APP_NAME)
                    app.delete()
                } catch (e: Exception) {
                    // Not initialized or already deleted
                }
            }
        }

        fun initialize(context: Context, config: FirebaseConfig): FirebaseApp? {
            if (!config.isValid() || !config.isEnabled) return null
            return try {
                FirebaseApp.getInstance(APP_NAME)
            } catch (e: Exception) {
                try {
                    val builder = FirebaseOptions.Builder()
                        .setApiKey(config.apiKey)
                        .setApplicationId(config.appId)
                        .setProjectId(config.projectId)

                    if (config.storageBucket.isNotBlank()) {
                        builder.setStorageBucket(config.storageBucket)
                    }

                    FirebaseApp.initializeApp(context.applicationContext, builder.build(), APP_NAME)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    null
                }
            }
        }

        fun getApp(): FirebaseApp? {
            return try {
                FirebaseApp.getInstance(APP_NAME)
            } catch (e: Exception) {
                null
            }
        }
    }
}
