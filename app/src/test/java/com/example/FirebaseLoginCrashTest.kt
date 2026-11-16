package com.example

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.NoteRepository
import com.example.sync.AttachmentCacheManager
import com.example.sync.FirebaseConfig
import com.example.sync.SyncManager
import com.example.sync.toNote
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseLoginCrashTest {

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FirebaseApp.getApps(context).toList().forEach {
            try { it.delete() } catch (_: Exception) {}
        }
        context.getSharedPreferences("firebase_sync_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun named_firebase_app_sdk_initialization_and_save_preserves_instance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(FirebaseApp.getApps(context).isEmpty())

        val config = FirebaseConfig(
            apiKey = "AIzaSyDummyKeyForTest1234567890",
            appId = "1:123456789012:android:abcdef1234567890",
            projectId = "test-pinotes-proj",
            storageBucket = "test-pinotes-proj.firebasestorage.app",
            isEnabled = true
        )
        FirebaseConfig.save(context, config)
        val app = FirebaseConfig.initialize(context, config)
        assertNotNull("Named FirebaseApp should initialize", app)

        val auth = FirebaseAuth.getInstance(app!!)
        assertNotNull("FirebaseAuth should initialize", auth)
        assertNotNull("FirebaseFirestore should initialize", FirebaseFirestore.getInstance(app))
        assertNotNull("FirebaseStorage should initialize", FirebaseStorage.getInstance(app))

        // Updating userEmail/userPassword mid-login should NOT delete & recreate FirebaseApp
        FirebaseConfig.save(context, config.copy(userEmail = "a@b.com", userPassword = "secret"))
        val appAfterSave = FirebaseConfig.getApp()
        assertSame("Same FirebaseApp instance should be kept when project options unchanged", app, appAfterSave)
    }

    @Test
    fun gs_and_https_storage_bucket_urls_normalize_cleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val variants = listOf(
            "gs://test-pinotes-proj.firebasestorage.app/",
            "gs://test-pinotes-proj.appspot.com/some/path",
            "https://test-pinotes-proj.firebasestorage.app/",
            "  test-pinotes-proj.firebasestorage.app  ",
            ""
        )

        variants.forEach { rawBucket ->
            FirebaseApp.getApps(context).toList().forEach {
                try { it.delete() } catch (_: Exception) {}
            }
            val config = FirebaseConfig(
                apiKey = "AIzaSyDummyKeyForTest1234567890",
                appId = "1:123456789012:android:abcdef1234567890",
                projectId = "test-pinotes-proj",
                storageBucket = rawBucket,
                isEnabled = true
            )
            FirebaseConfig.save(context, config)
            val app = FirebaseConfig.initialize(context, config)
            assertNotNull("App should init for bucket='$rawBucket'", app)
            val storage = FirebaseStorage.getInstance(app!!)
            assertNotNull("FirebaseStorage should init without IllegalArgumentException for bucket='$rawBucket'", storage)
        }
    }

    @Test
    fun web_synced_note_with_data_uri_image_decodes_and_stores_in_room() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = NoteRepository(db.noteDao())
            val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(fakeJpeg, Base64.NO_WRAP)

            val firestoreDoc: Map<String, Any?> = mapOf(
                "noteId" to "web-note-1",
                "title" to "Web Photo Note",
                "content" to "Synced from web",
                "timestamp" to 1700000000000.0,
                "colorIndex" to 2.0,
                "isPinned" to true,
                "imageUrl" to dataUrl,
                "attachments" to listOf(
                    mapOf(
                        "fileName" to "users/u1/notes/web-note-1/photo.jpg",
                        "mimeType" to "image/jpeg",
                        "fileSize" to 4.0,
                        "storagePath" to "",
                        "downloadUrl" to dataUrl
                    )
                ),
                "createdAt" to 1700000000000L,
                "updatedAt" to 1700000000500L
            )

            val note = firestoreDoc.toNote(context)
            assertTrue("imageUri should resolve to cached file:// URI", note.imageUri?.startsWith("file://") == true)
            assertEquals(2, note.colorIndex)
            assertTrue(note.isPinned)

            val insertedId = repo.insertFromSync(note)
            assertTrue(insertedId > 0)
            val all = repo.getAllNotesDirect()
            assertEquals(1, all.size)
            assertEquals("Web Photo Note", all[0].title)
            assertTrue(all[0].imageUris.first().startsWith("file://"))

            val cacheFile = AttachmentCacheManager.getLocalCacheFile(context, "web-note-1", "users/u1/notes/web-note-1/photo.jpg")
            assertFalse("Sanitized filename should not contain slashes", cacheFile.name.contains('/'))
        } finally {
            db.close()
        }
    }

    @Test
    fun loginOrRegisterUser_invalid_config_never_throws() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = SyncManager.loginOrRegisterUser(context, "user@example.com", "pass123")
        assertFalse(result)
        assertNotNull(SyncManager.lastAuthError.value)
    }
}
