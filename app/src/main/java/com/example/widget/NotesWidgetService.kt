package com.example.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.R
import com.example.data.AppDatabase
import com.example.data.Note
import com.example.data.parseTaskItems

class NotesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return NotesWidgetFactory(applicationContext)
    }
}

class NotesWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var notes: List<Note> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        try {
            val db = AppDatabase.getDatabase(context)
            notes = db.noteDao().getAllNotesSync()
        } catch (e: Exception) {
            notes = emptyList()
        }
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= notes.size) return null
        val note = notes[position]

        val rv = RemoteViews(context.packageName, R.layout.widget_note_item)

        // Note title
        val titleText = if (note.title.isNotBlank()) note.title else "Untitled"
        rv.setTextViewText(R.id.widget_item_title, titleText)

        // Pinned icon
        if (note.isPinned) {
            rv.setViewVisibility(R.id.widget_item_pin, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.widget_item_pin, View.GONE)
        }

        // Category
        if (note.category.isNotBlank()) {
            rv.setTextViewText(R.id.widget_item_category, note.category)
            rv.setViewVisibility(R.id.widget_item_category, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.widget_item_category, View.GONE)
        }

        // Content preview
        val preview = if (note.isTaskList) {
            val items = parseTaskItems(note.content)
            if (items.isNotEmpty()) {
                val done = items.count { it.isChecked }
                val total = items.size
                "Checklist ($done/$total): " + items.take(2).joinToString(", ") { (if (it.isChecked) "✓ " else "☐ ") + it.text }
            } else {
                "Empty checklist"
            }
        } else {
            note.content.replace("**", "").replace("__", "").replace("*", "").trim()
        }
        rv.setTextViewText(R.id.widget_item_content, preview.ifEmpty { "No additional text" })

        // Click fill-in intent to open note in MainActivity
        val fillInIntent = Intent().apply {
            putExtra("open_note_id", note.id)
        }
        rv.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)

        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return if (position in notes.indices) notes[position].id.toLong() else position.toLong()
    }

    override fun hasStableIds(): Boolean = true
}
