package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.NoteRepository
import com.example.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NoteScheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NoteScheduleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getIntExtra("note_id", -1)
        if (noteId == -1) return

        Log.d(TAG, "Alarm triggered for note ID $noteId")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val noteDao = db.noteDao()
                val note = noteDao.getNoteById(noteId)

                if (note != null && note.deletedAt == null && !note.isArchived) {
                    // 1. Post the notification reliably
                    MainActivity.companionPushNoteNotification(context, note)

                    // 2. Handle recurrence or finish one-time reminder
                    val currentTime = System.currentTimeMillis()
                    if (note.isScheduled && !note.repeatFrequency.isNullOrBlank() && note.repeatFrequency != "once") {
                        if (note.repeatFrequency.equals("minutes", ignoreCase = true)) {
                            val intervalMinutes = (note.repeatIntervalMinutes ?: 15).coerceAtLeast(1)
                            val intervalMs = intervalMinutes * 60 * 1000L
                            val nextTrigger = currentTime + intervalMs
                            val untilTime = note.repeatUntilTime

                            if (untilTime == null || nextTrigger <= untilTime) {
                                val updatedNote = note.copy(
                                    scheduledTime = nextTrigger,
                                    timestamp = currentTime
                                )
                                noteDao.updateNote(updatedNote)
                                NotificationScheduler.schedule(context, updatedNote)
                                SyncManager.triggerSyncNow(context, NoteRepository(noteDao))
                                com.example.widget.NotesWidgetProvider.updateAllWidgets(context)
                                Log.d(TAG, "Scheduled next minute alarm for note $noteId at $nextTrigger")
                            } else {
                                // Reached cutoff end date/time
                                val updatedNote = note.copy(
                                    isScheduled = false,
                                    scheduledTime = null,
                                    timestamp = currentTime
                                )
                                noteDao.updateNote(updatedNote)
                                NotificationScheduler.cancel(context, note.id)
                                SyncManager.triggerSyncNow(context, NoteRepository(noteDao))
                                Log.d(TAG, "Completed recurring minute reminder for note $noteId")
                            }
                        } else {
                            val interval = when (note.repeatFrequency.lowercase()) {
                                "daily" -> 24 * 60 * 60 * 1000L
                                "weekly" -> 7 * 24 * 60 * 60 * 1000L
                                "monthly" -> 30 * 24 * 60 * 60 * 1000L
                                else -> 0L
                            }
                            if (interval > 0L) {
                                val nextTrigger = currentTime + interval
                                val untilTime = note.repeatUntilTime
                                if (untilTime == null || nextTrigger <= untilTime) {
                                    val updatedNote = note.copy(
                                        scheduledTime = nextTrigger,
                                        timestamp = currentTime
                                    )
                                    noteDao.updateNote(updatedNote)
                                    NotificationScheduler.schedule(context, updatedNote)
                                    SyncManager.triggerSyncNow(context, NoteRepository(noteDao))
                                    com.example.widget.NotesWidgetProvider.updateAllWidgets(context)
                                    Log.d(TAG, "Scheduled next repeating alarm for note $noteId at $nextTrigger")
                                } else {
                                    val updatedNote = note.copy(
                                        isScheduled = false,
                                        scheduledTime = null,
                                        timestamp = currentTime
                                    )
                                    noteDao.updateNote(updatedNote)
                                    NotificationScheduler.cancel(context, note.id)
                                    SyncManager.triggerSyncNow(context, NoteRepository(noteDao))
                                    com.example.widget.NotesWidgetProvider.updateAllWidgets(context)
                                    Log.d(TAG, "Completed repeating reminder with cutoff for note $noteId")
                                }
                            }
                        }
                    } else {
                        // Mark one-time reminder as fired/inactive
                        val updatedNote = note.copy(
                            isScheduled = false,
                            scheduledTime = null,
                            timestamp = currentTime
                        )
                        noteDao.updateNote(updatedNote)
                        NotificationScheduler.cancel(context, note.id)
                        SyncManager.triggerSyncNow(context, NoteRepository(noteDao))
                        com.example.widget.NotesWidgetProvider.updateAllWidgets(context)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling note schedule alarm: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
