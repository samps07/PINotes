package com.example.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.example.data.Note
import com.example.data.NoteRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SyncStatus {
    SYNCED, SYNCING, OFFLINE, ERROR
}

// Custom await extension to cleanly turn Firebase Task to suspend function without play-services-coroutines jar issues
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        this.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(task.exception ?: RuntimeException("Task failed"))
            }
        }
    }
}

object SyncManager {
    val syncStatus = MutableStateFlow(SyncStatus.OFFLINE)
    val isUserLoggedIn = MutableStateFlow(false)
    val loggedInUserEmail = MutableStateFlow<String?>(null)
    
    private var activeListener: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    // Dynamic Firebase Auth login
    suspend fun loginOrRegisterUser(context: Context, email: String, password: String): Boolean {
        val config = FirebaseConfig.load(context)
        val app = FirebaseConfig.initialize(context, config) ?: return false
        val auth = FirebaseAuth.getInstance(app)
        
        try {
            syncStatus.value = SyncStatus.SYNCING
            // Try to log in
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            if (authResult.user != null) {
                // Login successful
                isUserLoggedIn.value = true
                loggedInUserEmail.value = email
                // Save config with email/password
                FirebaseConfig.save(context, config.copy(isEnabled = true, userEmail = email, userPassword = password))
                syncStatus.value = SyncStatus.SYNCED
                return true
            }
        } catch (e: Exception) {
            // Try to register if login failed
            try {
                val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                if (authResult.user != null) {
                    isUserLoggedIn.value = true
                    loggedInUserEmail.value = email
                    FirebaseConfig.save(context, config.copy(isEnabled = true, userEmail = email, userPassword = password))
                    syncStatus.value = SyncStatus.SYNCED
                    return true
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        syncStatus.value = SyncStatus.ERROR
        return false
    }

    fun logout(context: Context) {
        val app = FirebaseConfig.getApp()
        if (app != null) {
            try {
                FirebaseAuth.getInstance(app).signOut()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        activeListener?.remove()
        activeListener = null
        
        isUserLoggedIn.value = false
        loggedInUserEmail.value = null
        
        val config = FirebaseConfig.load(context)
        FirebaseConfig.save(context, config.copy(isEnabled = false, userEmail = "", userPassword = ""))
        syncStatus.value = SyncStatus.OFFLINE
    }

    fun initializeSync(context: Context, noteRepository: NoteRepository) {
        scope.launch {
            val config = FirebaseConfig.load(context)
            if (config.isValid() && config.isEnabled) {
                val app = FirebaseConfig.initialize(context, config)
                if (app != null) {
                    val auth = FirebaseAuth.getInstance(app)
                    val currentUser = auth.currentUser
                    if (currentUser != null) {
                        isUserLoggedIn.value = true
                        loggedInUserEmail.value = currentUser.email
                        // Auto sync on startup if firebase account is setup
                        triggerSyncNow(context, noteRepository)
                    } else if (config.userEmail.isNotBlank() && config.userPassword.isNotBlank()) {
                        // Re-authenticate
                        try {
                            val authResult = auth.signInWithEmailAndPassword(config.userEmail, config.userPassword).await()
                            if (authResult.user != null) {
                                isUserLoggedIn.value = true
                                loggedInUserEmail.value = config.userEmail
                                // Auto sync on startup if firebase account is setup
                                triggerSyncNow(context, noteRepository)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            syncStatus.value = SyncStatus.ERROR
                        }
                    }
                }
            } else {
                syncStatus.value = SyncStatus.OFFLINE
            }
        }
    }

    // Force run sync right now
    fun triggerSyncNow(context: Context, noteRepository: NoteRepository) {
        scope.launch {
            val config = FirebaseConfig.load(context)
            if (!config.isValid() || !config.isEnabled) {
                syncStatus.value = SyncStatus.OFFLINE
                return@launch
            }
            if (!isNetworkAvailable(context)) {
                syncStatus.value = SyncStatus.OFFLINE
                return@launch
            }
            val app = FirebaseConfig.getApp() ?: FirebaseConfig.initialize(context, config)
            if (app == null) {
                syncStatus.value = SyncStatus.ERROR
                return@launch
            }
            val auth = FirebaseAuth.getInstance(app)
            val uid = auth.uid
            if (uid != null) {
                triggerSyncNow(context, app, uid, noteRepository)
            } else {
                // If not signed in but config exists
                if (config.userEmail.isNotBlank() && config.userPassword.isNotBlank()) {
                    try {
                        val authResult = auth.signInWithEmailAndPassword(config.userEmail, config.userPassword).await()
                        authResult.user?.let {
                            isUserLoggedIn.value = true
                            loggedInUserEmail.value = config.userEmail
                            triggerSyncNow(context, app, it.uid, noteRepository)
                        }
                    } catch (e: Exception) {
                        syncStatus.value = SyncStatus.ERROR
                    }
                }
            }
        }
    }

    private suspend fun triggerSyncNow(context: Context, app: FirebaseApp, userId: String, noteRepository: NoteRepository) {
        if (!isNetworkAvailable(context)) {
            syncStatus.value = SyncStatus.OFFLINE
            return
        }
        syncStatus.value = SyncStatus.SYNCING
        val success = performFullSync(context, app, userId, noteRepository)
        syncStatus.value = if (success) SyncStatus.SYNCED else SyncStatus.ERROR
    }

    /**
     * Upload helper for storage attachments / image attachments
     */
    private suspend fun uploadAndCreateAttachment(
        context: Context,
        noteId: String,
        localUriStr: String,
        storage: FirebaseStorage,
        userId: String
    ): SyncAttachment? {
        if (localUriStr.isBlank()) return null
        
        // Detailed Logging: Upload start
        Log.d("SyncManager", "Upload/Download starting: Upload start for path = $localUriStr, noteId = $noteId")
        
        return try {
            val uri = when {
                localUriStr.startsWith("content://") || localUriStr.startsWith("file://") -> Uri.parse(localUriStr)
                else -> Uri.fromFile(java.io.File(localUriStr))
            }
            val contentResolver = context.contentResolver
            
            // Reconstruct / read file metadata
            var name = "file_${System.currentTimeMillis()}"
            var size = 0L
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                    cursor.close()
                } else {
                    val file = java.io.File(uri.path ?: localUriStr)
                    if (file.exists()) {
                        name = file.name
                        size = file.length()
                    }
                }
            } catch (e: Exception) {
                val file = java.io.File(uri.path ?: localUriStr)
                if (file.exists()) {
                    name = file.name
                    size = file.length()
                }
            }
            
            val mimeType = try { contentResolver.getType(uri) } catch (e: Exception) { null } ?: when {
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                name.endsWith(".png", true) -> "image/png"
                name.endsWith(".webp", true) -> "image/webp"
                name.endsWith(".pdf", true) -> "application/pdf"
                name.endsWith(".txt", true) -> "text/plain"
                else -> "application/octet-stream"
            }
            
            val inputStream: java.io.InputStream = try {
                contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                val f = java.io.File(uri.path ?: localUriStr)
                if (f.exists()) f.inputStream() else null
            } ?: return null
            
            // Target storage path requirement: users/{uid}/attachments/{noteId}/
            val storagePath = "users/$userId/attachments/$noteId/$name"
            val storageRef = storage.reference.child(storagePath)
            
            storageRef.putStream(inputStream).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            
            // Detailed Logging: Upload success & Download URL creation
            Log.d("SyncManager", "Upload/Download success: Upload successful for $name in noteId $noteId")
            Log.d("SyncManager", "Upload/Download starting: Download URL created for $name -> $downloadUrl")
            
            SyncAttachment(
                fileName = name,
                mimeType = mimeType,
                fileSize = if (size > 0) size else 1000L,
                storagePath = storagePath,
                downloadUrl = downloadUrl
            )
        } catch (e: Exception) {
            // Detailed Logging: Upload failure
            Log.e("SyncManager", "Upload/Download failure: Failed to upload $localUriStr: ${e.message}", e)
            null
        }
    }

    /**
     * Immediately upload attached image to Firebase Storage (if setup in settings).
     * Path format: users/{uid}/notes/{noteId}/images/{filename}
     */
    suspend fun uploadAttachedImageImmediately(
        context: Context,
        noteId: String,
        localUriStr: String
    ): Pair<String, String>? {
        if (localUriStr.isBlank()) return null
        
        val config = FirebaseConfig.load(context)
        if (!config.isValid() || !config.isEnabled) {
            Log.d("SyncManager", "Immediate upload skipped: Firebase setup is not enabled or invalid")
            return null
        }
        
        val app = FirebaseConfig.getApp() ?: return null
        val auth = FirebaseAuth.getInstance(app)
        val uid = auth.currentUser?.uid ?: return null
        val storage = FirebaseStorage.getInstance(app)
        val firestore = FirebaseFirestore.getInstance(app)
        
        return try {
            val uri = when {
                localUriStr.startsWith("content://") || localUriStr.startsWith("file://") -> Uri.parse(localUriStr)
                else -> Uri.fromFile(java.io.File(localUriStr))
            }
            val contentResolver = context.contentResolver
            
            // Reconstruct / read file name
            var name = "file_${System.currentTimeMillis()}"
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                    cursor.close()
                } else {
                    val file = java.io.File(uri.path ?: localUriStr)
                    if (file.exists()) {
                        name = file.name
                    }
                }
            } catch (e: Exception) {
                val file = java.io.File(uri.path ?: localUriStr)
                if (file.exists()) {
                    name = file.name
                }
            }
            
            // Ensure unique filename inside folder
            val filename = "${System.currentTimeMillis()}_$name"
            val storagePath = "users/$uid/notes/$noteId/images/$filename"
            
            // Detailed Logging: Image upload start
            Log.d("SyncManager", "Image upload start: Path format users/$uid/notes/$noteId/images/$filename starting for noteId $noteId")
            
            val inputStream = try {
                contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                val f = java.io.File(uri.path ?: localUriStr)
                if (f.exists()) f.inputStream() else null
            } ?: return null
            val storageRef = storage.reference.child(storagePath)
            
            storageRef.putStream(inputStream).await()
            
            // Detailed Logging: Upload success
            Log.d("SyncManager", "Upload success: Successfully uploaded to Firebase Storage path $storagePath")
            
            // Retrieve Firebase Storage download URL
            val downloadUrl = storageRef.downloadUrl.await().toString()
            
            // Detailed Logging: URL generation
            Log.d("SyncManager", "URL generation: Generated download URL -> $downloadUrl")
            
            // Save download URL and storage path in Firestore document immediately
            val noteDocRef = firestore.collection("users").document(uid).collection("notes").document(noteId)
            
            var finalImageUrl = downloadUrl
            var finalImageStoragePath = storagePath
            try {
                val doc = noteDocRef.get().await()
                if (doc.exists()) {
                    val existingUrl = doc.getString("imageUrl")
                    if (!existingUrl.isNullOrEmpty() && !existingUrl.contains(downloadUrl)) {
                        finalImageUrl = "$existingUrl|$downloadUrl"
                    }
                    val existingPath = doc.getString("imageStoragePath")
                    if (!existingPath.isNullOrEmpty() && !existingPath.contains(storagePath)) {
                        finalImageStoragePath = "$existingPath|$storagePath"
                    }
                }
            } catch (e: Exception) {
                // Non-existing document is fine
            }
            
            val fieldsToUpdate = mapOf(
                "imageUrl" to finalImageUrl,
                "imageStoragePath" to finalImageStoragePath
            )
            noteDocRef.set(fieldsToUpdate, com.google.firebase.firestore.SetOptions.merge()).await()
            
            // Detailed Logging: Firestore update
            Log.d("SyncManager", "Firestore update: Saved imageUrl and imageStoragePath in Firestore immediately for noteId $noteId")
            
            Pair(downloadUrl, storagePath)
        } catch (e: Exception) {
            // Detailed Logging: Upload failure
            Log.e("SyncManager", "Upload failure: Failed to immediately upload attached image: ${e.message}", e)
            null
        }
    }

    /**
     * Bidirectional dynamic Sync core method
     */
    private suspend fun performFullSync(context: Context, app: FirebaseApp, userId: String, noteRepository: NoteRepository): Boolean {
        val firestore = FirebaseFirestore.getInstance(app)
        val storage = FirebaseStorage.getInstance(app)
        
        return try {
            val localNotes = noteRepository.getAllNotesDirect()
            val remoteNotesSnapshot = firestore.collection("users").document(userId).collection("notes")
                .get().await()
                
            val remoteNotesMap = remoteNotesSnapshot.documents.associateBy { it.id }
            
            for (localNote in localNotes) {
                var updatedLocalNote = localNote
                var attachmentsChanged = false
                
                // Read current local attachments
                val currentLocalAttachments = AttachmentCacheManager.deserializeAttachments(localNote.attachmentsJson).toMutableList()
                val newAttachments = mutableListOf<SyncAttachment>()
                
                // 1. Image Uri check
                val imageUrisToSync = if (!localNote.imageUri.isNullOrEmpty()) {
                    localNote.imageUri.split("|").filter { it.isNotBlank() }
                } else emptyList()
                
                val finalImageUris = mutableListOf<String>()
                for (imgUri in imageUrisToSync) {
                    if (!imgUri.startsWith("http://") && !imgUri.startsWith("https://")) {
                        // Needs upload
                        val att = uploadAndCreateAttachment(context, localNote.noteId, imgUri, storage, userId)
                        if (att != null) {
                            newAttachments.add(att)
                            finalImageUris.add(att.downloadUrl) // Save remote url locally so we know it's synced
                            attachmentsChanged = true
                        } else {
                            finalImageUris.add(imgUri)
                        }
                    } else {
                        // Keep remote
                        finalImageUris.add(imgUri)
                        val existing = currentLocalAttachments.find { it.downloadUrl == imgUri }
                        if (existing != null) {
                            newAttachments.add(existing)
                        } else {
                            // Rehydrate metadata dynamically for safe migration
                            val fallbackName = Uri.parse(imgUri).lastPathSegment ?: "image.jpg"
                            newAttachments.add(
                                SyncAttachment(
                                    fileName = fallbackName,
                                    mimeType = "image/jpeg",
                                    fileSize = 0L,
                                    storagePath = "users/$userId/attachments/${localNote.noteId}/$fallbackName",
                                    downloadUrl = imgUri
                                )
                            )
                        }
                    }
                }
                
                // 2. Attachment Uri check
                val attachmentsToSync = if (!localNote.attachmentUri.isNullOrEmpty()) {
                    localNote.attachmentUri.split("|").filter { it.isNotBlank() }
                } else emptyList()
                
                val finalAttachmentUris = mutableListOf<String>()
                for (attUri in attachmentsToSync) {
                    if (!attUri.startsWith("http://") && !attUri.startsWith("https://")) {
                        // Needs upload
                        val att = uploadAndCreateAttachment(context, localNote.noteId, attUri, storage, userId)
                        if (att != null) {
                            newAttachments.add(att)
                            finalAttachmentUris.add(att.downloadUrl)
                            attachmentsChanged = true
                        } else {
                            finalAttachmentUris.add(attUri)
                        }
                    } else {
                        // Keep remote
                        finalAttachmentUris.add(attUri)
                        val existing = currentLocalAttachments.find { it.downloadUrl == attUri }
                        if (existing != null) {
                            newAttachments.add(existing)
                        } else {
                            val fallbackName = Uri.parse(attUri).lastPathSegment ?: "document.bin"
                            newAttachments.add(
                                SyncAttachment(
                                    fileName = fallbackName,
                                    mimeType = "application/octet-stream",
                                    fileSize = 0L,
                                    storagePath = "users/$userId/attachments/${localNote.noteId}/$fallbackName",
                                    downloadUrl = attUri
                                )
                            )
                        }
                    }
                }
                
                // If attachments list changed, or the note is new but has attachments
                if (attachmentsChanged || (localNote.attachmentsJson.isNullOrBlank() && newAttachments.isNotEmpty())) {
                    val serialized = AttachmentCacheManager.serializeAttachments(newAttachments)
                    updatedLocalNote = updatedLocalNote.copy(
                        attachmentsJson = serialized,
                        imageUri = finalImageUris.joinToString("|"),
                        attachmentUri = finalAttachmentUris.joinToString("|")
                    )
                }

                // Make sure imageUrl and imageStoragePath are computed from local image attachments
                val imageAtts = newAttachments.filter { it.mimeType.startsWith("image/") }
                var finalImageUrl = if (imageAtts.isNotEmpty()) {
                    imageAtts.joinToString("|") { it.downloadUrl }
                } else {
                    localNote.imageUrl
                }
                var finalImageStoragePath = if (imageAtts.isNotEmpty()) {
                    imageAtts.joinToString("|") { it.storagePath }
                } else {
                    localNote.imageStoragePath
                }

                // Never overwrite a valid imageUrl with null. Preserve existing image references.
                if (finalImageUrl.isNullOrEmpty() && !localNote.imageUrl.isNullOrEmpty()) {
                    finalImageUrl = localNote.imageUrl
                }
                if (finalImageStoragePath.isNullOrEmpty() && !localNote.imageStoragePath.isNullOrEmpty()) {
                    finalImageStoragePath = localNote.imageStoragePath
                }

                updatedLocalNote = updatedLocalNote.copy(
                    imageUrl = finalImageUrl,
                    imageStoragePath = finalImageStoragePath
                )
                
                // Handle Storage deletion if local note is soft-deleted
                if (updatedLocalNote.deletedAt != null) {
                    val attachmentsToDelete = AttachmentCacheManager.deserializeAttachments(updatedLocalNote.attachmentsJson)
                    for (att in attachmentsToDelete) {
                        if (att.storagePath.isNotEmpty()) {
                            try {
                                Log.d("SyncManager", "Storage deleting: Removing ${att.storagePath} for deleted note ${updatedLocalNote.noteId}")
                                storage.reference.child(att.storagePath).delete().await()
                            } catch (e: Exception) {
                                Log.w("SyncManager", "Storage deletion warning: Failed to delete path ${att.storagePath}: ${e.message}")
                            }
                        }
                    }
                    if (!updatedLocalNote.imageStoragePath.isNullOrEmpty()) {
                        val paths = updatedLocalNote.imageStoragePath.split("|").filter { it.isNotBlank() }
                        for (p in paths) {
                            try {
                                Log.d("SyncManager", "Storage deleting: Removing image storage path $p for deleted note ${updatedLocalNote.noteId}")
                                storage.reference.child(p).delete().await()
                            } catch (e: Exception) {
                                Log.w("SyncManager", "Storage deletion warning: Failed to delete path $p: ${e.message}")
                            }
                        }
                    }
                    try {
                        val dir = java.io.File(context.cacheDir, "attachments/${updatedLocalNote.noteId}")
                        if (dir.exists()) {
                            dir.deleteRecursively()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (attachmentsChanged) {
                    updatedLocalNote = updatedLocalNote.copy(updatedAt = System.currentTimeMillis())
                    noteRepository.update(updatedLocalNote)
                }
                
                val remoteDoc = remoteNotesMap[localNote.noteId]
                if (remoteDoc == null) {
                    // Upload to Firebase (includes deletedAt if soft deleted)
                    firestore.collection("users").document(userId).collection("notes")
                        .document(localNote.noteId)
                        .set(updatedLocalNote.toFirestoreMap()).await()
                } else {
                    val remoteNote = remoteDoc.data?.toNote(context, localId = localNote.id)
                    if (remoteNote != null) {
                        val localIsDeleted = updatedLocalNote.deletedAt != null
                        val remoteIsDeleted = remoteNote.deletedAt != null

                        if (localIsDeleted && !remoteIsDeleted) {
                            // Local was deleted. Check if remote was edited AFTER local deletion
                            val deletionTime = updatedLocalNote.deletedAt ?: updatedLocalNote.updatedAt
                            if (remoteNote.updatedAt > deletionTime) {
                                // Remote was edited after deletion -> restore remote note locally
                                noteRepository.updateFromSync(remoteNote.copy(id = localNote.id))
                            } else {
                                // Propagate local deletion to Firestore
                                firestore.collection("users").document(userId).collection("notes")
                                    .document(localNote.noteId)
                                    .set(updatedLocalNote.toFirestoreMap()).await()
                            }
                        } else if (!localIsDeleted && remoteIsDeleted) {
                            // Remote was deleted. Check if local was edited AFTER remote deletion
                            val remoteDeletionTime = remoteNote.deletedAt ?: remoteNote.updatedAt
                            if (updatedLocalNote.updatedAt > remoteDeletionTime) {
                                // Local was edited after remote deletion -> upload local note to Firestore
                                firestore.collection("users").document(userId).collection("notes")
                                    .document(localNote.noteId)
                                    .set(updatedLocalNote.toFirestoreMap()).await()
                            } else {
                                // Apply remote deletion locally
                                noteRepository.updateFromSync(updatedLocalNote.copy(deletedAt = remoteNote.deletedAt, updatedAt = remoteNote.updatedAt))
                            }
                        } else if (localIsDeleted && remoteIsDeleted) {
                            // Both deleted -> sync deletion state to Firestore
                            firestore.collection("users").document(userId).collection("notes")
                                .document(localNote.noteId)
                                .set(updatedLocalNote.toFirestoreMap()).await()
                        } else {
                            // Both active
                            if (updatedLocalNote.updatedAt > remoteNote.updatedAt) {
                                // Local is newer
                                val finalLocalNote = if (updatedLocalNote.imageUrl.isNullOrEmpty() && !remoteNote.imageUrl.isNullOrEmpty()) {
                                    updatedLocalNote.copy(
                                        imageUrl = remoteNote.imageUrl,
                                        imageStoragePath = remoteNote.imageStoragePath
                                    )
                                } else {
                                    updatedLocalNote
                                }
                                firestore.collection("users").document(userId).collection("notes")
                                    .document(localNote.noteId)
                                    .set(finalLocalNote.toFirestoreMap()).await()
                            } else if (remoteNote.updatedAt > updatedLocalNote.updatedAt) {
                                // Remote is newer
                                val finalRemoteNote = if (remoteNote.imageUrl.isNullOrEmpty() && !updatedLocalNote.imageUrl.isNullOrEmpty()) {
                                    remoteNote.copy(
                                        imageUrl = updatedLocalNote.imageUrl,
                                        imageStoragePath = updatedLocalNote.imageStoragePath
                                    )
                                } else {
                                    remoteNote
                                }
                                val downloaded = finalRemoteNote.copy(id = localNote.id)
                                noteRepository.updateFromSync(downloaded)
                            }
                        }
                    }
                }
            }
            
            // Check for notes on remote that aren't present locally at all
            for (remoteDoc in remoteNotesSnapshot.documents) {
                val noteId = remoteDoc.id
                val existsLocally = localNotes.any { it.noteId == noteId }
                if (!existsLocally) {
                    val remoteNote = remoteDoc.data?.toNote(context, localId = 0) ?: continue
                    if (remoteNote.deletedAt == null) {
                        noteRepository.insertFromSync(remoteNote)
                    }
                }
            }

            // 2-Way Sync of custom Categories and Tags list
            try {
                val localConfigPrefs = context.applicationContext.getSharedPreferences("category_prefs", Context.MODE_PRIVATE)
                val localUpdatedAt = localConfigPrefs.getLong("config_updated_at", 0L)
                
                val configRef = firestore.collection("users").document(userId).collection("config").document("preferences")
                val remoteConfigSnapshot = configRef.get().await()
                
                if (remoteConfigSnapshot.exists()) {
                    val remoteUpdatedAt = remoteConfigSnapshot.getLong("updatedAt") ?: 0L
                    val remoteCategories = remoteConfigSnapshot.get("categories") as? List<String>
                    val remoteTags = remoteConfigSnapshot.get("tags") as? List<String>
                    
                    if (localUpdatedAt > remoteUpdatedAt) {
                        // Local categories are newer, upload them to Firestore
                        val localCategories = com.example.data.CategoryPrefs.getCategories(context)
                        val localTags = com.example.data.CategoryPrefs.getTags(context)
                        val configData = mapOf(
                            "categories" to localCategories,
                            "tags" to localTags,
                            "updatedAt" to localUpdatedAt
                        )
                        configRef.set(configData).await()
                        Log.d("SyncManager", "Synced categories: Uploaded local categories/tags list to Firestore")
                    } else if (remoteUpdatedAt > localUpdatedAt) {
                        // Remote is newer, pull them to local storage
                        if (remoteCategories != null && remoteCategories.isNotEmpty()) {
                            com.example.data.CategoryPrefs.saveCategories(context, remoteCategories)
                        }
                        if (remoteTags != null && remoteTags.isNotEmpty()) {
                            com.example.data.CategoryPrefs.saveTags(context, remoteTags)
                        }
                        localConfigPrefs.edit().putLong("config_updated_at", remoteUpdatedAt).apply()
                        Log.d("SyncManager", "Synced categories: Downloaded remote categories/tags list to local SharedPreferences")
                    }
                } else {
                    // No config exists on Firestore yet, upload local categories and tags list
                    val localCategories = com.example.data.CategoryPrefs.getCategories(context)
                    val localTags = com.example.data.CategoryPrefs.getTags(context)
                    val defaultTime = if (localUpdatedAt > 0L) localUpdatedAt else System.currentTimeMillis()
                    val configData = mapOf(
                        "categories" to localCategories,
                        "tags" to localTags,
                        "updatedAt" to defaultTime
                    )
                    configRef.set(configData).await()
                    localConfigPrefs.edit().putLong("config_updated_at", defaultTime).apply()
                    Log.d("SyncManager", "Synced categories: Initialized remote categories/tags preferences list on Firestore")
                }
            } catch (cfgEx: Exception) {
                Log.e("SyncManager", "Synced categories warning: Failed to sync categories config list: ${cfgEx.message}")
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Set up realtime Firestore remote listener
     */
    private fun startRealtimeListener(context: Context, app: FirebaseApp, userId: String, noteRepository: NoteRepository) {
        // Disabled for manual sync mode
    }
}

/**
 * Extension mapping utilities
 */
fun Note.toFirestoreMap(): Map<String, Any?> {
    val attachments = AttachmentCacheManager.deserializeAttachments(attachmentsJson)
    val attachmentsMapList = AttachmentCacheManager.listToMapList(attachments)
    return mapOf(
        "noteId" to noteId,
        "title" to title,
        "content" to content,
        "timestamp" to timestamp,
        "category" to category,
        "colorIndex" to colorIndex,
        "isPinned" to isPinned,
        "attachments" to attachmentsMapList,
        "isTaskList" to isTaskList,
        "webUrl" to webUrl,
        "isScheduled" to isScheduled,
        "scheduledTime" to scheduledTime,
        "repeatFrequency" to repeatFrequency,
        "position" to position,
        "tags" to tags,
        "autoTags" to autoTags,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "deletedAt" to deletedAt,
        "imageUrl" to imageUrl,
        "imageStoragePath" to imageStoragePath,
        "isPersistentNotification" to isPersistentNotification,
        "isArchived" to isArchived
    )
}

fun Map<String, Any?>.toNote(context: Context, localId: Int = 0): Note {
    val attachmentsRaw = this["attachments"] as? List<*>
    val rawList = mutableListOf<Map<String, Any>>()
    if (attachmentsRaw != null) {
        for (item in attachmentsRaw) {
            if (item is Map<*, *>) {
                val m = mutableMapOf<String, Any>()
                for ((k, v) in item) {
                    if (k is String && v != null) {
                        m[k] = v
                    }
                }
                rawList.add(m)
            }
        }
    }
    
    val attachments = AttachmentCacheManager.mapListToList(rawList)
    val attachmentsJsonStr = if (attachments.isNotEmpty()) AttachmentCacheManager.serializeAttachments(attachments) else null
    
    // Detailed Logging: Attachment restoration
    if (attachments.isNotEmpty()) {
        Log.d("SyncManager", "Attachment restoration: Restored ${attachments.size} attachments from Firestore for noteId = ${this["noteId"]}")
    }

    val imageUrlSec = this["imageUrl"] as? String ?: this["imageUri"] as? String
    val imageStoragePathSec = this["imageStoragePath"] as? String

    // Detailed Logging: Image restoration on another device
    if (!imageUrlSec.isNullOrEmpty()) {
        Log.d("SyncManager", "Image restoration on another device: Restored imageUrl = $imageUrlSec, storagePath = $imageStoragePathSec for noteId = ${this["noteId"]}")
    }

    // Reconstruct local imageUri and attachmentUri pointing to cache
    val imageAttachments = attachments.filter { it.mimeType.startsWith("image/") }
    val nonImageAttachments = attachments.filter { !it.mimeType.startsWith("image/") }
    
    val imageUriSec = if (imageAttachments.isNotEmpty()) {
        imageAttachments.map { att ->
            val cachedFile = AttachmentCacheManager.getLocalCacheFile(context, (this["noteId"] as? String) ?: "", att.fileName)
            Uri.fromFile(cachedFile).toString()
        }.joinToString("|")
    } else imageUrlSec
    
    val attachmentUriSec = if (nonImageAttachments.isNotEmpty()) {
        val att = nonImageAttachments.first()
        val cachedFile = AttachmentCacheManager.getLocalCacheFile(context, (this["noteId"] as? String) ?: "", att.fileName)
        Uri.fromFile(cachedFile).toString()
    } else null

    return Note(
        id = localId,
        noteId = (this["noteId"] as? String) ?: UUID.randomUUID().toString(),
        title = (this["title"] as? String) ?: "",
        content = (this["content"] as? String) ?: "",
        timestamp = (this["timestamp"] as? Long) ?: System.currentTimeMillis(),
        category = (this["category"] as? String) ?: "General",
        colorIndex = (this["colorIndex"] as? Long)?.toInt() ?: 0,
        isPinned = (this["isPinned"] as? Boolean) ?: false,
        imageUri = imageUriSec,
        isTaskList = (this["isTaskList"] as? Boolean) ?: false,
        webUrl = (this["webUrl"] as? String),
        isScheduled = (this["isScheduled"] as? Boolean) ?: false,
        scheduledTime = (this["scheduledTime"] as? Long),
        repeatFrequency = (this["repeatFrequency"] as? String),
        position = (this["position"] as? Long)?.toInt() ?: 0,
        tags = (this["tags"] as? String),
        autoTags = (this["autoTags"] as? String),
        attachmentUri = attachmentUriSec,
        createdAt = (this["createdAt"] as? Long) ?: System.currentTimeMillis(),
        updatedAt = (this["updatedAt"] as? Long) ?: System.currentTimeMillis(),
        deletedAt = (this["deletedAt"] as? Long),
        attachmentsJson = attachmentsJsonStr,
        imageUrl = imageUrlSec,
        imageStoragePath = imageStoragePathSec,
        isPersistentNotification = (this["isPersistentNotification"] as? Boolean) ?: false,
        isArchived = (this["isArchived"] as? Boolean) ?: false
    )
}
