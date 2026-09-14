package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Note
import com.example.data.NoteRepository
import com.example.data.CategoryPrefs
import com.example.sync.SyncManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SortOrder {
    LAST_UPDATED,
    FIRST_UPDATED
}

class NoteViewModel(val repository: NoteRepository, private val context: Context) : ViewModel() {

    private val layoutPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    val searchQuery = MutableStateFlow("")
    val selectedCategory = MutableStateFlow<String?>(null)
    val isGridLayout = MutableStateFlow(layoutPrefs.getBoolean("is_grid_layout", true))
    val sortOrder = MutableStateFlow(SortOrder.LAST_UPDATED)
    val includedTags = MutableStateFlow<Set<String>>(emptySet())
    val excludedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTagFilter = MutableStateFlow<String?>(null)
    val hiddenCategories = MutableStateFlow<Set<String>>(emptySet())

    val allRawNotes: StateFlow<List<Note>> = repository.allNotes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    fun filterNotes(
        allNotes: List<Note>,
        category: String?,
        query: String,
        incTags: Set<String>,
        excTags: Set<String>,
        hiddenCats: Set<String>,
        order: SortOrder
    ): List<Note> {
        val filtered = allNotes.filter { note ->
            val noteTags = note.tagList.map { it.trim().removePrefix("#").trim() }
            val matchesInc = incTags.isEmpty() || incTags.all { inc ->
                noteTags.any { it.equals(inc, ignoreCase = true) }
            }
            val matchesExc = excTags.isEmpty() || excTags.none { exc ->
                noteTags.any { it.equals(exc, ignoreCase = true) }
            }

            !note.isArchived &&
            (query.isEmpty() || note.title.contains(query, ignoreCase = true) || note.content.contains(query, ignoreCase = true)) &&
            matchesInc &&
            matchesExc &&
            if (category != null) {
                note.category.equals(category, ignoreCase = true)
            } else {
                !hiddenCats.contains(note.category)
            }
        }
        return if (order == SortOrder.LAST_UPDATED) {
            filtered.sortedWith(compareByDescending<Note> { it.isPinned }.thenBy { it.position }.thenByDescending { it.timestamp })
        } else {
            filtered.sortedWith(compareByDescending<Note> { it.isPinned }.thenBy { it.position }.thenBy { it.timestamp })
        }
    }

    val notesState: StateFlow<List<Note>> = combine(
        repository.allNotes,
        searchQuery,
        selectedCategory,
        sortOrder,
        includedTags,
        excludedTags,
        hiddenCategories
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val allNotes = flows[0] as List<Note>
        @Suppress("UNCHECKED_CAST")
        val query = flows[1] as String
        @Suppress("UNCHECKED_CAST")
        val category = flows[2] as String?
        @Suppress("UNCHECKED_CAST")
        val order = flows[3] as SortOrder
        @Suppress("UNCHECKED_CAST")
        val incTags = flows[4] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val excTags = flows[5] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val hiddenCats = flows[6] as Set<String>

        filterNotes(allNotes, category, query, incTags, excTags, hiddenCats, order)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val archivedNotesState: StateFlow<List<Note>> = combine(
        repository.allNotes,
        searchQuery
    ) { allNotes, query ->
        allNotes.filter { note ->
            note.isArchived &&
            (query.isEmpty() || note.title.contains(query, ignoreCase = true) || note.content.contains(query, ignoreCase = true))
        }.sortedByDescending { it.updatedAt }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tagsState: StateFlow<List<String>> = _tags.asStateFlow()

    init {
        _categories.value = CategoryPrefs.getCategories(context)
        _tags.value = CategoryPrefs.getTags(context)
        hiddenCategories.value = CategoryPrefs.getHiddenCategories(context)
    }

    fun toggleCategoryHidden(categoryName: String) {
        val current = hiddenCategories.value.toMutableSet()
        if (current.contains(categoryName)) {
            current.remove(categoryName)
        } else {
            current.add(categoryName)
        }
        hiddenCategories.value = current
        CategoryPrefs.saveHiddenCategories(context, current)
    }

    fun renameCategory(oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == oldName) return
        val currentList = _categories.value.toMutableList()
        val index = currentList.indexOf(oldName)
        if (index != -1) {
            currentList[index] = trimmed
            _categories.value = currentList
            CategoryPrefs.saveCategories(context, currentList)

            val currentHidden = hiddenCategories.value.toMutableSet()
            if (currentHidden.remove(oldName)) {
                currentHidden.add(trimmed)
                hiddenCategories.value = currentHidden
                CategoryPrefs.saveHiddenCategories(context, currentHidden)
            }

            if (selectedCategory.value == oldName) {
                selectedCategory.value = trimmed
            }

            viewModelScope.launch {
                repository.updateCategoryName(oldName, trimmed)
            }
        }
    }

    fun addTag(tagName: String) {
        val trimmed = tagName.trim().removePrefix("#").trim()
        if (trimmed.isEmpty()) return
        val currentList = _tags.value.toMutableList()
        if (!currentList.contains(trimmed)) {
            currentList.add(trimmed)
            _tags.value = currentList
            CategoryPrefs.saveTags(context, currentList)
        }
    }

    fun addCategory(categoryName: String) {
        val trimmed = categoryName.trim()
        if (trimmed.isEmpty()) return
        val currentList = _categories.value.toMutableList()
        if (!currentList.contains(trimmed)) {
            currentList.add(trimmed)
            _categories.value = currentList
            CategoryPrefs.saveCategories(context, currentList)
        }
    }

    fun saveNewCategoryOrder(newOrder: List<String>) {
        _categories.value = newOrder
        CategoryPrefs.saveCategories(context, newOrder)
    }

    fun deleteCategory(categoryName: String) {
        val currentList = _categories.value.toMutableList()
        if (currentList.remove(categoryName)) {
            _categories.value = currentList
            CategoryPrefs.saveCategories(context, currentList)
            if (selectedCategory.value == categoryName) {
                selectedCategory.value = null
            }
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSelectedCategory(category: String?) {
        selectedCategory.value = category
    }

    fun toggleTagFilter(tag: String) {
        val cleanTag = tag.trim().removePrefix("#").trim()
        if (cleanTag.isEmpty()) return
        val currentInc = includedTags.value.toMutableSet()
        val currentExc = excludedTags.value.toMutableSet()

        val incMatch = currentInc.find { it.equals(cleanTag, ignoreCase = true) }
        val excMatch = currentExc.find { it.equals(cleanTag, ignoreCase = true) }

        if (incMatch != null) {
            // Included -> Excluded
            currentInc.remove(incMatch)
            currentExc.add(cleanTag)
        } else if (excMatch != null) {
            // Excluded -> None
            currentExc.remove(excMatch)
        } else {
            // None -> Included
            currentInc.add(cleanTag)
        }

        includedTags.value = currentInc
        excludedTags.value = currentExc
    }

    fun clearTagFilters() {
        includedTags.value = emptySet()
        excludedTags.value = emptySet()
        selectedTagFilter.value = null
    }

    fun setSelectedTagFilter(tag: String?) {
        if (tag == null) {
            clearTagFilters()
        } else {
            toggleTagFilter(tag)
        }
    }

    fun toggleLayout() {
        val next = !isGridLayout.value
        isGridLayout.value = next
        layoutPrefs.edit().putBoolean("is_grid_layout", next).apply()
    }

    fun archiveNote(note: Note) {
        viewModelScope.launch {
            repository.update(note.copy(isArchived = true, isPinned = false, updatedAt = System.currentTimeMillis()))
            SyncManager.triggerSyncNow(context, repository)
        }
    }

    fun unarchiveNote(note: Note) {
        viewModelScope.launch {
            repository.update(note.copy(isArchived = false, updatedAt = System.currentTimeMillis()))
            SyncManager.triggerSyncNow(context, repository)
        }
    }

    fun archiveSelectedNotes(selectedIds: Set<Int>) {
        viewModelScope.launch {
            val all = repository.allNotes.first()
            all.filter { it.id in selectedIds }.forEach { note ->
                repository.update(note.copy(isArchived = true, isPinned = false, updatedAt = System.currentTimeMillis()))
            }
            SyncManager.triggerSyncNow(context, repository)
        }
    }

    fun refreshCategoriesAndTags() {
        _categories.value = CategoryPrefs.getCategories(context)
        _tags.value = CategoryPrefs.getTags(context)
    }

    fun insertNote(
        title: String,
        content: String,
        category: String,
        colorIndex: Int,
        isPinned: Boolean,
        imageUri: String? = null,
        isTaskList: Boolean = false,
        webUrl: String? = null,
        isScheduled: Boolean = false,
        scheduledTime: Long? = null,
        repeatFrequency: String? = null,
        tags: String? = null,
        autoTags: String? = null,
        attachmentUri: String? = null,
        imageUrl: String? = null,
        imageStoragePath: String? = null,
        isPersistentNotification: Boolean = false,
        onSuccess: ((Note) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val note = Note(
                title = title,
                content = content,
                category = category.trim().ifEmpty { "General" },
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
                isPersistentNotification = isPersistentNotification
            )
            val generatedId = repository.insert(note)
            val insertedNote = note.copy(id = generatedId.toInt())
            onSuccess?.invoke(insertedNote)
            SyncManager.triggerSyncNow(context, repository)
        }
    }

    fun updateNote(note: Note) {
        viewModelScope.launch {
            repository.update(note.copy(timestamp = System.currentTimeMillis()))
            SyncManager.triggerSyncNow(context, repository)
        }
    }

    fun updateNoteRaw(note: Note) {
        viewModelScope.launch {
            repository.update(note)
            SyncManager.triggerSyncNow(context, repository)
        }
    }

    fun updateNotePositions(newOrderList: List<Note>) {
        viewModelScope.launch {
            var changed = false
            newOrderList.forEachIndexed { index, note ->
                if (note.position != index) {
                    repository.update(note.copy(position = index))
                    changed = true
                }
            }
            if (changed) {
                SyncManager.triggerSyncNow(context, repository)
            }
        }
    }

    fun reorderNoteUp(note: Note) {
        val currentList = notesState.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == note.id }
        if (index > 0) {
            val prev = currentList[index - 1]
            currentList[index - 1] = note
            currentList[index] = prev
            updateNotePositions(currentList)
        }
    }

    fun reorderNoteDown(note: Note) {
        val currentList = notesState.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == note.id }
        if (index >= 0 && index < currentList.size - 1) {
            val next = currentList[index + 1]
            currentList[index + 1] = note
            currentList[index] = next
            updateNotePositions(currentList)
        }
    }

    fun togglePin(note: Note) {
        viewModelScope.launch {
            repository.update(note.copy(isPinned = !note.isPinned))
            SyncManager.triggerSyncNow(context, repository)
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            // Delete associated images from Firebase Storage if configured
            if (!note.imageStoragePath.isNullOrEmpty()) {
                try {
                    val app = com.example.sync.FirebaseConfig.getApp()
                    if (app != null) {
                        val storage = com.google.firebase.storage.FirebaseStorage.getInstance(app)
                        val paths = note.imageStoragePath.split("|").filter { it.isNotBlank() }
                        paths.forEach { path ->
                            android.util.Log.d("SyncManager", "Storage deleting (note deleted): Removing $path")
                            storage.reference.child(path).delete()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SyncManager", "Storage deletion warning: Failed to delete associated files on note delete: ${e.message}")
                }
            }
            repository.delete(note)
            try {
                com.example.NotificationScheduler.cancel(context, note.id)
                androidx.core.app.NotificationManagerCompat.from(context).cancel(note.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            SyncManager.triggerSyncNow(context, repository)
        }
    }
}

class NoteViewModelFactory(private val repository: NoteRepository, private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NoteViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NoteViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
