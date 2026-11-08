package com.example.data

import android.content.Context

object CategoryPrefs {
    private const val PREF_NAME = "category_prefs"
    private const val KEY_CATEGORIES = "categories_list"
    private const val KEY_TAGS = "tags_list"
    private const val KEY_HIDDEN_CATEGORIES = "hidden_categories_list"
    private const val KEY_CATEGORY_PASSCODE = "category_lock_passcode"
    private const val KEY_CATEGORY_PASSCODE_ENABLED = "category_lock_passcode_enabled"

    fun isPasscodeEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CATEGORY_PASSCODE_ENABLED, false)
    }

    fun setPasscodeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CATEGORY_PASSCODE_ENABLED, enabled).apply()
    }

    fun getPasscode(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_CATEGORY_PASSCODE, null)
        return if (code.isNullOrBlank()) null else code
    }

    fun setPasscode(context: Context, passcode: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CATEGORY_PASSCODE, passcode.trim())
            .putBoolean(KEY_CATEGORY_PASSCODE_ENABLED, true)
            .apply()
    }

    fun clearPasscode(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CATEGORY_PASSCODE)
            .putBoolean(KEY_CATEGORY_PASSCODE_ENABLED, false)
            .apply()
    }

    fun verifyPasscode(context: Context, input: String): Boolean {
        val saved = getPasscode(context) ?: return true
        return saved == input.trim()
    }

    fun getHiddenCategories(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_HIDDEN_CATEGORIES, null) ?: return emptySet()
        if (saved.isEmpty()) return emptySet()
        return saved.split("|||").filter { it.isNotEmpty() }.toSet()
    }

    fun saveHiddenCategories(context: Context, hiddenCategories: Set<String>) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_HIDDEN_CATEGORIES, hiddenCategories.joinToString("|||"))
            .apply()
    }

    fun getCategories(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CATEGORIES, null) ?: return listOf("General", "Personal", "Work", "Ideas", "Reminders")
        if (saved.isEmpty()) return emptyList()
        return saved.split("|||").filter { it.isNotEmpty() }
    }

    fun saveCategories(context: Context, categories: List<String>) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CATEGORIES, categories.joinToString("|||"))
            .putLong("config_updated_at", System.currentTimeMillis())
            .apply()
    }

    fun getTags(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TAGS, null) ?: return listOf("Urgent", "Reference", "School", "Finance", "Family")
        if (saved.isEmpty()) return emptyList()
        return saved.split("|||").filter { it.isNotEmpty() }
    }

    fun saveTags(context: Context, tags: List<String>) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TAGS, tags.joinToString("|||"))
            .putLong("config_updated_at", System.currentTimeMillis())
            .apply()
    }
}
