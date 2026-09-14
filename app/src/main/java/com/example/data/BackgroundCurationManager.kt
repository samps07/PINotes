package com.example.data

import android.content.Context
import android.widget.Toast
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BackgroundCurationManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startCuration(context: Context, noteId: Int, websiteUrl: String) {
        if (WebpageScraper.isMapUrl(websiteUrl)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                // Perform the automatic Gemini AI website extraction & summary
                val metadata = WebpageScraper.extractMetadata(websiteUrl, appContext)
                if (metadata.title.isNotEmpty()) {
                    val database = AppDatabase.getDatabase(appContext)
                    val dao = database.noteDao()
                    val existingNote = dao.getNoteById(noteId)
                    if (existingNote != null) {
                        val isTitlePlaceholder = existingNote.title.isEmpty() || 
                                                 existingNote.title == "New Attachment Note" || 
                                                 existingNote.title.startsWith("Shared Link") || 
                                                 existingNote.title == "Curation Placeholder" ||
                                                 existingNote.title == "Analyzing link..." ||
                                                 existingNote.title == "Untitled Note" ||
                                                 existingNote.title == "Untitled" ||
                                                 existingNote.title == existingNote.webUrl ||
                                                 existingNote.title.startsWith("http://") ||
                                                 existingNote.title.startsWith("https://")
                        
                        val isContentPlaceholder = existingNote.content.isEmpty() || 
                                                   existingNote.content == "AI Extraction compiling in background..." ||
                                                   existingNote.content.startsWith("http://") ||
                                                   existingNote.content.startsWith("https://") ||
                                                   existingNote.content == existingNote.webUrl ||
                                                   existingNote.content == existingNote.title

                        // If AI extracts a category and the local note is set to general/empty, apply the category!
                        val finalCategory = if (!metadata.category.isNullOrEmpty() && (existingNote.category.isEmpty() || existingNote.category == "General")) {
                            metadata.category
                        } else {
                            existingNote.category
                        }

                        // If AI extracts tags, assign them to the note's autoTags field!
                        val finalAutoTags = if (!metadata.tags.isNullOrEmpty() && existingNote.autoTags.isNullOrEmpty()) {
                            // Convert pipe-separated or comma-separated tags to standard pipe-separated format
                            metadata.tags.split(Regex("[|,]+")).map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }.joinToString("|")
                        } else {
                            existingNote.autoTags
                        }

                        val updatedNote = existingNote.copy(
                            title = if (isTitlePlaceholder) metadata.title else existingNote.title,
                            content = if (isContentPlaceholder) metadata.summary else existingNote.content,
                            imageUri = if (existingNote.imageUri.isNullOrEmpty()) metadata.imageUrl else existingNote.imageUri,
                            category = finalCategory,
                            autoTags = finalAutoTags,
                            webUrl = websiteUrl,
                            updatedAt = System.currentTimeMillis()
                        )
                        dao.updateNote(updatedNote)

                        // Trigger cloud sync so background curation result syncs across devices
                        val repo = NoteRepository(dao)
                        com.example.sync.SyncManager.triggerSyncNow(appContext, repo)

                        // Sync newly extracted category with Suggestion Preferences (categories still sync, but not tags)
                        if (!metadata.category.isNullOrEmpty()) {
                            val currentCats = CategoryPrefs.getCategories(appContext).toMutableList()
                            val cleanCat = metadata.category.trim()
                            if (cleanCat.isNotEmpty() && !currentCats.contains(cleanCat)) {
                                currentCats.add(cleanCat)
                                CategoryPrefs.saveCategories(appContext, currentCats)
                            }
                        }

                        // Only update the active notification if it was already pushed/active
                        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                        val isNotificationActive = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            notificationManager?.activeNotifications?.any { it.id == noteId } == true
                        } else {
                            false
                        }
                        if (isNotificationActive) {
                            MainActivity.companionPushNoteNotification(appContext, updatedNote)
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, "AI Background Curation finished!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
