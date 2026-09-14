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
                                val items = parseTaskItems(note.content).toMutableList()
                                val rawInt = replyText.toIntOrNull()
                                val cleanReply = replyText.removePrefix("/").trim()
                                val slashInt = cleanReply.toIntOrNull()
                                val matchedIndex = if (rawInt != null) {
                                    rawInt - 1
                                } else if (slashInt != null) {
                                    slashInt - 1
                                } else {
                                    items.indexOfFirst { it.text.equals(replyText, ignoreCase = true) }
                                }

                                if (matchedIndex >= 0 && matchedIndex < items.size) {
                                    val currentItem = items[matchedIndex]
                                    items[matchedIndex] = currentItem.copy(isChecked = !currentItem.isChecked)
                                } else {
                                    items.add(com.example.data.TaskItem(replyText, false))
                                }
                                note.copy(
                                    content = serializeTaskItems(items),
                                    timestamp = System.currentTimeMillis()
                                )
                            } else {
                                note.copy(
                                    content = replyText,
                                    timestamp = System.currentTimeMillis()
                                )
                            }
                            noteDao.updateNote(updatedNote)
                            MainActivity.companionPushNoteNotification(context, updatedNote)
                        }
                    } else {
                        val itemIndex = intent.getIntExtra("item_index", -1)
                        if (note.isTaskList && itemIndex != -1) {
                            val items = parseTaskItems(note.content).toMutableList()
                            if (itemIndex >= 0 && itemIndex < items.size) {
                                val current = items[itemIndex]
                                items[itemIndex] = current.copy(isChecked = !current.isChecked)
                                
                                val updatedNote = note.copy(
                                    content = serializeTaskItems(items),
                                    timestamp = System.currentTimeMillis()
                                )
                                noteDao.updateNote(updatedNote)
                                MainActivity.companionPushNoteNotification(context, updatedNote)
                            }
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
