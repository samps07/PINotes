package com.example

import android.os.Bundle
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.AppDatabase
import com.example.data.Note
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getDatabase(this)
        val noteDao = database.noteDao()
        val repo = com.example.data.NoteRepository(noteDao)
        com.example.sync.SyncManager.initializeSync(this, repo)

        var initialImageUri: String? = null
        var initialAttachmentUri: String? = null
        var initialTextContent: String = ""
        var initialTitle: String = ""

        if (intent?.action == Intent.ACTION_SEND) {
            val type = intent.type ?: "*/*"
            val streamUri = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }) ?: intent.clipData?.let { cd ->
                if (cd.itemCount > 0) cd.getItemAt(0).uri else null
            } ?: intent.data

            if (streamUri != null) {
                if (type.startsWith("image/")) {
                    val copiedUri = MainActivity.copyUriToLocalFile(this, streamUri)
                    if (copiedUri != null) {
                        initialImageUri = copiedUri.toString()
                    }
                } else {
                    val copiedUri = MainActivity.copyUriToLocalFileWithRealName(this, streamUri)
                    if (copiedUri != null) {
                        initialAttachmentUri = copiedUri.toString()
                    }
                }
            }

            val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val extraSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (!extraText.isNullOrBlank()) {
                initialTextContent = extraText
            }
            if (!extraSubject.isNullOrBlank()) {
                initialTitle = extraSubject
            }
        }

        setContent {
            MyApplicationTheme {
                ShareOverlayScreen(
                    initialImageUri = initialImageUri,
                    initialAttachmentUri = initialAttachmentUri,
                    initialText = initialTextContent,
                    initialTitle = initialTitle,
                    onDismiss = { finish() },
                    onSave = { title, content, category, colorIndex, isPinned, imageUri, isTaskList, webUrl, isScheduled, scheduledTime, repeatFrequency, tags, autoTags, attachmentUri, imageUrl, imageStoragePath, isPersistentNotification, nId ->
                        saveSharedNoteAndNotify(title, content, category, colorIndex, isPinned, imageUri, isTaskList, webUrl, isScheduled, scheduledTime, repeatFrequency, tags, autoTags, attachmentUri, imageUrl, imageStoragePath, isPersistentNotification, nId)
                    }
                )
            }
        }
    }

    private fun saveSharedNoteAndNotify(
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
        noteId: String
    ) {
        lifecycleScope.launch {
            val database = AppDatabase.getDatabase(this@ShareActivity)
            val noteDao = database.noteDao()
            val note = Note(
                title = title,
                content = content,
                category = category,
                colorIndex = colorIndex,
                isPinned = isPinned,
                timestamp = System.currentTimeMillis(),
                imageUri = imageUri,
                isTaskList = isTaskList,
                webUrl = webUrl,
                isScheduled = isScheduled,
                scheduledTime = scheduledTime,
                repeatFrequency = repeatFrequency,
                tags = tags,
                autoTags = autoTags,
                attachmentUri = attachmentUri,
                imageUrl = imageUrl,
                imageStoragePath = imageStoragePath,
                isPersistentNotification = isPersistentNotification,
                noteId = noteId
            )
            val generatedId = noteDao.insertNote(note)
            val finalNote = note.copy(id = generatedId.toInt())

            val repo = com.example.data.NoteRepository(noteDao)
            com.example.sync.SyncManager.triggerSyncNow(this@ShareActivity, repo)

            val isMap = com.example.data.WebpageScraper.isMapUrl(webUrl)
            if (!webUrl.isNullOrEmpty() && !isMap) {
                com.example.data.BackgroundCurationManager.startCuration(this@ShareActivity, finalNote.id, webUrl)
            }

            if (finalNote.isPersistentNotification) {
                MainActivity.companionPushNoteNotification(this@ShareActivity, finalNote)
            }
            if (isScheduled && scheduledTime != null) {
                if (scheduledTime <= System.currentTimeMillis()) {
                    if (!finalNote.isPersistentNotification) {
                        MainActivity.companionPushNoteNotification(this@ShareActivity, finalNote)
                    }
                } else {
                    NotificationScheduler.schedule(this@ShareActivity, finalNote)
                }
            }
            Toast.makeText(this@ShareActivity, "Note created with attachment!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

@Composable
fun ShareOverlayScreen(
    initialImageUri: String?,
    initialAttachmentUri: String?,
    initialText: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, category: String, colorIndex: Int, isPinned: Boolean, imageUri: String?, isTaskList: Boolean, webUrl: String?, isScheduled: Boolean, scheduledTime: Long?, repeatFrequency: String?, tags: String?, autoTags: String?, attachmentUri: String?, imageUrl: String?, imageStoragePath: String?, isPersistentNotification: Boolean, noteId: String) -> Unit
) {
    var selectedColorIndex by remember { mutableStateOf(0) }
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val dialogBg = getNoteColor(selectedColorIndex, darkTheme)
    val noteId = remember { java.util.UUID.randomUUID().toString() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onDismiss() }, // Sapping outside dismisses
        contentAlignment = Alignment.Center
    ) {
        // Prevent click events nested in the card from triggering background click
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            color = dialogBg,
            tonalElevation = 6.dp
        ) {
            ShareEditorContent(
                initialImageUri = initialImageUri,
                initialAttachmentUri = initialAttachmentUri,
                initialText = initialText,
                initialTitle = initialTitle,
                onDismiss = onDismiss,
                onSave = onSave,
                selectedColorIndex = selectedColorIndex,
                onColorChange = { selectedColorIndex = it },
                noteId = noteId
            )
        }
    }
}

@Composable
fun ShareEditorContent(
    initialImageUri: String?,
    initialAttachmentUri: String?,
    initialText: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, category: String, colorIndex: Int, isPinned: Boolean, imageUri: String?, isTaskList: Boolean, webUrl: String?, isScheduled: Boolean, scheduledTime: Long?, repeatFrequency: String?, tags: String?, autoTags: String?, attachmentUri: String?, imageUrl: String?, imageStoragePath: String?, isPersistentNotification: Boolean, noteId: String) -> Unit,
    selectedColorIndex: Int,
    onColorChange: (Int) -> Unit,
    noteId: String
) {
    var titleValue by remember { mutableStateOf(TextFieldValue(initialTitle, TextRange(initialTitle.length))) }
    var title by DynamicTextDelegate({ titleValue }, { titleValue = it })

    var contentValue by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
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

    var selectedCategory by remember { mutableStateOf("General") }
    var isPinned by remember { mutableStateOf(false) }
    var imageUri by remember { mutableStateOf(initialImageUri) }
    var imageUrlState by remember { mutableStateOf<String?>(null) }
    var imageStoragePathState by remember { mutableStateOf<String?>(null) }
    val localCtx = LocalContext.current
    
    LaunchedEffect(imageUri) {
        val uriStr = imageUri
        if (!uriStr.isNullOrEmpty() && !uriStr.startsWith("http://") && !uriStr.startsWith("https://")) {
            val uploaded = com.example.sync.SyncManager.uploadAttachedImageImmediately(
                context = localCtx,
                noteId = noteId,
                localUriStr = uriStr
            )
            if (uploaded != null) {
                imageUrlState = uploaded.first
                imageStoragePathState = uploaded.second
                imageUri = uploaded.first // update with public URL so it displays and syncs cleanly
            }
        }
    }
    
    var selectedAutoTags by remember { mutableStateOf(emptySet<String>()) }
    var attachmentUri by remember { mutableStateOf(initialAttachmentUri) }
    
    // Scheduling States
    var isScheduled by remember { mutableStateOf(false) } // default false in overlay
    var scheduledTime by remember { mutableStateOf<Long?>(null) }
    var repeatFrequency by remember { mutableStateOf("once") }
    var showSchedulingWindow by remember { mutableStateOf(false) } // hide by default
    var isImmediate by remember { mutableStateOf(false) }
    var isPersistentNotification by remember { mutableStateOf(false) }

    var isTaskList by remember { mutableStateOf(false) }

    // AI Metadata Extraction States
    var isAiEnabled by remember { mutableStateOf(false) }
    var websiteUrl by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var extractionError by remember { mutableStateOf<String?>(null) }
    var isManualEditAllowed by remember { mutableStateOf(false) }

    var isTitleError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    var availableTags by remember {
        mutableStateOf(com.example.data.CategoryPrefs.getTags(context))
    }
    var showAddTagDialog by remember { mutableStateOf(false) }
    val categories = remember { com.example.data.CategoryPrefs.getCategories(context) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val onDialogText = getNoteOnColor(selectedColorIndex, androidx.compose.foundation.isSystemInDarkTheme())
    val scope = rememberCoroutineScope()

    // Automatically detect shared URL and pre-trigger extraction
    LaunchedEffect(initialText) {
        val parsedUrl = com.example.data.WebpageScraper.extractUrlFromText(initialText)
        if (!parsedUrl.isNullOrEmpty()) {
            websiteUrl = parsedUrl
            val isMap = com.example.data.WebpageScraper.isMapUrl(parsedUrl)
            if (!isMap) {
                isAiEnabled = true
                isExtracting = true
                try {
                    val metadata = com.example.data.WebpageScraper.extractMetadata(parsedUrl, context)
                    if (metadata.title.isNotEmpty()) {
                        title = metadata.title
                        content = metadata.summary
                        if (metadata.imageUrl.isNotEmpty()) {
                            imageUri = metadata.imageUrl
                        }
                        if (!metadata.category.isNullOrEmpty()) {
                            val cleanCat = metadata.category.trim()
                            if (cleanCat.isNotEmpty()) {
                                selectedCategory = cleanCat
                                val currentCats = com.example.data.CategoryPrefs.getCategories(context).toMutableList()
                                if (!currentCats.contains(cleanCat)) {
                                    currentCats.add(cleanCat)
                                    com.example.data.CategoryPrefs.saveCategories(context, currentCats)
                                }
                            }
                        }
                        if (!metadata.tags.isNullOrEmpty()) {
                            val extractedList = metadata.tags.split(Regex("[|,]+"))
                                .map { it.trim().removePrefix("#").trim() }
                                .filter { it.isNotEmpty() }
                            if (extractedList.isNotEmpty()) {
                                selectedAutoTags = extractedList.toSet()
                            }
                        }
                        isManualEditAllowed = true
                        isAiEnabled = false
                    }
                } catch (e: java.lang.Exception) {
                    extractionError = e.message
                } finally {
                    isExtracting = false
                }
            } else {
                isAiEnabled = false
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(20.dp)
            .fillMaxWidth()
    ) {
        // Fixed Header Row with actions (Unified Squircle containing AI, Checklist, Push Notification, Pin)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "New Note",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = onDialogText,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )

            // Optimized Horizontal Squircle Container
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
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
                        modifier = Modifier.size(34.dp).testTag("share_ai_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Extraction Toggle",
                            tint = if (isAiEnabled) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
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
                        modifier = Modifier.size(34.dp).testTag("share_task_list_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Toggle Checklist Mode",
                            tint = if (isTaskList) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
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
                                if (scheduledTime == null) {
                                    scheduledTime = System.currentTimeMillis() + 1000L
                                }
                            }
                        },
                        modifier = Modifier.size(34.dp).testTag("share_schedule_switch")
                    ) {
                        Icon(
                            imageVector = if (showSchedulingWindow) Icons.Default.Schedule else Icons.Outlined.Schedule,
                            contentDescription = "Schedule Notification",
                            tint = if (showSchedulingWindow) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 4. Pin toggle in Dialog
                    IconToggleButton(
                        checked = isPinned,
                        onCheckedChange = { isPinned = it },
                        modifier = Modifier.size(34.dp).testTag("share_pin_toggle")
                    ) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin Note",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else onDialogText.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Scrollable Content
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
        ) {
            // Source Link Field (Visible if we have a web URL, or if AI Mode is Enabled)
            if (isAiEnabled || websiteUrl.isNotEmpty()) {
                OutlinedTextField(
                    value = websiteUrl,
                    onValueChange = { websiteUrl = it },
                    label = { Text("Web URL", style = MaterialTheme.typography.bodySmall) },
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
                        if (isAiEnabled) {
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
                                                        imageUri = metadata.imageUrl
                                                    }
                                                    if (!metadata.category.isNullOrEmpty()) {
                                                        val cleanCat = metadata.category.trim()
                                                        if (cleanCat.isNotEmpty()) {
                                                            selectedCategory = cleanCat
                                                            val currentCats = com.example.data.CategoryPrefs.getCategories(context).toMutableList()
                                                            if (!currentCats.contains(cleanCat)) {
                                                                currentCats.add(cleanCat)
                                                                com.example.data.CategoryPrefs.saveCategories(context, currentCats)
                                                            }
                                                        }
                                                    }
                                                    if (!metadata.tags.isNullOrEmpty()) {
                                                        val extractedList = metadata.tags.split(Regex("[|,]+"))
                                                            .map { it.trim().removePrefix("#").trim() }
                                                            .filter { it.isNotEmpty() }
                                                        if (extractedList.isNotEmpty()) {
                                                            selectedAutoTags = extractedList.toSet()
                                                        }
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
                                    modifier = Modifier.testTag("share_run_ai_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "Extract via AI",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_website_url_input"),
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

            // Title Input
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
                    .testTag("share_title_input"),
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

            Spacer(modifier = Modifier.height(12.dp))

            // Dynamic Body: Checklist editor or rich text multiline area
            if (isTaskList) {
                com.example.ui.TableListNoteComponent(
                    content = content,
                    onContentChange = { newContent -> content = newContent },
                    isEditMode = true,
                    onDialogTextColor = onDialogText
                )
            } else {
                // Content Input
                OutlinedTextField(
                    value = contentValue,
                    onValueChange = { contentValue = it },
                    label = { Text("Note content") },
                    placeholder = { Text("Write something down...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 180.dp)
                        .onFocusChanged { isContentFocused = it.isFocused }
                        .testTag("share_content_input"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Image attachment preview if present
            if (!imageUri.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Shared Attachment",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )

                    // Clear button
                    IconButton(
                        onClick = { imageUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .size(32.dp)
                            .testTag("share_clear_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove Attachment",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // File Attachment preview if present
            if (!attachmentUri.isNullOrEmpty()) {
                val attachmentName = try {
                    val uri = Uri.parse(attachmentUri)
                    val decodedPath = Uri.decode(uri.toString())
                    val lastSegment = decodedPath.substringAfterLast('/')
                    val cleanName = lastSegment.substringBefore('?')
                    if (cleanName.isNotEmpty() && cleanName != "null") cleanName else "Attached File"
                } catch (e: Exception) {
                    "Attached File"
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
                        contentDescription = "Attached File",
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
                            text = "Local Attachment",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        )
                    }

                    IconButton(
                        onClick = {
                            try {
                                val uri = Uri.parse(attachmentUri)
                                val file = java.io.File(uri.path ?: "")
                                if (file.exists()) {
                                    val pathUri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "com.example.fileprovider",
                                        file
                                    )
                                    val mime = MainActivity.getMimeTypeForFile(file)
                                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(pathUri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(openIntent)
                                } else {
                                    Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Launch,
                            contentDescription = "Open Attachment",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = { attachmentUri = null },
                        modifier = Modifier.testTag("share_remove_attachment_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove Attachment",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Persistently visible Text Formatting Toolbar in ShareActivity
            if (!isTaskList) {
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

            // Scheduling Window Section in ShareActivity
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

                        // Immediate Toggle Button (Styled exactly same as Pick Date & Time button)
                        OutlinedButton(
                            onClick = {
                                isImmediate = !isImmediate
                                if (isImmediate) {
                                    scheduledTime = System.currentTimeMillis() + 1000L
                                    isScheduled = true
                                    Toast.makeText(context, "Configured to schedule immediately on save!", Toast.LENGTH_SHORT).show()
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
                                    text = "Immediate Schedule",
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

            Spacer(modifier = Modifier.height(14.dp))

            // Category choice
            Text(
                text = "Category",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = onDialogText.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category, fontSize = 12.sp) },
                        modifier = Modifier.testTag("share_category_chip_$category"),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tags selection in ShareActivity
            Text(
                text = "Tags",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = onDialogText.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(6.dp))
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
                        modifier = Modifier.testTag("share_add_tag_chip"),
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
                            modifier = Modifier.testTag("share_tag_chip_$cleanTag"),
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                }
            }

            // Auto Tags Section (AI extracted tags) in ShareActivity
            if (isAiEnabled || selectedAutoTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Auto Tags",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = onDialogText.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(6.dp))
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
                                    modifier = Modifier.testTag("share_autotag_chip_$cleanTag"),
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

            Spacer(modifier = Modifier.height(14.dp))

            // Visual Theme Colors
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
                            .clickable { onColorChange(i) }
                            .testTag("share_color_picker_$i"),
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

            // 5. Last Edited & Scheduling Info Row below Theme Color section (Inside scrollable column)
            val formatter = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            val lastEditedStr = "Created: " + formatter.format(java.util.Date())

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

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("share_cancel_button")
            ) {
                Text("Cancel")
            }

            Button(
                onClick = {
                    if (title.trim().isEmpty()) {
                        isTitleError = true
                    } else {
                        keyboardController?.hide()
                        onSave(
                            title.trim(),
                            content.trim(),
                            selectedCategory,
                            selectedColorIndex,
                            isPinned,
                            imageUri,
                            isTaskList,
                            if (isAiEnabled || websiteUrl.isNotEmpty()) websiteUrl.trim() else null,
                            isScheduled,
                            scheduledTime,
                            repeatFrequency,
                            if (selectedTags.isNotEmpty()) selectedTags.joinToString("|") else null,
                            if (selectedAutoTags.isNotEmpty()) selectedAutoTags.joinToString("|") else null,
                            attachmentUri,
                            imageUrlState,
                            imageStoragePathState,
                            isPersistentNotification,
                            noteId
                        )
                    }
                },
                modifier = Modifier.testTag("share_save_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save & Close")
            }
        }
    }
}

@Composable
private fun getNoteColor(colorIndex: Int, darkTheme: Boolean): Color {
    return when (colorIndex) {
        0 -> Color(0xFF2B2930)
        1 -> Color(0xFF3D2C2E)
        2 -> Color(0xFF3C312B)
        3 -> Color(0xFF3B3929)
        4 -> Color(0xFF2B3A34)
        5 -> Color(0xFF273240)
        6 -> Color(0xFF332D41)
        else -> Color(0xFF2B2930)
    }
}

@Composable
private fun getNoteOnColor(colorIndex: Int, darkTheme: Boolean): Color {
    return when (colorIndex) {
        0 -> Color(0xFFE6E1E5)
        1 -> Color(0xFFFFD9DF)
        2 -> Color(0xFFFFDEC7)
        3 -> Color(0xFFFFF3BE)
        4 -> Color(0xFFCBF1D9)
        5 -> Color(0xFFCFE4FF)
        6 -> Color(0xFFD0BCFF)
        else -> Color(0xFFE6E1E5)
    }
}

