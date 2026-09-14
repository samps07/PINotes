package com.example.data

import android.content.Context

object CategoryPrefs {
    private const val PREF_NAME = "category_prefs"
    private const val KEY_CATEGORIES = "categories_list"
    private const val KEY_TAGS = "tags_list"
    private const val KEY_HIDDEN_CATEGORIES = "hidden_categories_list"

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
