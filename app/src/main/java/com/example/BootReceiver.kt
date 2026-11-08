package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        Log.d(TAG, "Received system action: $action. Restoring scheduled reminders and pinned notes.")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val noteDao = db.noteDao()
                val allNotes = noteDao.getAllNotesDirect()
                val now = System.currentTimeMillis()

                for (note in allNotes) {
                    if (note.deletedAt != null || note.isArchived) continue

                    // 1. Re-pin persistent notes if active
                    if (note.isPersistentNotification) {
                        MainActivity.companionPushNoteNotification(context, note)
                    }

                    // 2. Re-register scheduled reminders
                    if (note.isScheduled && note.scheduledTime != null) {
                        if (note.scheduledTime > now) {
                            NotificationScheduler.schedule(context, note)
                            Log.d(TAG, "Rescheduled future alarm for note ${note.id} at ${note.scheduledTime}")
                        } else {
                            // Reminder was due while device was powered off
                            MainActivity.companionPushNoteNotification(context, note)
                            if (note.repeatFrequency.equals("minutes", ignoreCase = true)) {
                                val intervalMs = (note.repeatIntervalMinutes ?: 15).coerceAtLeast(1) * 60 * 1000L
                                var nextTrigger = note.scheduledTime
                                while (nextTrigger <= now) {
                                    nextTrigger += intervalMs
                                }
                                val until = note.repeatUntilTime
                                if (until == null || nextTrigger <= until) {
                                    val updated = note.copy(scheduledTime = nextTrigger)
                                    noteDao.updateNote(updated)
                                    NotificationScheduler.schedule(context, updated)
                                } else {
                                    val updated = note.copy(isScheduled = false, scheduledTime = null)
                                    noteDao.updateNote(updated)
                                }
                            } else if (note.repeatFrequency?.lowercase() in listOf("daily", "weekly", "monthly")) {
                                val interval = when (note.repeatFrequency!!.lowercase()) {
                                    "daily" -> 24 * 60 * 60 * 1000L
                                    "weekly" -> 7 * 24 * 60 * 60 * 1000L
                                    "monthly" -> 30 * 24 * 60 * 60 * 1000L
                                    else -> 0L
                                }
                                if (interval > 0L) {
                                    var nextTrigger = note.scheduledTime
                                    while (nextTrigger <= now) {
                                        nextTrigger += interval
                                    }
                                    val updated = note.copy(scheduledTime = nextTrigger)
                                    noteDao.updateNote(updated)
                                    NotificationScheduler.schedule(context, updated)
                                }
                            } else {
                                // One-time reminder that expired while device was off
                                val updated = note.copy(isScheduled = false, scheduledTime = null)
                                noteDao.updateNote(updated)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in BootReceiver: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
