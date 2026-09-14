package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.data.AppDatabase
import com.example.data.formatNoteForCopying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CopyNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val copyText = intent.getStringExtra("copy_text")
        val noteId = intent.getIntExtra("note_id", -1)

        if (!copyText.isNullOrEmpty()) {
            copyToClipboard(copyText)
            finish()
            return
        }

        if (noteId != -1) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val note = db.noteDao().getNoteById(noteId)
                    val textToCopy = if (note != null) formatNoteForCopying(note) else ""
                    withContext(Dispatchers.Main) {
                        if (textToCopy.isNotEmpty()) {
                            copyToClipboard(textToCopy)
                        }
                        finish()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        finish()
                    }
                }
            }
        } else {
            finish()
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Note", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Note copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
