package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NoteScheduleReceiver : BroadcastReceiver() {
    private val TAG = "NoteScheduleReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getIntExtra("note_id", -1)
        if (noteId == -1) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val noteDao = db.noteDao()
                val note = noteDao.getNoteById(noteId)
                if (note != null) {
                    // 1. Post notification
                    MainActivity.companionPushNoteNotification(context, note)

                    // 2. Handle schedule repeats if configured
                    if (note.isScheduled && !note.repeatFrequency.isNullOrBlank() && note.repeatFrequency != "once") {
                        val currentTime = note.scheduledTime ?: System.currentTimeMillis()
                        val interval = when (note.repeatFrequency.lowercase()) {
                            "daily" -> 24 * 60 * 60 * 1000L
                            "weekly" -> 7 * 24 * 60 * 60 * 1000L
                            "monthly" -> 30 * 24 * 60 * 60 * 1000L // Approx 30 days
                            else -> 0L
                        }

                        if (interval > 0L) {
                            val nextTrigger = currentTime + interval
                            val updatedNote = note.copy(scheduledTime = nextTrigger)
                            noteDao.updateNote(updatedNote)
                            NotificationScheduler.schedule(context, updatedNote)
                            Log.d(TAG, "Scheduled next alarm for repeating note $noteId at $nextTrigger")
                        }
                    } else {
                        // Mark as no longer active scheduler
                        val updatedNote = note.copy(isScheduled = false, scheduledTime = null)
                        noteDao.updateNote(updatedNote)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling alarm receive: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
