package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.data.Note

object NotificationScheduler {
    private const val TAG = "NotificationScheduler"

    private fun buildAlarmIntent(context: Context, noteId: Int, triggerTime: Long): Intent {
        return Intent(context, NoteScheduleReceiver::class.java).apply {
            action = "com.example.ACTION_NOTE_SCHEDULE_ALARM"
            data = Uri.parse("pinotes://reminder/$noteId")
            putExtra("note_id", noteId)
            putExtra("scheduled_time", triggerTime)
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening exact alarm settings: ${e.message}")
            }
        }
    }

    /**
     * Schedules a reliable alarm using setExactAndAllowWhileIdle with fallback to
     * setAndAllowWhileIdle if exact alarm permission is unavailable.
     */
    fun schedule(context: Context, note: Note): Boolean {
        if (!note.isScheduled || note.scheduledTime == null) {
            cancel(context, note.id)
            return false
        }

        val triggerTime = note.scheduledTime
        val now = System.currentTimeMillis()
        val freq = note.repeatFrequency?.lowercase() ?: "once"

        if (triggerTime <= now) {
            // For recurring reminders, compute next trigger
            if (freq == "minutes") {
                val intervalMs = (note.repeatIntervalMinutes ?: 15).coerceAtLeast(1) * 60 * 1000L
                var nextTrigger = triggerTime
                while (nextTrigger <= now) {
                    nextTrigger += intervalMs
                }
                val untilTime = note.repeatUntilTime
                if (untilTime != null && nextTrigger > untilTime) {
                    cancel(context, note.id)
                    return false
                }
                return scheduleAtTime(context, note.id, nextTrigger)
            } else if (freq in listOf("daily", "weekly", "monthly")) {
                val interval = when (freq) {
                    "daily" -> 24 * 60 * 60 * 1000L
                    "weekly" -> 7 * 24 * 60 * 60 * 1000L
                    "monthly" -> 30 * 24 * 60 * 60 * 1000L
                    else -> 0L
                }
                if (interval > 0L) {
                    var nextTrigger = triggerTime
                    while (nextTrigger <= now) {
                        nextTrigger += interval
                    }
                    val untilTime = note.repeatUntilTime
                    if (untilTime != null && nextTrigger > untilTime) {
                        cancel(context, note.id)
                        return false
                    }
                    return scheduleAtTime(context, note.id, nextTrigger)
                } else {
                    cancel(context, note.id)
                    return false
                }
            } else {
                cancel(context, note.id)
                return false
            }
        }

        return scheduleAtTime(context, note.id, triggerTime)
    }

    fun scheduleAtTime(context: Context, noteId: Int, triggerTime: Long): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = buildAlarmIntent(context, noteId, triggerTime)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            noteId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = canScheduleExactAlarms(context)
        return try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Successfully scheduled exact alarm for note $noteId at $triggerTime")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.i(TAG, "Exact alarms permission missing; scheduled inexact idle alarm for note $noteId at $triggerTime")
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException on setExactAndAllowWhileIdle, falling back to setAndAllowWhileIdle: ${e.message}")
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                true
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback scheduling failed: ${ex.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm: ${e.message}")
            false
        }
    }

    fun cancel(context: Context, noteId: Int) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = buildAlarmIntent(context, noteId, 0L)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                noteId,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d(TAG, "Cancelled alarm for note $noteId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling alarm: ${e.message}")
        }
    }
}
