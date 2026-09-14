package com.example

import android.os.Bundle
import com.example.sync.SyncManager
import com.example.sync.FirebaseConfig
import com.example.sync.SyncStatus
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import kotlin.math.roundToInt
import androidx.core.app.ActivityCompat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import com.example.ui.CardTablePreview
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.AppDatabase
import com.example.data.Note
import com.example.data.NoteRepository
import com.example.ui.NoteViewModel
import com.example.ui.NoteViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.graphics.graphicsLayer
import org.json.JSONArray
import org.json.JSONObject

class DynamicTextDelegate(
    val getValue: () -> TextFieldValue,
    val setValue: (TextFieldValue) -> Unit
) {
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String {
        return getValue().text
    }
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
        setValue(TextFieldValue(value, TextRange(value.length)))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ensure notification channel is initialized for Android O+
        createNotificationChannel()

        // Local Room DB Setup
        val database = AppDatabase.getDatabase(this)
        val repository = NoteRepository(database.noteDao())
        
        // Initialize dynamic Firebase Sync Engine on app start
        SyncManager.initializeSync(this, repository)
        
        val viewModelFactory = NoteViewModelFactory(repository, this)
        val viewModel = ViewModelProvider(this, viewModelFactory)[NoteViewModel::class.java]

        setContent {
            val appSettings = remember { getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
            var scaleState by remember { mutableStateOf(appSettings.getFloat("app_ui_scale", 1.0f)) }

            val currentDensity = androidx.compose.ui.platform.LocalDensity.current
            val adjustedDensity = androidx.compose.ui.unit.Density(
                density = currentDensity.density * scaleState,
                fontScale = currentDensity.fontScale * scaleState
            )

            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides adjustedDensity
            ) {
                MyApplicationTheme {
                    Scaffold(
                        modifier = Modifier.fillMaxSize()
                    ) { innerPadding ->
                        MainScreen(
                            viewModel = viewModel,
                            modifier = Modifier.padding(innerPadding),
                            onScaleChange = { newScale ->
                                scaleState = newScale
                            }
                        )
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val descriptionText = "Channel for pushing individual notes as system notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun pushNoteNotification(context: Context, note: Note) {
        companionPushNoteNotification(context, note)
    }

    companion object {
        const val CHANNEL_ID = "notes_push_notifications"
        const val CHANNEL_NAME = "Note Notifications"

        fun companionPushNoteNotification(context: Context, note: Note) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("open_note_id", note.id)
                }
                val pendingIntent: PendingIntent = PendingIntent.getActivity(
                    context,
                    note.id,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                var title = note.title.ifEmpty { "Untitled Note" }
                if (!note.attachmentUri.isNullOrEmpty()) {
                    val attachmentName = try {
                        val uri = android.net.Uri.parse(note.attachmentUri)
                        val decodedPath = android.net.Uri.decode(uri.toString())
                        val lastSegment = decodedPath.substringAfterLast('/')
                        val cleanName = lastSegment.substringBefore('?')
                        if (cleanName.isNotEmpty() && cleanName != "null") cleanName else "Document"
                    } catch (e: Exception) {
                        "Document"
                    }
                    title = if (note.title.isEmpty()) "📎 $attachmentName" else "📎 [$attachmentName] - $title"
                }
                
                // Format content text and rich big text representation based on isTaskList
                val displayContent: String
                val richBigText: String
                if (note.isTaskList) {
                    val items = com.example.data.parseTaskItems(note.content)
                    val checkedCount = items.count { it.isChecked }
                    val totalCount = items.size
                    displayContent = "Progress: $checkedCount/$totalCount tasks completed"
                    richBigText = items.mapIndexed { idx, item ->
                        val statusSymbol = if (item.isChecked) "☑" else "☐"
                        "${idx + 1}. $statusSymbol  ${item.text}"
                    }.joinToString("\n")
                } else {
                    displayContent = note.content
                    richBigText = note.content
                }

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(title)
                    .setContentText(displayContent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(!note.isPersistentNotification)
                    .setOngoing(note.isPersistentNotification)

                val bigTextStyle = NotificationCompat.BigTextStyle()
                    .bigText(richBigText)
                    .setBigContentTitle(title)
                
                builder.setStyle(bigTextStyle)

                var actionsCount = 0

                if (note.isPersistentNotification) {
                    val swipeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                        action = "com.example.ACTION_PERSISTENT_SWIPED"
                        putExtra("note_id", note.id)
                    }
                    val swipePendingIntent = PendingIntent.getBroadcast(
                        context,
                        note.id * 1000 + 777,
                        swipeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setDeleteIntent(swipePendingIntent)

                    val dismissIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                        action = "com.example.ACTION_DISMISS_NOTIFICATION"
                        putExtra("note_id", note.id)
                    }
                    val dismissPendingIntent = PendingIntent.getBroadcast(
                        context,
                        note.id * 1000 + 999,
                        dismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Done", dismissPendingIntent)
                    actionsCount++
                }

                // 1. Add text edit reply action (Highest priority, always added first)
                val replyLabel = if (note.isTaskList) "Type item name or number to check, or add item" else "Edit note body"
                val remoteInput = androidx.core.app.RemoteInput.Builder("key_note_reply")
                    .setLabel(replyLabel)
                    .build()
                val replyActionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "com.example.ACTION_REPLY_EDIT"
                    putExtra("note_id", note.id)
                }
                val replyActionPendingIntent = PendingIntent.getBroadcast(
                    context,
                    note.id * 1000 + 888,
                    replyActionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                val replyAction = NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_edit,
                    "Edit / Add",
                    replyActionPendingIntent
                ).addRemoteInput(remoteInput).build()
                builder.addAction(replyAction)
                actionsCount++

                // 2. Add copy note text action directly from notification
                val formattedCopyText = com.example.data.formatNoteForCopying(note)
                val copyIntent = Intent(context, CopyNoteActivity::class.java).apply {
                    putExtra("note_id", note.id)
                    putExtra("copy_text", formattedCopyText)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
                val copyPendingIntent = PendingIntent.getActivity(
                    context,
                    note.id * 1000 + 333,
                    copyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_save, "Copy", copyPendingIntent)
                actionsCount++

                // 2. Add document/attachment direct open action button (Second priority)
                if (!note.attachmentUri.isNullOrEmpty() && actionsCount < 3) {
                    try {
                        val fileUri = Uri.parse(note.attachmentUri)
                        val file = java.io.File(fileUri.path ?: "")
                        if (file.exists()) {
                            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "com.example.fileprovider",
                                file
                            )
                            val mimeType = getMimeTypeForFile(file)
                            val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(contentUri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val openFilePendingIntent = PendingIntent.getActivity(
                                context,
                                note.id * 1000 + 444,
                                openFileIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            builder.addAction(android.R.drawable.ic_menu_save, "Open File", openFilePendingIntent)
                            actionsCount++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 3. Extract first available web URL and append a direct deep linking notification action (Third priority)
                val urlPattern = """(https?://[^\s$.?#].[^\s]*)""".toRegex()
                val urlFind = urlPattern.find(note.content)
                val extractedUrl = if (!note.webUrl.isNullOrEmpty()) note.webUrl else urlFind?.value

                if (extractedUrl != null && actionsCount < 3) {
                    try {
                        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(extractedUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val openPendingIntent = PendingIntent.getActivity(
                            context,
                            note.id * 1000 + 499,
                            openIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val actionLabel = if (extractedUrl.contains("maps") || extractedUrl.contains("goo.gl")) {
                            "Open in Maps"
                        } else {
                            "Open Link"
                        }
                        builder.addAction(0, actionLabel, openPendingIntent)
                        actionsCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 4. Add interactive checklist action channels if space is still available
                if (note.isTaskList) {
                    val items = com.example.data.parseTaskItems(note.content)
                    items.forEachIndexed { index, item ->
                        if (actionsCount < 3) {
                            val actionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                                putExtra("note_id", note.id)
                                putExtra("item_index", index)
                            }
                            val requestCode = note.id * 1000 + index
                            val actionPendingIntent = PendingIntent.getBroadcast(
                                context,
                                requestCode,
                                actionIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            val actionLabel = if (item.isChecked) "✗ ${item.text}" else "✓ ${item.text}"
                            builder.addAction(0, actionLabel, actionPendingIntent)
                            actionsCount++
                        }
                    }
                }

                val firstImage = note.imageUris.firstOrNull()
                if (!firstImage.isNullOrEmpty()) {
                    val bitmap = kotlinx.coroutines.withTimeoutOrNull(1000L) {
                        loadBitmapFromUri(context, firstImage)
                    }
                    if (bitmap != null) {
                        val bigPictureStyle = NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                            .setBigContentTitle(title)
                            .setSummaryText(displayContent)
                        builder.setStyle(bigPictureStyle)
                        builder.setLargeIcon(bitmap)
                    }
                }

                try {
                    with(NotificationManagerCompat.from(context)) {
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        ) {
                            notify(note.id, builder.build())
                        } else {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, "Notifications permission is required to push notes.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "Failed to send notification: permission required.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fun loadBitmapFromUri(context: Context, uriString: String?): Bitmap? {
            if (uriString == null) return null
            return try {
                if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(uriString).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } else null
                        } else null
                    }
                } else {
                    val uri = Uri.parse(uriString)
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    bitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun copyUriToLocalFile(context: Context, sourceUri: Uri): Uri? {
            try {
                val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return null
                val file = File(context.filesDir, "img_${System.currentTimeMillis()}.jpg")
                val outputStream = FileOutputStream(file)
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                return Uri.fromFile(file)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        fun copyUriToLocalFileWithRealName(context: Context, sourceUri: Uri): Uri? {
            try {
                val contentResolver = context.contentResolver
                var displayName = "file_${System.currentTimeMillis()}"
                val cursor = contentResolver.query(sourceUri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            displayName = name
                        }
                    }
                    cursor.close()
                } else {
                    val lastSegment = sourceUri.lastPathSegment
                    if (!lastSegment.isNullOrBlank()) {
                        displayName = lastSegment
                    }
                }
                
                // Sanitize filename to prevent directory traversal or invalid characters
                val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val file = File(context.filesDir, safeName)
                
                val inputStream = contentResolver.openInputStream(sourceUri) ?: return null
                val outputStream = FileOutputStream(file)
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                return Uri.fromFile(file)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        fun getMimeTypeForFile(file: File): String {
            val extension = file.extension.lowercase()
            if (extension.isNotEmpty()) {
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                if (!mime.isNullOrEmpty()) return mime
            }
            return when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "txt" -> "text/plain"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "xml" -> "text/xml"
                "zip" -> "application/zip"
                "rar" -> "application/x-rar-compressed"
                "7z" -> "application/x-7z-compressed"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                else -> "*/*"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: NoteViewModel,
    modifier: Modifier = Modifier,
    onScaleChange: (Float) -> Unit
) {
    val notes by viewModel.notesState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val isGridLayout by viewModel.isGridLayout.collectAsStateWithLifecycle()
    val categoriesList by viewModel.categories.collectAsStateWithLifecycle()
    val hiddenCategories by viewModel.hiddenCategories.collectAsStateWithLifecycle()

    val allCategoryTabs = remember(categoriesList) { listOf<String?>(null) + categoriesList }
    val categoryChipsListState = rememberLazyListState()

    val context = LocalContext.current
    var lastSelectedNoteToNotify by remember { mutableStateOf<Note?>(null) }

    // Synchronize category chips scroll with active category selection
    LaunchedEffect(selectedCategory, categoriesList) {
        val selectedCategoryIndex = allCategoryTabs.indexOf(selectedCategory).coerceAtLeast(0)
        categoryChipsListState.animateScrollToItem(selectedCategoryIndex)
    }

    // Multi-Selection and Reordering states
    var selectedNoteIds by remember { mutableStateOf(setOf<Int>()) }
    val isInSelectionMode by remember(selectedNoteIds) { derivedStateOf { selectedNoteIds.isNotEmpty() } }

    var localNotesList by remember { mutableStateOf<List<Note>>(emptyList()) }
    var draggedNoteId by remember { mutableStateOf<Int?>(null) }
    var dragOffsetAccumulated by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()

    LaunchedEffect(notes) {
        if (draggedNoteId == null) {
            localNotesList = notes
        }
    }

    var showEditorDialog by remember { mutableStateOf(false) }
    var activeNoteForEdit by remember { mutableStateOf<Note?>(null) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var fullScreenImageUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(context, notes) {
        val activity = context as? MainActivity
        val intent = activity?.intent
        if (intent != null && intent.hasExtra("open_note_id")) {
            val noteId = intent.getIntExtra("open_note_id", -1)
            if (noteId != -1) {
                val targetNote = notes.find { it.id == noteId }
                if (targetNote != null) {
                    activeNoteForEdit = targetNote
                    showEditorDialog = true
                    intent.removeExtra("open_note_id")
                }
            }
        }
    }
    
    val scope = rememberCoroutineScope()
    
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var categoryToRearrange by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showImportBackupDialog by remember { mutableStateOf(false) }
    var showGoogleLoginDialog by remember { mutableStateOf(false) }

    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var isLoggedIn by remember { mutableStateOf(sharedPrefs.getBoolean("is_logged_in", false)) }
    var userEmail by remember { mutableStateOf(sharedPrefs.getString("user_email", "") ?: "") }
    var userName by remember { mutableStateOf(sharedPrefs.getString("user_name", "") ?: "") }

    // Firebase Decentralized Sync States
    val firebaseLoggedIn by SyncManager.isUserLoggedIn.collectAsStateWithLifecycle()
    val firebaseUserEmail by SyncManager.loggedInUserEmail.collectAsStateWithLifecycle()
    val syncStatus by SyncManager.syncStatus.collectAsStateWithLifecycle()

    LaunchedEffect(syncStatus) {
        if (syncStatus == SyncStatus.SYNCED) {
            viewModel.refreshCategoriesAndTags()
        }
    }

    var isAlwaysCompact by remember { mutableStateOf(sharedPrefs.getBoolean("always_compact", false)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            lastSelectedNoteToNotify?.let { note ->
                (context as? MainActivity)?.pushNoteNotification(context, note)
                Toast.makeText(context, "Note pushed to notifications!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Permission was denied. Cannot push notifications.", Toast.LENGTH_SHORT).show()
        }
        lastSelectedNoteToNotify = null
    }

    val onPushNotificationClick: (Note) -> Unit = { note ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                (context as? MainActivity)?.pushNoteNotification(context, note)
                Toast.makeText(context, "Note pushed to notifications!", Toast.LENGTH_SHORT).show()
            } else {
                lastSelectedNoteToNotify = note
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            (context as? MainActivity)?.pushNoteNotification(context, note)
            Toast.makeText(context, "Note pushed to notifications!", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Sleek Header with PINotes and compact action buttons
        var isSearchExpanded by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (isInSelectionMode) {
                // Multi-Selection App Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = { selectedNoteIds = emptySet() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Selection",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${selectedNoteIds.size} selected",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val currentSelected = localNotesList.filter { selectedNoteIds.contains(it.id) }
                                    val anyUnpinned = currentSelected.any { !it.isPinned }
                                    currentSelected.forEach { note ->
                                        viewModel.updateNoteRaw(note.copy(isPinned = anyUnpinned))
                                    }
                                    Toast.makeText(context, if (anyUnpinned) "Pinned selected notes" else "Unpinned selected notes", Toast.LENGTH_SHORT).show()
                                    selectedNoteIds = emptySet()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Toggle Pin Selected",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                if (selectedNoteIds.isNotEmpty()) {
                                    val count = selectedNoteIds.size
                                    viewModel.archiveSelectedNotes(selectedNoteIds)
                                    Toast.makeText(context, "$count note(s) archived", Toast.LENGTH_SHORT).show()
                                    selectedNoteIds = emptySet()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Archive,
                                contentDescription = "Archive Selected",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                if (selectedNoteIds.isNotEmpty()) {
                                    scope.launch {
                                        val count = selectedNoteIds.size
                                        selectedNoteIds.forEach { id ->
                                            val note = localNotesList.find { it.id == id }
                                            if (note != null) {
                                                viewModel.deleteNote(note)
                                            }
                                        }
                                        Toast.makeText(context, "$count notes deleted", Toast.LENGTH_SHORT).show()
                                        selectedNoteIds = emptySet()
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isSearchExpanded) {
                        // Sleek expanding Search Text Field inline
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                                .testTag("search_bar"),
                            placeholder = { Text("Search PINotes...", fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { 
                                    viewModel.setSearchQuery("")
                                    isSearchExpanded = false 
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Search",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            )
                        )
                    } else {
                        // Compact App Title "PINotes" with animated logo transforming from a pin to text
                        AnimatedLogoHeader()
                           // Row of compact action icons wrapped inside a beautifully styled squircle pill container
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.5.dp),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            if (!isSearchExpanded) {
                                AppTooltip(text = "Search Notes") {
                                    IconButton(
                                        onClick = { isSearchExpanded = true },
                                        modifier = Modifier
                                            .testTag("search_toggle_button")
                                            .size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search Notes",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // 2-Way Sync / Refresh Button
                            AppTooltip(text = "Trigger 2-way Sync") {
                                IconButton(
                                    onClick = {
                                        val confState = FirebaseConfig.load(context)
                                        if (!confState.isValid()) {
                                            Toast.makeText(context, "Please configure Firebase setting details first.", Toast.LENGTH_SHORT).show()
                                            showGoogleLoginDialog = true
                                        } else if (!firebaseLoggedIn) {
                                            Toast.makeText(context, "Please connect account in Firebase settings first.", Toast.LENGTH_SHORT).show()
                                            showGoogleLoginDialog = true
                                        } else {
                                            Toast.makeText(context, "Initiating Private Firebase Sync...", Toast.LENGTH_SHORT).show()
                                            scope.launch {
                                                SyncManager.triggerSyncNow(context, viewModel.repository)
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .testTag("sync_refresh_button")
                                        .size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = "2-way Cloud Sync",
                                        tint = when (syncStatus) {
                                            SyncStatus.SYNCING -> MaterialTheme.colorScheme.primary
                                            SyncStatus.SYNCED -> Color(0xFF4CAF50)
                                            SyncStatus.ERROR -> Color(0xFFF44336)
                                            SyncStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Firebase Private Sync Profile / Account Button
                            AppTooltip(text = if (firebaseLoggedIn) "Firebase Connected ($firebaseUserEmail)" else "Configure Firebase Sync") {
                                IconButton(
                                    onClick = { showGoogleLoginDialog = true },
                                    modifier = Modifier
                                        .testTag("google_profile_button")
                                        .size(30.dp)
                                ) {
                                    if (firebaseLoggedIn) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        ) {
                                            Text(
                                                text = (firebaseUserEmail ?: "F").take(1).uppercase(Locale.getDefault()),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontSize = 10.sp
                                                )
                                            )
                                            // Small clean status dot based on SyncStatus
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(
                                                        color = when (syncStatus) {
                                                            SyncStatus.SYNCED -> Color(0xFF4CAF50)
                                                            SyncStatus.SYNCING -> Color(0xFFFFEB3B)
                                                            SyncStatus.ERROR -> Color(0xFFF44336)
                                                            SyncStatus.OFFLINE -> Color(0xFF9E9E9E)
                                                        },
                                                        shape = CircleShape
                                                    )
                                                    .align(Alignment.BottomEnd)
                                                    .border(0.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = "Configure Private Firebase Sync",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Layout Toggle Mode
                            AppTooltip(text = if (isGridLayout) "Switch to List View" else "Switch to Grid View") {
                                IconButton(
                                    onClick = { viewModel.toggleLayout() },
                                    modifier = Modifier
                                        .testTag("layout_toggle_button")
                                        .size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isGridLayout) Icons.Default.List else Icons.Default.GridView,
                                        contentDescription = if (isGridLayout) "Switch to List View" else "Switch to Grid View",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Settings Gear Icon Button
                            AppTooltip(text = "App Settings") {
                                IconButton(
                                    onClick = { showSettingsDialog = true },
                                    modifier = Modifier
                                        .testTag("settings_button")
                                        .size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "App Settings",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    } // Closes Surface
                } // Closes else
            } // Close the main top Header Row
            }

            // Horizontal scroll area with fixed Sort options toggle on the left
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(16.dp))
                // Fixed Sort Toggle Button on the left
                AppTooltip(text = "Sort Options") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .testTag("sort_filter_toggle_button")
                            .clickable { showSortDialog = true }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort Notes",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Horizontal Scrollable Category Filter Chips next to it
                LazyRow(
                    state = categoryChipsListState,
                    modifier = Modifier
                        .weight(1f),
                    contentPadding = PaddingValues(start = 10.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // "All" filter chip
                    item {
                        InteractiveCategoryChip(
                            text = "All Notes",
                            isSelected = selectedCategory == null,
                            onClick = { viewModel.setSelectedCategory(null) },
                            onLongClick = { Toast.makeText(context, "Show all notes without any category filtering", Toast.LENGTH_SHORT).show() },
                            leadingIcon = if (selectedCategory == null) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            testTag = "category_filter_chip_all"
                        )
                    }

                    // Dedicated core category chips using custom InteractiveCategoryChip supporting long taps
                    items(categoriesList) { category ->
                        val isHiddenFromAll = hiddenCategories.contains(category)
                        InteractiveCategoryChip(
                            text = category,
                            isSelected = selectedCategory == category,
                            onClick = { viewModel.setSelectedCategory(category) },
                            onLongClick = { 
                                categoryToRearrange = category
                                Toast.makeText(context, "Manage '$category' tag: Rename, hide, reorder, or delete.", Toast.LENGTH_SHORT).show()
                            },
                            leadingIcon = if (selectedCategory == category || isHiddenFromAll) {
                                {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        if (selectedCategory == category) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        if (isHiddenFromAll) {
                                            Icon(
                                                imageVector = Icons.Default.VisibilityOff,
                                                contentDescription = "Hidden from All Notes",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            } else null,
                            testTag = "category_filter_chip_$category"
                        )
                    }

                    // Inline custom Category Tag Creator represented by an elegant Plus Icon Chip
                    item {
                        InteractiveCategoryChip(
                            text = "New Tag",
                            isSelected = false,
                            onClick = { showAddCategoryDialog = true },
                            onLongClick = { Toast.makeText(context, "Create a new custom category folder to organize notes", Toast.LENGTH_SHORT).show() },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add custom category tag",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            borderColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            testTag = "category_filter_chip_add"
                        )
                    }
                }
            }

            // Active Tag Filters Bar
            val activeIncTags by viewModel.includedTags.collectAsStateWithLifecycle()
            val activeExcTags by viewModel.excludedTags.collectAsStateWithLifecycle()
            if (activeIncTags.isNotEmpty() || activeExcTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Filtered tags:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        items(activeIncTags.toList()) { tag ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSystemInDarkTheme()) Color(0xFF1E4620) else Color(0xFFD1E7DD),
                                border = BorderStroke(1.dp, if (isSystemInDarkTheme()) Color(0xFF86EFAC) else Color(0xFF0F5132)),
                                modifier = Modifier.clickable { viewModel.toggleTagFilter(tag) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Included tag",
                                        tint = if (isSystemInDarkTheme()) Color(0xFF86EFAC) else Color(0xFF0F5132),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSystemInDarkTheme()) Color(0xFF86EFAC) else Color(0xFF0F5132),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        items(activeExcTags.toList()) { tag ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSystemInDarkTheme()) Color(0xFF4A1212) else Color(0xFFF8D7DA),
                                border = BorderStroke(1.dp, if (isSystemInDarkTheme()) Color(0xFFFCA5A5) else Color(0xFF842029)),
                                modifier = Modifier.clickable { viewModel.toggleTagFilter(tag) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Excluded tag",
                                        tint = if (isSystemInDarkTheme()) Color(0xFFFCA5A5) else Color(0xFF842029),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSystemInDarkTheme()) Color(0xFFFCA5A5) else Color(0xFF842029),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = { viewModel.clearTagFilters() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear tag filters",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Notes State Display (Empty or Filled List/Grid)
            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(96.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.NoteAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty() || selectedCategory != null) "No matching notes" else "Capture your thoughts",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty() || selectedCategory != null)
                                "Try adjusting your search queries or category filters to find what you need."
                            else
                                "Jot down tasks, brilliant ideas, school work, or deep memories. Tap the button below to write your first note!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            maxLines = 4,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = Color.Transparent
                ) {
                    if (isGridLayout) {
                        // Adaptive grid with 2 columns
                        LazyVerticalStaggeredGrid(
                            state = gridState,
                            columns = StaggeredGridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp, top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalItemSpacing = 10.dp
                        ) {
                            items(localNotesList, key = { it.id }) { note ->
                                val dragModifier = Modifier.pointerInput(note.id, localNotesList) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            draggedNoteId = note.id
                                            dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                            selectedNoteIds = selectedNoteIds + note.id
                                        },
                                        onDragEnd = {
                                            draggedNoteId = null
                                            dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                        },
                                        onDragCancel = {
                                            draggedNoteId = null
                                            dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetAccumulated += dragAmount
                                            
                                            val currentDraggedId = draggedNoteId
                                            if (currentDraggedId != null) {
                                                val fromIdx = localNotesList.indexOfFirst { it.id == currentDraggedId }
                                                if (fromIdx != -1) {
                                                    val layoutInfo = gridState.layoutInfo
                                                    val visibleItems = layoutInfo.visibleItemsInfo
                                                    val draggedItem = visibleItems.find { it.key == currentDraggedId }
                                                    if (draggedItem != null) {
                                                        val dragCenterX = draggedItem.offset.x + draggedItem.size.width / 2 + dragOffsetAccumulated.x
                                                        val dragCenterY = draggedItem.offset.y + draggedItem.size.height / 2 + dragOffsetAccumulated.y
                                                        
                                                        val targetItem = visibleItems.find { item ->
                                                            item.key != currentDraggedId &&
                                                            dragCenterX >= item.offset.x && dragCenterX <= item.offset.x + item.size.width &&
                                                            dragCenterY >= item.offset.y && dragCenterY <= item.offset.y + item.size.height
                                                        }
                                                        if (targetItem != null) {
                                                            val toIdx = localNotesList.indexOfFirst { it.id == targetItem.key }
                                                            if (toIdx != -1 && toIdx != fromIdx) {
                                                                val newList = localNotesList.toMutableList()
                                                                val tempItem = newList.removeAt(fromIdx)
                                                                newList.add(toIdx, tempItem)
                                                                localNotesList = newList
                                                                dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                                                viewModel.updateNotePositions(newList)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }

                                NoteCard(
                                    note = note,
                                    onEdit = {
                                        if (isInSelectionMode) {
                                            selectedNoteIds = if (selectedNoteIds.contains(note.id)) {
                                                selectedNoteIds - note.id
                                            } else {
                                                selectedNoteIds + note.id
                                            }
                                        } else {
                                            activeNoteForEdit = note
                                            showEditorDialog = true
                                        }
                                    },
                                    onLongClick = if (isInSelectionMode) null else {
                                        { selectedNoteIds = selectedNoteIds + note.id }
                                    },
                                    isSelected = selectedNoteIds.contains(note.id),
                                    onDelete = { noteToDelete = note },
                                    onTogglePin = { viewModel.togglePin(note) },
                                    onPushNotification = { onPushNotificationClick(note) },
                                    onUpdate = { viewModel.updateNote(it) },
                                    onArchive = { viewModel.archiveNote(note) },
                                    isDensityCompact = isAlwaysCompact,
                                    onImageClick = { fullScreenImageUri = it },
                                    modifier = Modifier
                                        .then(dragModifier)
                                        .offset {
                                            if (draggedNoteId == note.id) {
                                                IntOffset(dragOffsetAccumulated.x.roundToInt(), dragOffsetAccumulated.y.roundToInt())
                                            } else {
                                                IntOffset.Zero
                                            }
                                        }
                                        .zIndex(if (draggedNoteId == note.id) 10f else 1f)
                                )
                            }
                        }
                    } else {
                        // Adaptive list layout
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp, top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(localNotesList, key = { it.id }) { note ->
                                val dragModifier = Modifier.pointerInput(note.id, localNotesList) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            draggedNoteId = note.id
                                            dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                            selectedNoteIds = selectedNoteIds + note.id
                                        },
                                        onDragEnd = {
                                            draggedNoteId = null
                                            dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                        },
                                        onDragCancel = {
                                            draggedNoteId = null
                                            dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetAccumulated += dragAmount
                                            
                                            val currentDraggedId = draggedNoteId
                                            if (currentDraggedId != null) {
                                                val fromIdx = localNotesList.indexOfFirst { it.id == currentDraggedId }
                                                if (fromIdx != -1) {
                                                    val layoutInfo = listState.layoutInfo
                                                    val visibleItems = layoutInfo.visibleItemsInfo
                                                    val draggedItem = visibleItems.find { it.key == currentDraggedId }
                                                    if (draggedItem != null) {
                                                        val dragCenterY = draggedItem.offset + draggedItem.size / 2 + dragOffsetAccumulated.y
                                                        val targetItem = visibleItems.find { item ->
                                                            item.key != currentDraggedId &&
                                                            dragCenterY >= item.offset && dragCenterY <= item.offset + item.size
                                                        }
                                                        if (targetItem != null) {
                                                            val toIdx = localNotesList.indexOfFirst { it.id == targetItem.key }
                                                            if (toIdx != -1 && toIdx != fromIdx) {
                                                                val newList = localNotesList.toMutableList()
                                                                val tempItem = newList.removeAt(fromIdx)
                                                                newList.add(toIdx, tempItem)
                                                                localNotesList = newList
                                                                dragOffsetAccumulated = androidx.compose.ui.geometry.Offset.Zero
                                                                viewModel.updateNotePositions(newList)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }

                                NoteCard(
                                    note = note,
                                    onEdit = {
                                        if (isInSelectionMode) {
                                            selectedNoteIds = if (selectedNoteIds.contains(note.id)) {
                                                selectedNoteIds - note.id
                                            } else {
                                                selectedNoteIds + note.id
                                            }
                                        } else {
                                            activeNoteForEdit = note
                                            showEditorDialog = true
                                        }
                                    },
                                    onLongClick = if (isInSelectionMode) null else {
                                        { selectedNoteIds = selectedNoteIds + note.id }
                                    },
                                    isSelected = selectedNoteIds.contains(note.id),
                                    onDelete = { noteToDelete = note },
                                    onTogglePin = { viewModel.togglePin(note) },
                                    onPushNotification = { onPushNotificationClick(note) },
                                    onUpdate = { viewModel.updateNote(it) },
                                    onArchive = { viewModel.archiveNote(note) },
                                    isCompact = true,
                                    isDensityCompact = isAlwaysCompact,
                                    onImageClick = { fullScreenImageUri = it },
                                    modifier = Modifier
                                        .then(dragModifier)
                                        .offset {
                                            if (draggedNoteId == note.id) {
                                                IntOffset(dragOffsetAccumulated.x.roundToInt(), dragOffsetAccumulated.y.roundToInt())
                                            } else {
                                                IntOffset.Zero
                                            }
                                        }
                                        .zIndex(if (draggedNoteId == note.id) 10f else 1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Beautiful floating action button (FAB)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            AppTooltip(text = "Create New Note") {
                FloatingActionButton(
                    onClick = {
                        activeNoteForEdit = null
                        showEditorDialog = true
                    },
                    modifier = Modifier.testTag("add_note_fab"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Note",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Multi-use Note Editor Dialog
        if (showEditorDialog) {
            NoteEditorDialog(
                note = activeNoteForEdit,
                categories = categoriesList,
                onDismiss = { showEditorDialog = false },
                onImageClick = { fullScreenImageUri = it },
                onAutoSave = { title, content, cat, colIdx, pinned, imgUri, isTaskList, webUrl, isScheduled, scheduledTime, repeatFreq, tags, autoT, attachUri, imageUrl, imageStoragePath, isPersistent, isAi ->
                    if (activeNoteForEdit != null) {
                        val updatedNote = activeNoteForEdit!!.copy(
                            title = title,
                            content = content,
                            category = cat,
                            colorIndex = colIdx,
                            isPinned = pinned,
                            imageUri = imgUri,
                            isTaskList = isTaskList,
                            webUrl = webUrl,
                            isScheduled = isScheduled,
                            scheduledTime = scheduledTime,
                            repeatFrequency = repeatFreq,
                            tags = tags,
                            autoTags = autoT,
                            attachmentUri = attachUri,
                            imageUrl = imageUrl,
                            imageStoragePath = imageStoragePath,
                            isPersistentNotification = isPersistent
                        )
                        viewModel.updateNote(updatedNote)
                        activeNoteForEdit = updatedNote
                    }
                },
                onSave = { title, content, cat, colIdx, pinned, imgUri, isTaskList, webUrl, isScheduled, scheduledTime, repeatFreq, tags, autoT, attachUri, imageUrl, imageStoragePath, isPersistent, isAi ->
                    if (activeNoteForEdit == null) {
                        viewModel.insertNote(
                            title = title,
                            content = content,
                            category = cat,
                            colorIndex = colIdx,
                            isPinned = pinned,
                            imageUri = imgUri,
                            isTaskList = isTaskList,
                            webUrl = webUrl,
                            isScheduled = isScheduled,
                            scheduledTime = scheduledTime,
                            repeatFrequency = repeatFreq,
                            tags = tags,
                            autoTags = autoT,
                            attachmentUri = attachUri,
                            imageUrl = imageUrl,
                            imageStoragePath = imageStoragePath,
                            isPersistentNotification = isPersistent
                        ) { insertedNote ->
                            val isMap = com.example.data.WebpageScraper.isMapUrl(webUrl)
                            if (isAi && !webUrl.isNullOrEmpty() && !isMap) {
                                com.example.data.BackgroundCurationManager.startCuration(context, insertedNote.id, webUrl)
                            }
                            if (insertedNote.isPersistentNotification) {
                                MainActivity.companionPushNoteNotification(context, insertedNote)
                            }
                            if (isScheduled && scheduledTime != null) {
                                if (scheduledTime <= System.currentTimeMillis()) {
                                    if (!insertedNote.isPersistentNotification) {
                                        MainActivity.companionPushNoteNotification(context, insertedNote)
                                    }
                                } else {
                                    NotificationScheduler.schedule(context, insertedNote)
                                }
                                Toast.makeText(context, "Note notification scheduled successfully", Toast.LENGTH_SHORT).show()
                            } else if (insertedNote.isPersistentNotification) {
                                Toast.makeText(context, "Persistent notification active in notification shade", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        val updatedNote = activeNoteForEdit!!.copy(
                            title = title,
                            content = content,
                            category = cat,
                            colorIndex = colIdx,
                            isPinned = pinned,
                            imageUri = imgUri,
                            isTaskList = isTaskList,
                            webUrl = webUrl,
                            isScheduled = isScheduled,
                            scheduledTime = scheduledTime,
                            repeatFrequency = repeatFreq,
                            tags = tags,
                            autoTags = autoT,
                            attachmentUri = attachUri,
                            imageUrl = imageUrl,
                            imageStoragePath = imageStoragePath,
                            isPersistentNotification = isPersistent
                        )
                        viewModel.updateNote(updatedNote)
                        val isMap = com.example.data.WebpageScraper.isMapUrl(webUrl)
                        if (isAi && !webUrl.isNullOrEmpty() && !isMap) {
                            com.example.data.BackgroundCurationManager.startCuration(context, updatedNote.id, webUrl)
                        }
                        if (updatedNote.isPersistentNotification) {
                            MainActivity.companionPushNoteNotification(context, updatedNote)
                        } else {
                            if (!isScheduled) {
                                androidx.core.app.NotificationManagerCompat.from(context).cancel(updatedNote.id)
                            }
                        }
                        if (isScheduled && scheduledTime != null) {
                            if (scheduledTime <= System.currentTimeMillis()) {
                                if (!updatedNote.isPersistentNotification) {
                                    MainActivity.companionPushNoteNotification(context, updatedNote)
                                }
                            } else {
                                NotificationScheduler.schedule(context, updatedNote)
                            }
                            Toast.makeText(context, "Note notification scheduled successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            NotificationScheduler.cancel(context, updatedNote.id)
                        }
                    }
                    showEditorDialog = false
                }
            )
        }

        // Delete Note Alert Dialog
        if (noteToDelete != null) {
            AlertDialog(
                onDismissRequest = { noteToDelete = null },
                title = { Text(text = "Delete Note", fontWeight = FontWeight.Bold) },
                text = { Text(text = "Are you sure you want to permanently delete \"${noteToDelete?.title?.ifEmpty { "Untitled Note" }}\"? This action cannot be undone.") },
                confirmButton = {
                    Button(
                        modifier = Modifier.testTag("confirm_delete_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            noteToDelete?.let { viewModel.deleteNote(it) }
                            noteToDelete = null
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { noteToDelete = null }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Sort Options Dialog
        if (showSortDialog) {
            val currentSort by viewModel.sortOrder.collectAsStateWithLifecycle()
            AlertDialog(
                onDismissRequest = { showSortDialog = false },
                title = { Text("Sort Notes") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.sortOrder.value = com.example.ui.SortOrder.LAST_UPDATED
                                    showSortDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSort == com.example.ui.SortOrder.LAST_UPDATED,
                                onClick = {
                                    viewModel.sortOrder.value = com.example.ui.SortOrder.LAST_UPDATED
                                    showSortDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Last Updated (Newest)",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.sortOrder.value = com.example.ui.SortOrder.FIRST_UPDATED
                                    showSortDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSort == com.example.ui.SortOrder.FIRST_UPDATED,
                                onClick = {
                                    viewModel.sortOrder.value = com.example.ui.SortOrder.FIRST_UPDATED
                                    showSortDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "First Updated (Oldest)",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            text = "Filter by Tag",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        val allTags = remember(localNotesList) {
                            localNotesList.flatMap { it.tagList }
                                .map { it.trim().removePrefix("#").trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                                .toList()
                                .sorted()
                        }

                        val includedTags by viewModel.includedTags.collectAsStateWithLifecycle()
                        val excludedTags by viewModel.excludedTags.collectAsStateWithLifecycle()

                        Text(
                            text = "Filter by Tags (Tap 1x: Include, Tap 2x: Exclude)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        if (allTags.isEmpty()) {
                            Text(
                                text = "No custom tags found in your notes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        } else {
                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier.fillMaxWidth().testTag("tag_filter_row_in_dialog"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    androidx.compose.material3.FilterChip(
                                        selected = includedTags.isEmpty() && excludedTags.isEmpty(),
                                        onClick = { viewModel.clearTagFilters() },
                                        label = { Text("All") },
                                        modifier = Modifier.testTag("tag_filter_all_sort_dialog")
                                    )
                                }
                                items(allTags) { tag ->
                                    val isInc = includedTags.any { it.equals(tag, ignoreCase = true) }
                                    val isExc = excludedTags.any { it.equals(tag, ignoreCase = true) }

                                    val isDark = isSystemInDarkTheme()
                                    val chipBg = when {
                                        isInc -> if (isDark) Color(0xFF1E4620) else Color(0xFFD1E7DD)
                                        isExc -> if (isDark) Color(0xFF4A1212) else Color(0xFFF8D7DA)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                    val chipText = when {
                                        isInc -> if (isDark) Color(0xFF86EFAC) else Color(0xFF0F5132)
                                        isExc -> if (isDark) Color(0xFFFCA5A5) else Color(0xFF842029)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }

                                    androidx.compose.material3.FilterChip(
                                        selected = isInc || isExc,
                                        onClick = { viewModel.toggleTagFilter(tag) },
                                        label = {
                                            Text(
                                                text = when {
                                                    isInc -> "+#$tag"
                                                    isExc -> "-#$tag"
                                                    else -> "#$tag"
                                                },
                                                fontWeight = if (isInc || isExc) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = chipBg,
                                            selectedLabelColor = chipText,
                                            selectedLeadingIconColor = chipText,
                                            containerColor = chipBg,
                                            labelColor = chipText
                                        ),
                                        modifier = Modifier.testTag("tag_filter_${tag}_sort_dialog")
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSortDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        // Import Backup Dialog
        if (showImportBackupDialog) {
            var backupJsonInput by remember { mutableStateOf("") }
            var importError by remember { mutableStateOf<String?>(null) }

            AlertDialog(
                onDismissRequest = { showImportBackupDialog = false },
                title = { Text("Import Notes Backup", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Paste your exported PINotes JSON backup content below. This will add the imported notes to your current database.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        OutlinedTextField(
                            value = backupJsonInput,
                            onValueChange = {
                                backupJsonInput = it
                                importError = null
                            },
                            placeholder = { Text("Paste JSON here...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .testTag("backup_json_input_field"),
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            maxLines = 10
                        )

                        if (importError != null) {
                            Text(
                                text = importError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (backupJsonInput.trim().isEmpty()) {
                                importError = "Please paste a valid JSON array backup first."
                                return@Button
                            }
                            try {
                                val jsonArray = JSONArray(backupJsonInput.trim())
                                val count = jsonArray.length()
                                if (count == 0) {
                                    importError = "Backup JSON is empty."
                                    return@Button
                                }

                                for (i in 0 until count) {
                                    val obj = jsonArray.getJSONObject(i)
                                    val title = obj.optString("title", "")
                                    val content = obj.optString("content", "")
                                    val category = obj.optString("category", "General")
                                    val colorIndex = obj.optInt("colorIndex", 0)
                                    val isPinned = obj.optBoolean("isPinned", false)
                                    val isTaskList = obj.optBoolean("isTaskList", false)
                                    val webUrl = obj.optString("webUrl", null)
                                    val tags = obj.optString("tags", null)
                                    val autoTags = obj.optString("autoTags", null)

                                    viewModel.insertNote(
                                        title = title,
                                        content = content,
                                        category = category,
                                        colorIndex = colorIndex,
                                        isPinned = isPinned,
                                        isTaskList = isTaskList,
                                        webUrl = if (webUrl.isNullOrEmpty() || webUrl == "null") null else webUrl,
                                        tags = if (tags.isNullOrEmpty() || tags == "null") null else tags,
                                        autoTags = if (autoTags.isNullOrEmpty() || autoTags == "null") null else autoTags
                                    )
                                }

                                Toast.makeText(context, "Successfully restored $count notes!", Toast.LENGTH_SHORT).show()
                                showImportBackupDialog = false
                            } catch (e: Exception) {
                                importError = "Invalid PINotes Backup Format: ${e.localizedMessage}"
                            }
                        },
                        modifier = Modifier.testTag("confirm_import_backup_btn")
                    ) {
                        Text("Restore")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportBackupDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Google Login & Cloud Sync Dialog (Repurposed as Decentralized Firebase Sync Configuration)
        if (showGoogleLoginDialog) {
            var fbProjectId by remember { mutableStateOf("") }
            var fbAppId by remember { mutableStateOf("") }
            var fbApiKey by remember { mutableStateOf("") }
            var fbStorageBucket by remember { mutableStateOf("") }

            var authEmail by remember { mutableStateOf("") }
            var authPassword by remember { mutableStateOf("") }
            var isSignUpTab by remember { mutableStateOf(false) }
            var isLoading by remember { mutableStateOf(false) }
            var isFirebaseConfigExpanded by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val conf = FirebaseConfig.load(context)
                fbProjectId = conf.projectId
                fbAppId = conf.appId
                fbApiKey = conf.apiKey
                fbStorageBucket = conf.storageBucket
                authEmail = conf.userEmail
                authPassword = conf.userPassword
            }

            AlertDialog(
                onDismissRequest = { 
                    if (!isLoading) showGoogleLoginDialog = false 
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Firebase Private Sync",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Enter your own private Firebase project credentials details below to enable fully encrypted, decentralized bidirectional cross-device note sync.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isFirebaseConfigExpanded = !isFirebaseConfigExpanded }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "1. Firebase Config Setup",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = if (isFirebaseConfigExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (isFirebaseConfigExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                if (isFirebaseConfigExpanded) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = fbProjectId,
                                        onValueChange = { fbProjectId = it },
                                        label = { Text("Project ID") },
                                        placeholder = { Text("e.g. my-private-notes") },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_project_id_input"),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = fbAppId,
                                        onValueChange = { fbAppId = it },
                                        label = { Text("App ID (Mobile)") },
                                        placeholder = { Text("e.g. 1:123456:android:abcd") },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_app_id_input"),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = fbApiKey,
                                        onValueChange = { fbApiKey = it },
                                        label = { Text("Web API Key") },
                                        placeholder = { Text("AIzaSy...") },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_api_key_input"),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = fbStorageBucket,
                                        onValueChange = { fbStorageBucket = it },
                                        label = { Text("Storage Bucket Url") },
                                        placeholder = { Text("e.g. my-private-notes.appspot.com") },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_storage_bucket_input"),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(
                                        onClick = {
                                            if (fbProjectId.isBlank() || fbAppId.isBlank() || fbApiKey.isBlank() || fbStorageBucket.isBlank()) {
                                                Toast.makeText(context, "All configuration fields are required!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val currentConf = FirebaseConfig.load(context)
                                                val newConf = currentConf.copy(
                                                    projectId = fbProjectId.trim(),
                                                    appId = fbAppId.trim(),
                                                    apiKey = fbApiKey.trim(),
                                                    storageBucket = fbStorageBucket.trim(),
                                                    isEnabled = true
                                                )
                                                FirebaseConfig.save(context, newConf)
                                                val reinitApp = FirebaseConfig.initialize(context, newConf)
                                                if (reinitApp != null) {
                                                    Toast.makeText(context, "Configuration successfully saved and initialized!", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "Saved. Ensure credentials are correct configuration.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_save_config_btn"),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Save Firebase Configuration", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "2. Remote User Account",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )

                                if (firebaseLoggedIn) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = firebaseUserEmail ?: "Active Account",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(
                                                            color = when (syncStatus) {
                                                                SyncStatus.SYNCED -> Color(0xFF4CAF50)
                                                                SyncStatus.SYNCING -> Color(0xFFFFEB3B)
                                                                SyncStatus.ERROR -> Color(0xFFF44336)
                                                                SyncStatus.OFFLINE -> Color(0xFF9E9E9E)
                                                            },
                                                            shape = CircleShape
                                                        )
                                                )
                                                Text(
                                                    text = "Sync Status: $syncStatus",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                if (isLoading) return@Button
                                                isLoading = true
                                                scope.launch {
                                                    try {
                                                        SyncManager.triggerSyncNow(context, viewModel.repository)
                                                        // Wait a short duration to let sync finish
                                                        kotlinx.coroutines.delay(500)
                                                    } finally {
                                                        isLoading = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f).testTag("fb_force_sync_btn"),
                                            shape = RoundedCornerShape(8.dp),
                                            enabled = !isLoading
                                        ) {
                                            if (isLoading) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Sync Now")
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                SyncManager.logout(context)
                                                Toast.makeText(context, "Successfully disconnected!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.weight(1f).testTag("fb_logout_btn"),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Log Out")
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Please verify your Firebase configuration details above before making connection attempts.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )

                                    TabRow(
                                        selectedTabIndex = if (isSignUpTab) 1 else 0,
                                        modifier = Modifier.fillMaxWidth().height(40.dp)
                                    ) {
                                        Tab(
                                            selected = !isSignUpTab,
                                            onClick = { isSignUpTab = false },
                                            text = { Text("Log In", style = MaterialTheme.typography.labelMedium) }
                                        )
                                        Tab(
                                            selected = isSignUpTab,
                                            onClick = { isSignUpTab = true },
                                            text = { Text("Create Account", style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }

                                    OutlinedTextField(
                                        value = authEmail,
                                        onValueChange = { authEmail = it },
                                        label = { Text("Email Address") },
                                        placeholder = { Text("e.g. sam@gmail.com") },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_auth_email_input"),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = authPassword,
                                        onValueChange = { authPassword = it },
                                        label = { Text("Password") },
                                        placeholder = { Text("Enter password") },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_auth_password_input"),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true,
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                                    )

                                    Button(
                                        onClick = {
                                            if (authEmail.isBlank() || authPassword.isBlank()) {
                                                Toast.makeText(context, "Please enter email and password credentials!", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            val confState = FirebaseConfig.load(context)
                                            if (!confState.isValid()) {
                                                Toast.makeText(context, "Invalid Firebase Configuration! Save project credentials first.", Toast.LENGTH_LONG).show()
                                                return@Button
                                            }
                                            isLoading = true
                                            scope.launch {
                                                try {
                                                    val successState = SyncManager.loginOrRegisterUser(context, authEmail.trim(), authPassword.trim())
                                                    if (successState) {
                                                        // Immediately trigger first sync
                                                        SyncManager.initializeSync(context, viewModel.repository)
                                                        Toast.makeText(context, "Authenticated successfully: Online sync is active!", Toast.LENGTH_LONG).show()
                                                    } else {
                                                        Toast.makeText(context, "Authentication failed. Check your credentials or Firebase configuration settings.", Toast.LENGTH_LONG).show()
                                                    }
                                                } finally {
                                                    isLoading = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().testTag("fb_auth_action_btn"),
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = !isLoading
                                    ) {
                                        if (isLoading) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                        } else {
                                            Text(if (isSignUpTab) "Create Account & Connect" else "Secure Log In & Connect", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showGoogleLoginDialog = false },
                        enabled = !isLoading,
                        modifier = Modifier.testTag("fb_sync_dismiss_btn")
                    ) {
                        Text("Dismiss")
                    }
                }
            )
        }

        // Create Category Tag Dialog
        if (showAddCategoryDialog) {
            var newCategoryName by remember { mutableStateOf("") }
            var isNameError by remember { mutableStateOf(false) }
            
            AlertDialog(
                onDismissRequest = { showAddCategoryDialog = false },
                title = { Text("Create Custom Tag") },
                text = {
                    Column {
                        Text("Enter a name for the new custom tag:")
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = newCategoryName,
                            onValueChange = { 
                                newCategoryName = it 
                                if (isNameError && it.trim().isNotEmpty()) {
                                    isNameError = false
                                }
                            },
                            placeholder = { Text("e.g. Shopping, Fitness, Travel") },
                            singleLine = true,
                            isError = isNameError,
                            modifier = Modifier.fillMaxWidth().testTag("add_category_input_field"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (isNameError) {
                            Text(
                                text = "Tag name cannot be empty",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        modifier = Modifier.testTag("submit_add_category_button"),
                        onClick = {
                            if (newCategoryName.trim().isEmpty()) {
                                isNameError = true
                            } else {
                                viewModel.addCategory(newCategoryName.trim())
                                showAddCategoryDialog = false
                            }
                        }
                    ) {
                        Text("Add Tag")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showAddCategoryDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Rearrange / Rename / Hide / Delete Category Dialog
        if (categoryToRearrange != null) {
            val targetCategory = categoryToRearrange!!
            var curList = categoriesList.toList()
            val targetIndex = curList.indexOf(targetCategory)
            var renameText by remember(targetCategory) { mutableStateOf(targetCategory) }
            val isHidden = hiddenCategories.contains(targetCategory)
            
            AlertDialog(
                onDismissRequest = { categoryToRearrange = null },
                title = { Text("Manage Tag: \"$targetCategory\"") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // 1. Rename Category
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Category Name", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Button(
                                    onClick = {
                                        if (renameText.trim().isNotEmpty() && renameText.trim() != targetCategory) {
                                            viewModel.renameCategory(targetCategory, renameText.trim())
                                            categoryToRearrange = renameText.trim()
                                        }
                                    },
                                    enabled = renameText.trim().isNotEmpty() && renameText.trim() != targetCategory,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Rename")
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // 2. Hide / Unhide notes from "All Notes"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleCategoryHidden(targetCategory) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Hide from \"All Notes\"", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    if (isHidden) "Notes in this category are hidden when viewing All Notes"
                                    else "Hide notes under this category when viewing All Notes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isHidden,
                                onCheckedChange = { viewModel.toggleCategoryHidden(targetCategory) }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // 3. Position Reordering
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Reorder Tag Position", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    enabled = targetIndex > 0,
                                    onClick = {
                                        val newList = curList.toMutableList()
                                        val temp = newList[targetIndex]
                                        newList[targetIndex] = newList[targetIndex - 1]
                                        newList[targetIndex - 1] = temp
                                        viewModel.saveNewCategoryOrder(newList)
                                        curList = newList
                                    }
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Move Left")
                                }
                                
                                Text("Position: ${targetIndex + 1} / ${curList.size}", fontWeight = FontWeight.Bold)
                                
                                IconButton(
                                    enabled = targetIndex < curList.size - 1,
                                    onClick = {
                                        val newList = curList.toMutableList()
                                        val temp = newList[targetIndex]
                                        newList[targetIndex] = newList[targetIndex + 1]
                                        newList[targetIndex + 1] = temp
                                        viewModel.saveNewCategoryOrder(newList)
                                        curList = newList
                                    }
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "Move Right")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { categoryToRearrange = null }
                    ) {
                        Text("Done")
                    }
                },
                dismissButton = {
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            viewModel.deleteCategory(targetCategory)
                            categoryToRearrange = null
                        }
                    ) {
                        Text("Delete Tag")
                    }
                }
            )
        }

        // Settings Dialog (About Section with dropdown items)
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text("Settings", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Expandable About Secure Notes Setting Item
                        var isAboutExpanded by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isAboutExpanded) 0.3f else 0.15f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = if (isAboutExpanded) 0.25f else 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { isAboutExpanded = !isAboutExpanded }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "About",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (isAboutExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isAboutExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isAboutExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Secure Notes is a private offline-first personal notes manager designed to help you organize thoughts, secure URLs, construct rich interactive checklists, and automate web bookmark analysis.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        lineHeight = 20.sp
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = "Key Capabilities",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("💡", style = MaterialTheme.typography.bodyMedium)
                                            Text("Offline-First Room SQLite storage with zero server leaking.", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("✨", style = MaterialTheme.typography.bodyMedium)
                                            Text("Gemini AI Automatic Metadata website extraction & summary.", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("📌", style = MaterialTheme.typography.bodyMedium)
                                            Text("Dynamic action pin-push-link controls and direct interactive notifications.", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Expandable Groq Key Configuration Item (Formerly Gemini)
                        var isGroqExpanded by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isGroqExpanded) 0.3f else 0.15f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = if (isGroqExpanded) 0.25f else 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { isGroqExpanded = !isGroqExpanded }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VpnKey,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "AI API Key Config (OpenRouter / Groq)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (isGroqExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isGroqExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isGroqExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val settingsContext = LocalContext.current
                                    val settingsPrefs = remember { settingsContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
                                    var apiKeyInput by remember {
                                        val existing = settingsPrefs.getString("custom_groq_api_key", "") ?: ""
                                        val fallback = if (existing.isEmpty()) settingsPrefs.getString("custom_gemini_api_key", "") ?: "" else existing
                                        mutableStateOf(fallback)
                                    }

                                    OutlinedTextField(
                                        value = apiKeyInput,
                                        onValueChange = { newValue ->
                                            apiKeyInput = newValue
                                            settingsPrefs.edit()
                                                .putString("custom_groq_api_key", newValue.trim())
                                                .putString("custom_gemini_api_key", newValue.trim())
                                                .putString("custom_ai_api_key", newValue.trim())
                                                .apply()
                                        },
                                        label = { Text("Custom AI API Key (OpenRouter or Groq)", style = MaterialTheme.typography.bodySmall) },
                                        placeholder = { Text("Enter API Key (e.g. sk-or-... or gsk_...)") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.VpnKey,
                                                contentDescription = "API Key",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("settings_api_key_input"),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    Text(
                                        text = "Supports both OpenRouter keys (sk-or-...) and Groq keys (gsk_...). Compatible across all Android versions.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }

                        // 3. Expandable AI Code Configuration Item (Edit AI Analysis Code)
                        var isAiCodeExpanded by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isAiCodeExpanded) 0.3f else 0.15f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = if (isAiCodeExpanded) 0.25f else 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { isAiCodeExpanded = !isAiCodeExpanded }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Code,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Edit AI Analysis Prompt",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (isAiCodeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isAiCodeExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isAiCodeExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val settingsContext = LocalContext.current
                                    val settingsPrefs = remember { settingsContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
                                    val defaultPromptInstructions = com.example.data.WebpageScraper.DEFAULT_SYSTEM_INSTRUCTION
                                    var aiCodeInput by remember {
                                        val stored = settingsPrefs.getString("custom_ai_analysis_code", "") ?: ""
                                        mutableStateOf(if (stored.isBlank()) defaultPromptInstructions else stored)
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Custom System Instructions",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        TextButton(
                                            onClick = {
                                                aiCodeInput = defaultPromptInstructions
                                                settingsPrefs.edit().putString("custom_ai_analysis_code", defaultPromptInstructions).apply()
                                                Toast.makeText(settingsContext, "Reset to default AI instructions", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Reset to Default", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    OutlinedTextField(
                                        value = aiCodeInput,
                                        onValueChange = { newValue ->
                                            aiCodeInput = newValue
                                            settingsPrefs.edit().putString("custom_ai_analysis_code", newValue).apply()
                                        },
                                        label = { Text("Custom Prompt / Extraction Instructions", style = MaterialTheme.typography.bodySmall) },
                                        placeholder = { Text("Enter custom instructions...") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp)
                                            .testTag("settings_ai_code_input"),
                                        maxLines = 12,
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    Text(
                                        text = "Changes to this instruction are directly applied to the AI function whenever webpage notes or summaries are generated.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }

                        // 3.5. Expandable Archive Section in Settings
                        val archivedNotesList by viewModel.archivedNotesState.collectAsStateWithLifecycle()
                        var isArchiveExpanded by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isArchiveExpanded) 0.3f else 0.15f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = if (isArchiveExpanded) 0.25f else 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { isArchiveExpanded = !isArchiveExpanded }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Archive,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Archive (${archivedNotesList.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (isArchiveExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isArchiveExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isArchiveExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                if (archivedNotesList.isEmpty()) {
                                    Text(
                                        text = "No archived notes yet. Archived notes are hidden from the main view and kept here.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                    )
                                } else {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        archivedNotesList.forEach { arcNote ->
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = arcNote.title.ifEmpty { "Untitled Note" },
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (arcNote.content.isNotEmpty()) {
                                                            Text(
                                                                text = arcNote.content,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        AppTooltip(text = "Unarchive Note") {
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.unarchiveNote(arcNote)
                                                                    Toast.makeText(context, "Note unarchived", Toast.LENGTH_SHORT).show()
                                                                },
                                                                modifier = Modifier.size(32.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Unarchive,
                                                                    contentDescription = "Unarchive Note",
                                                                    tint = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                        AppTooltip(text = "Delete Permanently") {
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.deleteNote(arcNote)
                                                                    Toast.makeText(context, "Note deleted permanently", Toast.LENGTH_SHORT).show()
                                                                },
                                                                modifier = Modifier.size(32.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Delete,
                                                                    contentDescription = "Delete Permanently",
                                                                    tint = MaterialTheme.colorScheme.error,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Expandable App UI Scaling
                        var isScalingExpanded by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isScalingExpanded) 0.3f else 0.15f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = if (isScalingExpanded) 0.25f else 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { isScalingExpanded = !isScalingExpanded }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FormatSize,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "App UI Scaling",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (isScalingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isScalingExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isScalingExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val settingsContext = LocalContext.current
                                    val settingsPrefs = remember { settingsContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
                                    var currentScale by remember { mutableStateOf(settingsPrefs.getFloat("app_ui_scale", 1.0f)) }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "UI Scale Multiplier",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${(currentScale * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Slider(
                                        value = currentScale,
                                        onValueChange = { newValue ->
                                            val stepScale = (newValue * 20).toInt() / 20f
                                            currentScale = stepScale
                                            settingsPrefs.edit().putFloat("app_ui_scale", stepScale).apply()
                                            onScaleChange(stepScale)
                                        },
                                        valueRange = 0.8f..1.4f,
                                        steps = 11,
                                        modifier = Modifier.fillMaxWidth().testTag("scale_slider")
                                    )

                                    Text(
                                        text = "Scale text sizes, buttons, cards, and layouts. Helps fit elements to any screen size.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }

                        // 5. Expandable Local Backup & Restore
                        var isBackupExpanded by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isBackupExpanded) 0.3f else 0.15f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = if (isBackupExpanded) 0.25f else 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { isBackupExpanded = !isBackupExpanded }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Save,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Local Backup & Restore",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (isBackupExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isBackupExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isBackupExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Export notes as a single backup JSON file, or restore existing files on a new device.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(start = 4.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                try {
                                                    val notesToExport = localNotesList
                                                    val jsonArray = JSONArray()
                                                    notesToExport.forEach { n ->
                                                        val obj = JSONObject().apply {
                                                            put("title", n.title)
                                                            put("content", n.content)
                                                            put("category", n.category)
                                                            put("colorIndex", n.colorIndex)
                                                            put("isPinned", n.isPinned)
                                                            put("isTaskList", n.isTaskList)
                                                            put("webUrl", n.webUrl)
                                                            put("tags", n.tags)
                                                            put("autoTags", n.autoTags)
                                                        }
                                                        jsonArray.put(obj)
                                                    }
                                                    val jsonString = jsonArray.toString(2)
                                                    
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText("PINotes Backup", jsonString)
                                                    clipboard.setPrimaryClip(clip)
                                                    
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_TEXT, jsonString)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Save PINotes Backup File"))
                                                    
                                                    Toast.makeText(context, "Backup copied to clipboard & shared!", Toast.LENGTH_LONG).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Export JSON", style = MaterialTheme.typography.bodySmall)
                                        }

                                        Button(
                                            onClick = {
                                                showSettingsDialog = false
                                                showImportBackupDialog = true
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                        ) {
                                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Import JSON", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("1.2.0 (PINotes)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // App Guide & Green "Buy me a Coffee" buttons side-by-side of standardized equal size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val localContextForGuide = LocalContext.current
                            FilledTonalButton(
                                onClick = {
                                    try {
                                        val guideIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/samps07/PINote")).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        localContextForGuide.startActivity(guideIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(localContextForGuide, "Could not open link", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("app_guide_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Book,
                                        contentDescription = "App Guide Icon",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "App Guide",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                }
                            }

                            Button(
                                onClick = { showQrDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2E7D32),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("donate_fab_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Coffee,
                                        contentDescription = "Buy me a Coffee Icon",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Buy me a Coffee",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // "Made with ❤️ by Sam" centered
                        Text(
                            text = "Made with ❤️ by Sam",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("made_by_sam_footer")
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        modifier = Modifier.testTag("settings_done_button"),
                        onClick = { showSettingsDialog = false }
                    ) {
                        Text("Done")
                    }
                }
            )
        }

        // QR Code Dialog
        if (showQrDialog) {
            AlertDialog(
                onDismissRequest = { showQrDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(24.dp)
                        )
                        Text("Donate & Support Us", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Thank you for supporting Secure Notes! Scanning this QR code will open our donate support channel.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White)
                                .padding(12.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            QrCodeRenderer(modifier = Modifier.fillMaxSize())
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showQrDialog = false },
                        modifier = Modifier.testTag("donate_qr_dismiss_btn")
                    ) {
                        Text("Close")
                    }
                }
            )
        }

        if (fullScreenImageUri != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { fullScreenImageUri = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .clickable { fullScreenImageUri = null },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(fullScreenImageUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Full Screen Attached Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .padding(16.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    }

                    IconButton(
                        onClick = { fullScreenImageUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(24.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(48.dp)
                            .testTag("close_full_screen_image")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close full screen image",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NoteCard(
    note: Note,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onPushNotification: () -> Unit,
    onUpdate: (Note) -> Unit = {},
    onArchive: (() -> Unit)? = null,
    isCompact: Boolean = false,
    isDensityCompact: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val noteBg = getNoteColor(note.colorIndex, darkTheme)
    val noteText = getNoteOnColor(note.colorIndex, darkTheme)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("note_card_${note.id}")
            .combinedClickable(
                onClick = onEdit,
                onLongClick = { onLongClick?.invoke() }
            ),
        shape = RoundedCornerShape(if (isDensityCompact) 10.dp else 16.dp),
        colors = CardDefaults.cardColors(containerColor = noteBg),
        border = BorderStroke(
            width = if (isSelected) 3.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        if (isCompact) {
            // comfortable view (one note horizontally, i.e. 1-column list view)
            Column(
                modifier = Modifier.padding(if (isDensityCompact) 8.dp else 14.dp)
            ) {
                // 1. Attachment preview if any
                val imgList = note.imageUris
                if (imgList.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isDensityCompact) 75.dp else 110.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        val currentImageUri = imgList[0]
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(currentImageUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Note Image Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onImageClick?.invoke(currentImageUri) },
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        if (imgList.size > 1) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                contentColor = Color.White
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoLibrary,
                                        contentDescription = "Multiple images",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.White
                                    )
                                    Text(
                                        text = "+${imgList.size - 1}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 1b. Non-image file attachment if any
                if (imgList.isEmpty() && !note.attachmentUri.isNullOrEmpty()) {
                    val attachmentName = try {
                        val uri = android.net.Uri.parse(note.attachmentUri)
                        val decodedPath = android.net.Uri.decode(uri.toString())
                        val lastSegment = decodedPath.substringAfterLast('/')
                        val cleanName = lastSegment.substringBefore('?')
                        if (cleanName.isNotEmpty() && cleanName != "null") cleanName else "Attachment File"
                    } catch (e: Exception) {
                        "Attachment File"
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(noteText.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .border(1.dp, noteText.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attachment File",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = attachmentName,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = noteText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 2. Note title
                Text(
                    text = note.title.ifEmpty { "Untitled" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp
                    ),
                    color = noteText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 3. Note content (checklist or markdown)
                if (note.isTaskList) {
                    com.example.ui.CardTablePreview(
                        note = note,
                        onUpdate = onUpdate,
                        noteText = noteText,
                        maxDisplayRows = 3
                    )
                } else {
                    Text(
                        text = parseMarkdownToAnnotatedString(note.content),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 18.sp
                        ),
                        color = noteText.copy(alpha = 0.75f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 4. Category tag (below note content, above squircle)
                if (note.category.isNotEmpty() || note.tagList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (note.category.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        noteText.copy(alpha = 0.08f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = note.category,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = noteText.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        note.tagList.forEach { tag ->
                            val cleanTag = tag.trim().removePrefix("#")
                            if (cleanTag.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .border(
                                            width = 1.dp,
                                            color = noteText.copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .background(
                                            noteText.copy(alpha = 0.03f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                ) {
                                    Text(
                                        text = "#$cleanTag",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = noteText.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 5. Bottom block: translucent squircle and schedule tag placed next to each other
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Translucent action squircle with link, pin, push, delete buttons
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = noteText.copy(alpha = 0.06f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!note.webUrl.isNullOrEmpty()) {
                                val context = LocalContext.current
                                AppTooltip(text = "Open Website Link") {
                                    IconButton(
                                        onClick = {
                                            try {
                                                val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(note.webUrl)).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(openIntent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(32.dp).testTag("open_link_button_comfortable_${note.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInNew,
                                            contentDescription = "Open Link Directly",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            AppTooltip(text = if (note.isPinned) "Unpin Note" else "Pin Note to Top") {
                                IconButton(
                                    onClick = onTogglePin,
                                    modifier = Modifier.size(32.dp).testTag("pin_button_comfortable_${note.id}")
                                ) {
                                    Icon(
                                        imageVector = if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = if (note.isPinned) "Unpin Note" else "Pin Note",
                                        tint = if (note.isPinned) MaterialTheme.colorScheme.primary else noteText.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            AppTooltip(text = "Send Direct Push Notification") {
                                IconButton(
                                    onClick = onPushNotification,
                                    modifier = Modifier.size(32.dp).testTag("push_button_comfortable_${note.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsActive,
                                        contentDescription = "Push Notification",
                                        tint = noteText.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (onArchive != null) {
                                AppTooltip(text = "Archive Note") {
                                    IconButton(
                                        onClick = onArchive,
                                        modifier = Modifier.size(32.dp).testTag("archive_button_${note.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Archive,
                                            contentDescription = "Archive Note",
                                            tint = noteText.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }


                            AppTooltip(text = "Delete Note") {
                                IconButton(
                                    onClick = onDelete,
                                    modifier = Modifier.size(32.dp).testTag("delete_button_comfortable_${note.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Note",
                                        tint = noteText.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Schedule tag placed beside the translucent squircle
                    if (note.isScheduled && note.scheduledTime != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Scheduled",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(note.scheduledTime)),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        } else {
            // compact view (two notes vertically placed, 2-column grid layout)
            Column(
                modifier = Modifier.padding(if (isDensityCompact) 8.dp else 14.dp)
            ) {
                // 1. Attachment preview if any
                val imgList = note.imageUris
                if (imgList.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isDensityCompact) 75.dp else 115.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        val currentImageUri = imgList[0]
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(currentImageUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Note Image Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onImageClick?.invoke(currentImageUri) },
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        if (imgList.size > 1) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                contentColor = Color.White
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoLibrary,
                                        contentDescription = "Multiple images",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.White
                                    )
                                    Text(
                                        text = "+${imgList.size - 1}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 1b. Non-image file attachment if any
                if (imgList.isEmpty() && !note.attachmentUri.isNullOrEmpty()) {
                    val attachmentName = try {
                        val uri = android.net.Uri.parse(note.attachmentUri)
                        val decodedPath = android.net.Uri.decode(uri.toString())
                        val lastSegment = decodedPath.substringAfterLast('/')
                        val cleanName = lastSegment.substringBefore('?')
                        if (cleanName.isNotEmpty() && cleanName != "null") cleanName else "Attachment File"
                    } catch (e: Exception) {
                        "Attachment File"
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(noteText.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .border(1.dp, noteText.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attachment File",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = attachmentName,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = noteText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 2. Note Content (Title, Category, Body)
                Text(
                    text = note.title.ifEmpty { "Untitled" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp
                    ),
                    color = noteText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (note.isTaskList) {
                    com.example.ui.CardTablePreview(
                        note = note,
                        onUpdate = onUpdate,
                        noteText = noteText,
                        maxDisplayRows = 2
                    )
                } else {
                    Text(
                        text = parseMarkdownToAnnotatedString(note.content),
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 16.sp
                        ),
                        color = noteText.copy(alpha = 0.75f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (note.category.isNotEmpty() || note.tagList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (note.category.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        noteText.copy(alpha = 0.08f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = note.category,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = noteText.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        note.tagList.forEach { tag ->
                            val cleanTag = tag.trim().removePrefix("#")
                            if (cleanTag.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .border(
                                            width = 1.dp,
                                            color = noteText.copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .background(
                                            noteText.copy(alpha = 0.03f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                ) {
                                    Text(
                                        text = "#$cleanTag",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = noteText.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. link+pin+push+delete toggles all in one horizontal translucent squircle with minimal spacing
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = noteText.copy(alpha = 0.06f),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!note.webUrl.isNullOrEmpty()) {
                            val context = LocalContext.current
                            IconButton(
                                onClick = {
                                    try {
                                        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(note.webUrl)).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(openIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(32.dp).testTag("open_link_button_grid_${note.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Open Link Directly",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onTogglePin,
                            modifier = Modifier.size(32.dp).testTag("pin_button_grid_${note.id}")
                        ) {
                            Icon(
                                imageVector = if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (note.isPinned) "Unpin Note" else "Pin Note",
                                tint = if (note.isPinned) MaterialTheme.colorScheme.primary else noteText.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onPushNotification,
                            modifier = Modifier.size(32.dp).testTag("push_button_grid_${note.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Push Notification",
                                tint = noteText.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(32.dp).testTag("delete_button_grid_${note.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete Note",
                                tint = noteText.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // 4. schedule if any
                if (note.isScheduled && note.scheduledTime != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Scheduled",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(note.scheduledTime)),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (isSelected) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .size(24.dp),
                tonalElevation = 4.dp
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp).size(16.dp)
                )
            }
        }
    }
}
}

@Composable
fun NoteEditorDialog(
    note: Note?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        content: String,
        category: String,
        colorIndex: Int,
        isPinned: Boolean,
        imageUri: String?,
        isTaskList: Boolean,
        webUrl: String?,
        isScheduled: Boolean,
        scheduledTime: Long?,
        repeatFrequency: String?,
        tags: String?,
        autoTags: String?,
        attachmentUri: String?,
        imageUrl: String?,
        imageStoragePath: String?,
        isPersistentNotification: Boolean,
        isAiEnabled: Boolean
    ) -> Unit,
    onImageClick: ((String) -> Unit)? = null,
    onAutoSave: ((
        title: String,
        content: String,
        category: String,
        colorIndex: Int,
        isPinned: Boolean,
        imageUri: String?,
        isTaskList: Boolean,
        webUrl: String?,
        isScheduled: Boolean,
        scheduledTime: Long?,
        repeatFrequency: String?,
        tags: String?,
        autoTags: String?,
        attachmentUri: String?,
        imageUrl: String?,
        imageStoragePath: String?,
        isPersistentNotification: Boolean,
        isAiEnabled: Boolean
    ) -> Unit)? = null
) {
    val context = LocalContext.current
    val noteId = remember { note?.noteId ?: java.util.UUID.randomUUID().toString() }
    var titleValue by remember { mutableStateOf(TextFieldValue(note?.title ?: "", TextRange((note?.title ?: "").length))) }
    var title by DynamicTextDelegate({ titleValue }, { titleValue = it })

    var contentValue by remember { mutableStateOf(TextFieldValue(note?.content ?: "", TextRange((note?.content ?: "").length))) }
    var content by DynamicTextDelegate({ contentValue }, { contentValue = it })

    var isBoldActive by remember { mutableStateOf(false) }
    var isItalicActive by remember { mutableStateOf(false) }
    var isUnderlineActive by remember { mutableStateOf(false) }
    var isTitleFocused by remember { mutableStateOf(false) }
    var isContentFocused by remember { mutableStateOf(false) }

    fun toggleFormat(formatSymbol: String, isActive: Boolean, value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit) {
        val text = value.text
        val selection = value.selection
        if (selection.length > 0) {
            val start = selection.start
            val end = selection.end
            val selectedText = text.substring(start, end)
            val wrapped = "$formatSymbol$selectedText$formatSymbol"
            val newText = text.substring(0, start) + wrapped + text.substring(end)
            onValueChange(TextFieldValue(newText, TextRange(start + formatSymbol.length + selectedText.length + formatSymbol.length)))
        } else {
            val cursor = selection.start
            if (isActive) {
                val inserted = "$formatSymbol$formatSymbol"
                val newText = text.substring(0, cursor) + inserted + text.substring(cursor)
                onValueChange(TextFieldValue(newText, TextRange(cursor + formatSymbol.length)))
            } else {
                if (cursor + formatSymbol.length <= text.length && text.substring(cursor, cursor + formatSymbol.length) == formatSymbol) {
                    onValueChange(TextFieldValue(text, TextRange(cursor + formatSymbol.length)))
                }
            }
        }
    }

    fun onFormatClick(formatSymbol: String, isActive: Boolean) {
        if (isTitleFocused) {
            toggleFormat(formatSymbol, isActive, titleValue) { titleValue = it }
        } else {
            toggleFormat(formatSymbol, isActive, contentValue) { contentValue = it }
        }
    }

    var selectedCategory by remember { mutableStateOf(note?.category ?: "General") }
    var selectedTags by remember {
        mutableStateOf(
            if (note?.tags.isNullOrEmpty()) emptySet<String>()
            else note!!.tagList.toSet()
        )
    }
    var selectedAutoTags by remember {
        mutableStateOf(
            if (note?.autoTags.isNullOrEmpty()) emptySet<String>()
            else note!!.autoTagList.toSet()
        )
    }
    var attachmentUri by remember { mutableStateOf(note?.attachmentUri) }
    var availableTags by remember {
        mutableStateOf(com.example.data.CategoryPrefs.getTags(context))
    }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var selectedColorIndex by remember { mutableStateOf(note?.colorIndex ?: 0) }
    var isPinned by remember { mutableStateOf(note?.isPinned ?: false) }
    var isEditMode by remember { mutableStateOf(note == null) }
    var imageUrisList by remember {
        mutableStateOf(note?.imageUris ?: emptyList())
    }
    var imageUrlState by remember { mutableStateOf(note?.imageUrl) }
    var imageStoragePathState by remember { mutableStateOf(note?.imageStoragePath) }
    var isTaskList by remember { mutableStateOf(note?.isTaskList ?: false) }

    // Website Link and AI extraction states
    var websiteUrl by remember { mutableStateOf(note?.webUrl ?: "") }
    var isAiEnabled by remember { mutableStateOf(false) }
    var isExtracting by remember { mutableStateOf(false) }
    var extractionError by remember { mutableStateOf<String?>(null) }
    var isManualEditAllowed by remember { mutableStateOf(false) }

    // Scheduling States
    var isScheduled by remember { mutableStateOf(note?.isScheduled ?: false) }
    var scheduledTime by remember { mutableStateOf<Long?>(note?.scheduledTime) }
    var repeatFrequency by remember { mutableStateOf(note?.repeatFrequency ?: "once") }
    var showSchedulingWindow by remember { mutableStateOf(note?.isScheduled ?: false) }
    var isImmediate by remember { mutableStateOf(note?.isScheduled == true && note?.scheduledTime != null && (note.scheduledTime!! - System.currentTimeMillis() < 60000L)) }
    var isPersistentNotification by remember { mutableStateOf(note?.isPersistentNotification ?: false) }

    var isTitleError by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val dialogBg = getNoteColor(selectedColorIndex, isSystemInDarkTheme())
    val onDialogText = getNoteOnColor(selectedColorIndex, isSystemInDarkTheme())
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(24.dp),
            color = dialogBg,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                // Fixed Dialog Header with Actions (Unified Squircle containing AI, Checklist, Schedule, Pin)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (note == null) "New Note" else if (isEditMode) "Edit Note" else "",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = onDialogText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (note != null && !isEditMode) {
                            IconButton(
                                onClick = { isEditMode = true },
                                modifier = Modifier.size(32.dp).testTag("note_edit_pencil_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Note",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Space-efficient Horizontal Squircle
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // Copy button in view mode (beside AI button, icon only)
                            if (!isEditMode) {
                                IconButton(
                                    onClick = {
                                        val textToCopy = com.example.data.formatNoteTextForCopying(title, content, isTaskList, websiteUrl)
                                        if (textToCopy.isNotEmpty()) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Note", textToCopy)
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(context, "Note copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(31.dp).testTag("note_view_copy_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Note",
                                        tint = onDialogText.copy(alpha = 0.75f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // 1. AI Extraction Toggle
                            IconToggleButton(
                                checked = isAiEnabled,
                                onCheckedChange = { 
                                    isAiEnabled = it
                                    if (it && websiteUrl.isEmpty()) {
                                        val parsed = com.example.data.WebpageScraper.extractUrlFromText(content)
                                        if (!parsed.isNullOrEmpty()) {
                                            websiteUrl = parsed
                                        }
                                    }
                                },
                                modifier = Modifier.size(31.dp).testTag("editor_ai_toggle")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "AI Extraction Toggle",
                                    tint = if (isAiEnabled) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // 2. Checklist toggle
                            IconToggleButton(
                                checked = isTaskList,
                                onCheckedChange = { checked ->
                                    isTaskList = checked
                                    if (isTaskList) {
                                        if (content.isNotBlank() && !content.contains("[ ] ") && !content.contains("[x] ")) {
                                            content = content.split("\n")
                                                .filter { it.isNotBlank() }
                                                .joinToString("\n") { "[ ] $it" }
                                        } else if (content.isBlank()) {
                                            content = "[ ] "
                                        }
                                    } else {
                                        content = com.example.data.parseTaskItems(content).joinToString("\n") { it.text }
                                    }
                                },
                                modifier = Modifier.size(31.dp).testTag("editor_task_list_toggle")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.List,
                                    contentDescription = "Toggle Checklist Mode",
                                    tint = if (isTaskList) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // 3. Schedule Notification Toggle
                            IconToggleButton(
                                checked = showSchedulingWindow,
                                onCheckedChange = {
                                    showSchedulingWindow = it
                                    if (!it) {
                                        isScheduled = false
                                        scheduledTime = null
                                    } else {
                                        isScheduled = true
                                    }
                                },
                                modifier = Modifier.size(31.dp).testTag("editor_schedule_toggle")
                            ) {
                                Icon(
                                    imageVector = if (showSchedulingWindow) Icons.Default.Schedule else Icons.Outlined.Schedule,
                                    contentDescription = "Schedule Notification Push",
                                    tint = if (showSchedulingWindow) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // 4. Pin toggle in Dialog
                            IconToggleButton(
                                checked = isPinned,
                                onCheckedChange = { isPinned = it },
                                modifier = Modifier.size(31.dp).testTag("editor_pin_toggle")
                            ) {
                                Icon(
                                    imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = "Pin Note",
                                    tint = if (isPinned) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Content Column
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                ) {
                    val context = LocalContext.current

                    // AI Extraction Input Area
                    if (isAiEnabled) {
                        OutlinedTextField(
                            value = websiteUrl,
                            onValueChange = { websiteUrl = it },
                            placeholder = { Text("paste text/url to curate", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = "Website URL",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (isExtracting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            isExtracting = true
                                            extractionError = null
                                            scope.launch {
                                                try {
                                                    val metadata = com.example.data.WebpageScraper.extractMetadata(websiteUrl, context)
                                                    if (metadata.title.isNotEmpty()) {
                                                        title = metadata.title
                                                        content = metadata.summary
                                                        if (metadata.imageUrl.isNotEmpty()) {
                                                            imageUrisList = listOf(metadata.imageUrl)
                                                        }
                                                        if (!metadata.category.isNullOrEmpty()) {
                                                            selectedCategory = metadata.category
                                                        }
                                                        if (!metadata.tags.isNullOrEmpty()) {
                                                            selectedAutoTags = metadata.tags.split(Regex("[|,]+"))
                                                                .map { it.trim().removePrefix("#").trim() }
                                                                 .filter { it.isNotEmpty() }
                                                                .toSet()
                                                        }
                                                        isManualEditAllowed = true
                                                        isAiEnabled = false
                                                        Toast.makeText(context, "AI Extraction Successful!", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        extractionError = "No metadata returned. Make sure URL is valid."
                                                    }
                                                } catch (e: Exception) {
                                                    extractionError = e.message
                                                } finally {
                                                    isExtracting = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("editor_run_ai_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "Extract via AI",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_website_url_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        if (isExtracting) {
                            Text(
                                text = "Preparing beautiful AI summary...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                            )
                        }

                        if (extractionError != null) {
                            Text(
                                text = "Extraction Error: $extractionError",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Scheduling Window Section
                    if (showSchedulingWindow) {
                        val showDateTimePicker = {
                            val calendar = java.util.Calendar.getInstance()
                            scheduledTime?.let { calendar.timeInMillis = it }
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    calendar.set(java.util.Calendar.YEAR, year)
                                    calendar.set(java.util.Calendar.MONTH, month)
                                    calendar.set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                                    android.app.TimePickerDialog(
                                        context,
                                        { _, hourOfDay, minute ->
                                            calendar.set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                                            calendar.set(java.util.Calendar.MINUTE, minute)
                                            calendar.set(java.util.Calendar.SECOND, 0)
                                            scheduledTime = calendar.timeInMillis
                                            isScheduled = true
                                        },
                                        calendar.get(java.util.Calendar.HOUR_OF_DAY),
                                        calendar.get(java.util.Calendar.MINUTE),
                                        true
                                    ).show()
                                },
                                calendar.get(java.util.Calendar.YEAR),
                                calendar.get(java.util.Calendar.MONTH),
                                calendar.get(java.util.Calendar.DAY_OF_MONTH)
                            ).show()
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Schedule",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // Immediate Toggle Button (Styled exactly the same as Pick Date & Time button)
                                OutlinedButton(
                                    onClick = {
                                        isImmediate = !isImmediate
                                        if (isImmediate) {
                                            scheduledTime = System.currentTimeMillis() + 1000L
                                            isScheduled = true
                                            Toast.makeText(context, "Configured to push immediately on save!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            scheduledTime = null
                                            isScheduled = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("schedule_immediate_btn"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (isImmediate) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                        contentColor = if (isImmediate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isImmediate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isImmediate) Icons.Default.Check else Icons.Default.FlashOn,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isImmediate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Immediate Push",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isImmediate) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            isImmediate = false
                                            showDateTimePicker()
                                        },
                                        modifier = Modifier.weight(1.3f).testTag("schedule_date_time_btn"),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (!isImmediate && scheduledTime != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                            contentColor = if (!isImmediate && scheduledTime != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        ),
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (!isImmediate && scheduledTime != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (!isImmediate && scheduledTime != null) {
                                                java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(scheduledTime!!))
                                            } else {
                                                "Pick Date & Time"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    var showRepeatDropdown by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedButton(
                                            onClick = { showRepeatDropdown = true },
                                            modifier = Modifier.fillMaxWidth().testTag("schedule_repeat_btn"),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = repeatFrequency.replaceFirstChar { it.uppercase() },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = showRepeatDropdown,
                                            onDismissRequest = { showRepeatDropdown = false }
                                        ) {
                                            listOf("once", "daily", "weekly", "monthly").forEach { freq ->
                                                DropdownMenuItem(
                                                    text = { Text(freq.replaceFirstChar { it.uppercase() }) },
                                                    onClick = {
                                                        repeatFrequency = freq
                                                        showRepeatDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Notification Type",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    
                                    Row(
                                        modifier = Modifier
                                            .background(
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .padding(4.dp)
                                    ) {
                                        val isNormal = !isPersistentNotification
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isNormal) MaterialTheme.colorScheme.primary else Color.Transparent
                                                )
                                                .clickable { isPersistentNotification = false }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                                .testTag("notification_type_normal"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Notifications,
                                                    contentDescription = "Normal",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = if (isNormal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "Normal",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isNormal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = if (isNormal) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        val isPersistent = isPersistentNotification
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isPersistent) MaterialTheme.colorScheme.primary else Color.Transparent
                                                )
                                                .clickable { isPersistentNotification = true }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                                .testTag("notification_type_persistent"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PushPin,
                                                    contentDescription = "Persistent",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = if (isPersistent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "Persistent",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isPersistent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = if (isPersistent) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Title Input
                    if (isEditMode) {
                        OutlinedTextField(
                            value = titleValue,
                            onValueChange = {
                                titleValue = it
                                if (isTitleError && it.text.trim().isNotEmpty()) {
                                    isTitleError = false
                                }
                            },
                            label = { Text("Title") },
                            placeholder = { Text("Enter note title...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isTitleFocused = it.isFocused }
                                .testTag("editor_title_input"),
                            singleLine = true,
                            isError = isTitleError,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (isTitleError) {
                            Text(
                                text = "Title cannot be empty",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                            )
                        }
                    } else {
                        Text(
                            text = title.ifEmpty { "Untitled" },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = onDialogText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Note Body Area (Dynamic depending on isTaskList checklist mode)
                    if (isTaskList) {
                        com.example.ui.TableListNoteComponent(
                            content = content,
                            onContentChange = { newContent -> content = newContent },
                            isEditMode = isEditMode,
                            onSaveRequested = {
                                val autoFunc = onAutoSave ?: onSave
                                autoFunc(
                                    title.trim(),
                                    if (isTaskList) content else content.trim(),
                                    selectedCategory,
                                    selectedColorIndex,
                                    isPinned,
                                    if (imageUrisList.isNotEmpty()) imageUrisList.joinToString("|") else null,
                                    isTaskList,
                                    if (websiteUrl.trim().isNotEmpty()) websiteUrl.trim() else null,
                                    isScheduled,
                                    scheduledTime,
                                    repeatFrequency,
                                    selectedTags.joinToString("|"),
                                    if (selectedAutoTags.isNotEmpty()) selectedAutoTags.joinToString("|") else null,
                                    attachmentUri,
                                    imageUrlState,
                                    imageStoragePathState,
                                    isPersistentNotification,
                                    isAiEnabled
                                )
                            },
                            onDialogTextColor = onDialogText
                        )
                    } else {
                        if (isEditMode) {
                            // Standard rich multiline input
                            OutlinedTextField(
                                value = contentValue,
                                onValueChange = { contentValue = it },
                                label = { Text("Note content") },
                                placeholder = { Text("Write something down...") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 220.dp)
                                    .onFocusChanged { isContentFocused = it.isFocused }
                                    .testTag("editor_content_input"),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            // Beautiful rich body text display in View Mode
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp)
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = content.ifEmpty { "Empty note content" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = onDialogText.copy(alpha = 0.85f),
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }

                    // Image and Document attachments section
                    Spacer(modifier = Modifier.height(12.dp))

                    val pickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.PickVisualMedia()
                    ) { uri ->
                        if (uri != null) {
                            val localUri = MainActivity.copyUriToLocalFileWithRealName(context, uri)
                            if (localUri != null) {
                                val localUriStr = localUri.toString()
                                imageUrisList = imageUrisList + localUriStr
                                scope.launch {
                                    val uploaded = com.example.sync.SyncManager.uploadAttachedImageImmediately(
                                        context = context,
                                        noteId = noteId,
                                        localUriStr = localUriStr
                                    )
                                    if (uploaded != null) {
                                        val (downloadUrl, storagePath) = uploaded
                                        imageUrlState = if (imageUrlState.isNullOrEmpty()) downloadUrl else "$imageUrlState|$downloadUrl"
                                        imageStoragePathState = if (imageStoragePathState.isNullOrEmpty()) storagePath else "$imageStoragePathState|$storagePath"
                                    }
                                }
                            }
                        }
                    }

                    val documentPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            val localUri = MainActivity.copyUriToLocalFileWithRealName(context, uri)
                            if (localUri != null) {
                                attachmentUri = localUri.toString()
                            }
                        }
                    }

                    if (imageUrisList.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(imageUrisList) { index, imgUri ->
                                Box(
                                    modifier = Modifier
                                        .width(170.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(imgUri)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Attached Image",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { onImageClick?.invoke(imgUri) },
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )

                                    if (isEditMode) {
                                        // Clear single image button
                                        IconButton(
                                            onClick = {
                                                val removedUri = imageUrisList[index]
                                                imageUrisList = imageUrisList.filterIndexed { i, _ -> i != index }
                                                scope.launch {
                                                    try {
                                                        val app = com.example.sync.FirebaseConfig.getApp()
                                                        if (app != null) {
                                                            val storage = com.google.firebase.storage.FirebaseStorage.getInstance(app)
                                                            val imageUrls = (imageUrlState ?: "").split("|").filter { it.isNotBlank() }.toMutableList()
                                                            val imagePaths = (imageStoragePathState ?: "").split("|").filter { it.isNotBlank() }.toMutableList()
                                                            
                                                            var matchIndex = imageUrls.indexOf(removedUri)
                                                            if (matchIndex == -1 && index < imageUrls.size) {
                                                                matchIndex = index
                                                            }
                                                            
                                                            if (matchIndex != -1 && matchIndex < imageUrls.size) {
                                                                val pathToDelete = imagePaths.getOrNull(matchIndex)
                                                                imageUrls.removeAt(matchIndex)
                                                                if (pathToDelete != null) {
                                                                    if (matchIndex < imagePaths.size) {
                                                                        imagePaths.removeAt(matchIndex)
                                                                    }
                                                                    Log.d("SyncManager", "Storage deleting: Removing removed image storage path $pathToDelete")
                                                                    storage.reference.child(pathToDelete).delete().await()
                                                                }
                                                                imageUrlState = if (imageUrls.isEmpty()) null else imageUrls.joinToString("|")
                                                                imageStoragePathState = if (imagePaths.isEmpty()) null else imagePaths.joinToString("|")
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("SyncManager", "Storage deletion warning: Failed to delete removed image: ${e.message}")
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                                .size(26.dp)
                                                .testTag("remove_image_button_$index")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove Image",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!attachmentUri.isNullOrEmpty()) {
                        val attachmentName = try {
                            val uri = Uri.parse(attachmentUri)
                            val decodedPath = Uri.decode(uri.toString())
                            val lastSegment = decodedPath.substringAfterLast('/')
                            val cleanName = lastSegment.substringBefore('?')
                            if (cleanName.isNotEmpty() && cleanName != "null") cleanName else "Attachment File"
                        } catch (e: Exception) {
                            "Attachment File"
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Active Attachment",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = attachmentName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (attachmentUri!!.startsWith("http")) "Cloud Sync (Tap to Download)" else "Local File",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)),
                                )
                            }
                            
                            IconButton(
                                onClick = {
                                    try {
                                        val uri = Uri.parse(attachmentUri)
                                        if (uri.scheme == "http" || uri.scheme == "https") {
                                            scope.launch {
                                                val downloadedFile = com.example.sync.AttachmentCacheManager.downloadAttachmentOnDemand(
                                                    context,
                                                    note?.noteId ?: "",
                                                    attachmentName,
                                                    attachmentUri!!
                                                )
                                                if (downloadedFile != null && downloadedFile.exists()) {
                                                    val pathUri = androidx.core.content.FileProvider.getUriForFile(
                                                        context,
                                                        "com.example.fileprovider",
                                                        downloadedFile
                                                    )
                                                    val mime = if (downloadedFile.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "*/*"
                                                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(pathUri, mime)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(openIntent)
                                                } else {
                                                    scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                                        android.widget.Toast.makeText(context, "Error downloading attachment.", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        } else {
                                            val file = java.io.File(uri.path ?: "")
                                            if (file.exists()) {
                                                val pathUri = androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "com.example.fileprovider",
                                                    file
                                                )
                                                val mime = if (file.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "*/*"
                                                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(pathUri, mime)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(openIntent)
                                            } else {
                                                android.widget.Toast.makeText(context, "Local file not found.", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        android.widget.Toast.makeText(context, "Cannot open attachment.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Launch,
                                    contentDescription = "Open Attachment",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            if (isEditMode) {
                                IconButton(
                                    onClick = { attachmentUri = null }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove Attachment",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    if (isEditMode) {
                        OutlinedButton(
                            onClick = {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_image_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Image Attachment")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                documentPickerLauncher.launch("*/*")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_document_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add File Attachment (PDF, Doc, etc.)")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Persistently visible Text Formatting Toolbar
                    if (isEditMode && !isTaskList) {
                        Surface(
                            modifier = Modifier
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Bold Toggle Button
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isBoldActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                        .clickable {
                                            isBoldActive = !isBoldActive
                                            onFormatClick("**", isBoldActive)
                                        }
                                        .testTag("format_bold_btn"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FormatBold,
                                        contentDescription = "Format Bold",
                                        tint = if (isBoldActive) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Italic Toggle Button
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isItalicActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                        .clickable {
                                            isItalicActive = !isItalicActive
                                            onFormatClick("*", isItalicActive)
                                        }
                                        .testTag("format_italic_btn"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FormatItalic,
                                        contentDescription = "Format Italic",
                                        tint = if (isItalicActive) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Underline Toggle Button
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isUnderlineActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                        .clickable {
                                            isUnderlineActive = !isUnderlineActive
                                            onFormatClick("__", isUnderlineActive)
                                        }
                                        .testTag("format_underline_btn"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FormatUnderlined,
                                        contentDescription = "Format Underline",
                                        tint = if (isUnderlineActive) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Category selection header & row
                    Text(
                        text = "Category",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = onDialogText.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (isEditMode) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(categories) { category ->
                                FilterChip(
                                    selected = selectedCategory == category,
                                    onClick = { selectedCategory = category },
                                    label = { Text(category, fontSize = 12.sp) },
                                    modifier = Modifier.testTag("editor_category_chip_$category"),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    } else {
                        // Styled badge for selected category in View Mode
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = selectedCategory,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                             )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tags selection header & row
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = onDialogText.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (isEditMode) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Plus icon to add tags directly
                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = { showAddTagDialog = true },
                                    label = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add Tag",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("Add", fontSize = 12.sp)
                                        }
                                    },
                                    modifier = Modifier.testTag("editor_add_tag_chip"),
                                    shape = RoundedCornerShape(6.dp)
                                )
                            }

                            items(availableTags) { tag ->
                                val cleanTag = tag.trim().removePrefix("#")
                                if (cleanTag.isNotEmpty()) {
                                    val isSelected = selectedTags.contains(cleanTag)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedTags = if (isSelected) {
                                                selectedTags - cleanTag
                                            } else {
                                                selectedTags + cleanTag
                                            }
                                        },
                                        label = { Text("#$cleanTag", fontSize = 12.sp) },
                                        modifier = Modifier.testTag("editor_tag_chip_$cleanTag"),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // View mode for Tags
                        if (selectedTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                selectedTags.forEach { tag ->
                                    Box(
                                        modifier = Modifier
                                            .border(
                                                width = 1.dp,
                                                color = onDialogText.copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .background(
                                                onDialogText.copy(alpha = 0.03f),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                    ) {
                                        Text(
                                            text = "#$tag",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = onDialogText.copy(alpha = 0.85f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No Tags",
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                color = onDialogText.copy(alpha = 0.4f)
                             )
                        }
                    }

                    // Auto Tags Section (AI extracted tags)
                    if (isAiEnabled || selectedAutoTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Auto Tags",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = onDialogText.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (isEditMode) {
                            if (selectedAutoTags.isNotEmpty()) {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    items(selectedAutoTags.toList()) { tag ->
                                        val cleanTag = tag.trim().removePrefix("#")
                                        if (cleanTag.isNotEmpty()) {
                                            FilterChip(
                                                selected = true,
                                                onClick = {
                                                    selectedAutoTags = selectedAutoTags - cleanTag
                                                },
                                                label = { Text("#$cleanTag", fontSize = 12.sp) },
                                                modifier = Modifier.testTag("editor_autotag_chip_$cleanTag"),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "No AI auto tags generated yet",
                                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                    color = onDialogText.copy(alpha = 0.4f)
                                )
                            }
                        } else {
                            if (selectedAutoTags.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    selectedAutoTags.forEach { tag ->
                                        Box(
                                            modifier = Modifier
                                                .border(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "No AI Tags",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                    color = onDialogText.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }

                    if (showAddTagDialog) {
                        var newTagName by remember { mutableStateOf("") }
                        var isTagError by remember { mutableStateOf(false) }

                        AlertDialog(
                            onDismissRequest = { showAddTagDialog = false },
                            title = { Text("Create New Tag") },
                            text = {
                                Column {
                                    Text("Enter a name for the new tag:")
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = newTagName,
                                        onValueChange = {
                                            newTagName = it
                                            if (isTagError && it.trim().isNotEmpty()) {
                                                isTagError = false
                                            }
                                        },
                                        placeholder = { Text("e.g. Work, Urgent, School") },
                                        singleLine = true,
                                        isError = isTagError,
                                        modifier = Modifier.fillMaxWidth().testTag("add_tag_input_field"),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    if (isTagError) {
                                        Text(
                                            text = "Tag name cannot be empty",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    modifier = Modifier.testTag("submit_add_tag_button"),
                                    onClick = {
                                        val trimmedInput = newTagName.trim().removePrefix("#").trim()
                                        if (trimmedInput.isEmpty()) {
                                            isTagError = true
                                        } else {
                                            if (!availableTags.contains(trimmedInput)) {
                                                val updatedList = availableTags.toMutableList() + trimmedInput
                                                availableTags = updatedList
                                                com.example.data.CategoryPrefs.saveTags(context, updatedList)
                                            }
                                            selectedTags = selectedTags + trimmedInput
                                            showAddTagDialog = false
                                        }
                                    }
                                ) {
                                    Text("Add Tag")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showAddTagDialog = false }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    if (isEditMode) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // Visual Color circle picker
                        Text(
                            text = "Theme Color",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = onDialogText.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 0..6) {
                                val color = getNoteColor(i, true)
                                val onColor = getNoteOnColor(i, true)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            BorderStroke(
                                                width = if (selectedColorIndex == i) 2.5.dp else 1.dp,
                                                color = if (selectedColorIndex == i) MaterialTheme.colorScheme.primary else onColor.copy(alpha = 0.3f)
                                            ),
                                            CircleShape
                                        )
                                        .clickable { selectedColorIndex = i }
                                        .testTag("editor_color_picker_$i"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selectedColorIndex == i) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = onColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 5. Last Edited & Scheduling Info Row below Theme Color section (Inside scrollable Column)
                    val formatter = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                    val lastEditedStr = if (note != null) {
                        "Last Edited: " + formatter.format(java.util.Date(note.timestamp))
                    } else {
                        "Created: " + formatter.format(java.util.Date())
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            text = lastEditedStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = onDialogText.copy(alpha = 0.6f)
                        )
                        
                        if (isScheduled && scheduledTime != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                modifier = Modifier.testTag("editor_sched_stamp_squircle")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = "Scheduled Time stamp",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(scheduledTime!!)),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Fixed Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEditMode) {
                        TextButton(
                            onClick = {
                                keyboardController?.hide()
                                if (note == null) {
                                    onDismiss()
                                } else {
                                    isEditMode = false
                                }
                            },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("editor_cancel_button")
                        ) {
                            Text(if (note == null) "Cancel" else "Back to View")
                        }

                        Button(
                            onClick = {
                                if (title.trim().isEmpty()) {
                                    isTitleError = true
                                } else {
                                    keyboardController?.hide()
                                    onSave(
                                        title.trim(),
                                        if (isTaskList) content else content.trim(),
                                        selectedCategory,
                                        selectedColorIndex,
                                        isPinned,
                                        if (imageUrisList.isNotEmpty()) imageUrisList.joinToString("|") else null,
                                        isTaskList,
                                        if (websiteUrl.trim().isNotEmpty()) websiteUrl.trim() else null,
                                        isScheduled,
                                        scheduledTime,
                                        repeatFrequency,
                                        selectedTags.joinToString("|"),
                                        if (selectedAutoTags.isNotEmpty()) selectedAutoTags.joinToString("|") else null,
                                        attachmentUri,
                                        imageUrlState,
                                        imageStoragePathState,
                                        isPersistentNotification,
                                        isAiEnabled
                                    )
                                }
                            },
                            modifier = Modifier.testTag("editor_save_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save Note")
                        }
                    } else {
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                onDismiss()
                            },
                            modifier = Modifier.testTag("editor_close_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun parseMarkdownToAnnotatedString(text: String): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            } else if (text.startsWith("__", i)) {
                val end = text.indexOf("__", i + 2)
                if (end != -1) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append("__")
                    i += 2
                }
            } else if (text.startsWith("*", i)) {
                val end = text.indexOf("*", i + 1)
                if (end != -1) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append("*")
                    i += 1
                }
            } else {
                append(text[i])
                i++
            }
        }
    }
}

// Global Note Date Formatter
fun formatNoteDate(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Calendar.getInstance()
    val noteCalendar = Calendar.getInstance().apply { time = date }

    return if (now.get(Calendar.YEAR) == noteCalendar.get(Calendar.YEAR)) {
        if (now.get(Calendar.DAY_OF_YEAR) == noteCalendar.get(Calendar.DAY_OF_YEAR)) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
        }
    } else {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    }
}

// Helpers for mapped modern note paper styling
@Composable
private fun getNoteColor(colorIndex: Int, darkTheme: Boolean): Color {
    // Elegant Dark Theme Palette values override
    return when (colorIndex) {
        0 -> Color(0xFF2B2930) // Elegant Dark main Card background
        1 -> Color(0xFF3D2C2E) // Muted Elegant Burgundy
        2 -> Color(0xFF3C312B) // Muted Warm Terracotta
        3 -> Color(0xFF3B3929) // Muted Bronze Gold
        4 -> Color(0xFF2B3A34) // Muted Emerald Pine
        5 -> Color(0xFF273240) // Muted Slate Blue
        6 -> Color(0xFF332D41) // Elegant Dark violet-accent highlight Card
        else -> Color(0xFF2B2930)
    }
}

@Composable
private fun getNoteOnColor(colorIndex: Int, darkTheme: Boolean): Color {
    // Elegant Dark Theme Palette contrasting text/onColors
    return when (colorIndex) {
        0 -> Color(0xFFE6E1E5) // Elegant Dark primary text
        1 -> Color(0xFFFFD9DF) // Cotton Soft Pink contrast
        2 -> Color(0xFFFFDEC7) // Apricot Soft contrast
        3 -> Color(0xFFFFF3BE) // Butter Gold contrast
        4 -> Color(0xFFCBF1D9) // Mint Soft contrast
        5 -> Color(0xFFCFE4FF) // Blue Ice contrast
        6 -> Color(0xFFD0BCFF) // Elegant Dark accent purple text/onColor
        else -> Color(0xFFE6E1E5)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveCategoryChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    contentColor: androidx.compose.ui.graphics.Color? = null,
    borderColor: androidx.compose.ui.graphics.Color? = null,
    testTag: String
) {
    val actualContainerColor = containerColor ?: if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val actualContentColor = contentColor ?: if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val actualBorderColor = borderColor ?: if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    Surface(
        color = actualContainerColor,
        contentColor = actualContentColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, actualBorderColor),
        modifier = Modifier
            .testTag(testTag)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick ?: {}
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (leadingIcon != null) {
                leadingIcon()
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelWithAdjustments(),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun androidx.compose.material3.Typography.labelWithAdjustments() = this.labelLarge

@Composable
fun QrCodeRenderer(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val sizePx = size.width
        val numModules = 21 // 21x21 grid for Version 1 QR
        val moduleSize = sizePx / numModules

        // Draw background white
        drawRect(Color.White)

        // Draw the 3 finder patterns: top-left, top-right, bottom-left
        val finderIndices = listOf(
            Pair(0, 0),
            Pair(numModules - 7, 0),
            Pair(0, numModules - 7)
        )

        for (finder in finderIndices) {
            val fx = finder.first
            val fy = finder.second
            // Outer 7x7 solid block
            drawRect(
                color = Color.Black,
                topLeft = androidx.compose.ui.geometry.Offset(fx * moduleSize, fy * moduleSize),
                size = androidx.compose.ui.geometry.Size(7 * moduleSize, 7 * moduleSize)
            )
            // Inner 5x5 white block
            drawRect(
                color = Color.White,
                topLeft = androidx.compose.ui.geometry.Offset((fx + 1) * moduleSize, (fy + 1) * moduleSize),
                size = androidx.compose.ui.geometry.Size(5 * moduleSize, 5 * moduleSize)
            )
            // Center 3x3 solid block
            drawRect(
                color = Color.Black,
                topLeft = androidx.compose.ui.geometry.Offset((fx + 2) * moduleSize, (fy + 2) * moduleSize),
                size = androidx.compose.ui.geometry.Size(3 * moduleSize, 3 * moduleSize)
            )
        }

        // Draw random noise modules (seeded static generator)
        val seed = 42
        val random = java.util.Random(seed.toLong())

        for (r in 0 until numModules) {
            for (c in 0 until numModules) {
                // Skip finder pattern zones
                val inTopLeft = r < 8 && c < 8
                val inTopRight = r < 8 && c >= numModules - 8
                val inBottomLeft = r >= numModules - 8 && c < 8
                if (inTopLeft || inTopRight || inBottomLeft) {
                    continue
                }

                // Randomly color black or white
                if (random.nextBoolean()) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(c * moduleSize, r * moduleSize),
                        size = androidx.compose.ui.geometry.Size(moduleSize, moduleSize)
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedLogoHeader() {
    var animProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        animProgress = 1f
    }

    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = animProgress,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 800,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        )
    )

    val scale = 2.5f - (1.5f * progress)  // Animates smoothly from 2.5f to 1.0f
    val rotation = 45f * (1f - progress) // Animates smoothly from 45f to 0f
    val textAlpha = progress               // Animates alpha from 0f to 1f

    Row(
        modifier = Modifier
            .wrapContentWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // P
        Text(
            text = "P",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 0.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer(alpha = textAlpha)
        )

        // The Pin (Serving as the 'I')
        Icon(
            imageVector = Icons.Default.PushPin,
            contentDescription = "Pin letter I in PINotes",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .offset(x = (-3).dp) // Merges negative space seamlessly for a perfectly touching 'PI'
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    rotationZ = rotation
                )
        )

        // Notes
        Text(
            text = "Notes",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 0.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .offset(x = (-5).dp) // Merges negative space seamlessly for a perfectly touching 'INotes'
                .graphicsLayer(alpha = textAlpha)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        content()
    }
}




