package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.data.AppDatabase
import com.example.data.parseTaskItems
import com.example.data.serializeTaskItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getIntExtra("note_id", -1)
        if (noteId == -1) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val noteDao = db.noteDao()
                val note = noteDao.getNoteById(noteId)
                if (intent.action == "com.example.ACTION_DISMISS_NOTIFICATION") {
                    if (note != null) {
                        val updatedNote = note.copy(
                            isPersistentNotification = false,
                            timestamp = System.currentTimeMillis()
                        )
                        noteDao.updateNote(updatedNote)
                    }
                    val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
                    notificationManager.cancel(noteId)
                } else if (intent.action == "com.example.ACTION_PERSISTENT_SWIPED") {
                    if (note != null && note.isPersistentNotification) {
                        MainActivity.companionPushNoteNotification(context, note)
                    }
                } else if (intent.action == "com.example.ACTION_COPY_NOTE") {
                    val textToCopy = intent.getStringExtra("copy_text") ?: (if (note != null) com.example.data.formatNoteForCopying(note) else "")
                    if (textToCopy.isNotEmpty()) {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Note", textToCopy)
                            clipboard.setPrimaryClip(clip)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Note copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else if (note != null) {
                    if (intent.action == "com.example.ACTION_REPLY_EDIT") {
                        val remoteInputResults = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
                        val replyText = remoteInputResults?.getCharSequence("key_note_reply")?.toString()?.trim()
                        if (!replyText.isNullOrEmpty()) {
                            val updatedNote = if (note.isTaskList) {
                                val newContent = com.example.data.addTaskItemToContent(note.content, replyText)
                                note.copy(
                                    content = newContent,
                                    timestamp = System.currentTimeMillis()
                                )
                            } else {
                                note.copy(
                                    content = replyText,
                                    timestamp = System.currentTimeMillis()
                                )
                            }
                            noteDao.updateNote(updatedNote)
                            com.example.sync.SyncManager.triggerSyncNow(context, com.example.data.NoteRepository(noteDao))
                            MainActivity.companionPushNoteNotification(context, updatedNote)
                            com.example.widget.NotesWidgetProvider.updateAllWidgets(context)
                        }
                    } else if (intent.action == "com.example.ACTION_TOGGLE_ITEM" || intent.hasExtra("item_index")) {
                        val itemIndex = intent.getIntExtra("item_index", -1)
                        if (note.isTaskList && itemIndex != -1) {
                            val newContent = com.example.data.toggleTaskItemChecked(note.content, itemIndex)
                            val updatedNote = note.copy(
                                content = newContent,
                                timestamp = System.currentTimeMillis()
                            )
                            noteDao.updateNote(updatedNote)
                            com.example.sync.SyncManager.triggerSyncNow(context, com.example.data.NoteRepository(noteDao))
                            MainActivity.companionPushNoteNotification(context, updatedNote)
                            com.example.widget.NotesWidgetProvider.updateAllWidgets(context)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
