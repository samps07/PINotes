package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "General",
    val colorIndex: Int = 0,
    val isPinned: Boolean = false,
    val imageUri: String? = null,
    val isTaskList: Boolean = false,
    val webUrl: String? = null,
    val isScheduled: Boolean = false,
    val scheduledTime: Long? = null,
    val repeatFrequency: String? = null,
    val position: Int = 0,
    val tags: String? = null,
    val autoTags: String? = null,
    val attachmentUri: String? = null,
    val noteId: String = java.util.UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val attachmentsJson: String? = null,
    val imageUrl: String? = null,
    val imageStoragePath: String? = null,
    val isPersistentNotification: Boolean = false,
    val isArchived: Boolean = false
) {
    val imageUris: List<String>
        get() {
            val list = mutableListOf<String>()
            if (!imageUrl.isNullOrEmpty()) {
                list.addAll(imageUrl.split("|").filter { it.trim().isNotEmpty() })
            }
            if (!imageUri.isNullOrEmpty()) {
                val localUris = imageUri.split("|").filter { it.trim().isNotEmpty() }
                for (local in localUris) {
                    if (local.startsWith("http://") || local.startsWith("https://")) {
                        if (!list.contains(local)) list.add(local)
                    } else {
                        list.add(local)
                    }
                }
            }
            return list.distinct()
        }

    val tagList: List<String>
        get() = if (tags.isNullOrEmpty()) emptyList() else tags.split("|").filter { it.trim().isNotEmpty() }

    val autoTagList: List<String>
        get() = if (autoTags.isNullOrEmpty()) emptyList() else autoTags.split("|").filter { it.trim().isNotEmpty() }
}

data class TaskItem(
    val text: String,
    val isChecked: Boolean,
    val values: List<String> = if (text.contains(" | ")) text.split(" | ").map { it.trim() } else listOf(text)
)

data class TableTaskItem(
    val values: List<String>,
    val isChecked: Boolean
) {
    val text: String
        get() = values.joinToString(" | ")
}

data class TableListData(
    val headers: List<String>,
    val items: List<TableTaskItem>
)

fun parseTableListData(content: String): TableListData {
    if (content.isEmpty()) return TableListData(listOf("Item"), emptyList())
    val lines = content.split("\n")
    var headers = mutableListOf<String>()
    val items = mutableListOf<TableTaskItem>()

    for (line in lines) {
        if (line.isEmpty() && lines.size > 1 && items.isEmpty() && headers.isEmpty()) continue
        if (line.startsWith("[HEADERS] ", ignoreCase = true)) {
            val headerStr = line.substring(10)
            if (headerStr.isNotEmpty()) {
                headers = headerStr.split(" | ").toMutableList()
            }
        } else if (line.startsWith("[HEADERS]", ignoreCase = true)) {
            val headerStr = line.removePrefix("[HEADERS]").removePrefix("[headers]").removePrefix(":").trim()
            if (headerStr.isNotEmpty()) {
                headers = headerStr.split(" | ").toMutableList()
            }
        } else {
            val isChecked = line.startsWith("[x] ", ignoreCase = true) || line.startsWith("[x]", ignoreCase = true)
            val isUnchecked = line.startsWith("[ ] ") || line.startsWith("[ ]")
            val rawText = when {
                line.startsWith("[x] ", ignoreCase = true) -> line.substring(4)
                line.startsWith("[ ] ") -> line.substring(4)
                line.startsWith("[x]", ignoreCase = true) -> line.substring(3)
                line.startsWith("[ ]") -> line.substring(3)
                else -> line
            }

            // Filter out corrupt legacy [HEADERS] text item
            if (rawText.startsWith("[HEADERS]", ignoreCase = true) || rawText.equals("[HEADERS]", ignoreCase = true)) {
                continue
            }

            val columns = if (rawText.contains(" | ")) {
                rawText.split(" | ")
            } else if (rawText.contains("|")) {
                rawText.split("|").map { if (it.startsWith(" ") && it.endsWith(" ")) it.substring(1, it.length - 1) else it }
            } else {
                listOf(rawText)
            }
            items.add(TableTaskItem(values = columns, isChecked = isChecked))
        }
    }

    val maxCols = maxOf(headers.size, items.maxOfOrNull { it.values.size } ?: 1, 1)

    if (headers.isEmpty()) {
        if (maxCols == 1) {
            headers.add("Item")
        } else {
            for (i in 1..maxCols) {
                headers.add(if (i == 1) "Item" else "Column $i")
            }
        }
    } else {
        while (headers.size < maxCols) {
            headers.add("Column ${headers.size + 1}")
        }
    }

    val normalizedItems = items.map { item ->
        if (item.values.size < maxCols) {
            val newVals = item.values.toMutableList()
            while (newVals.size < maxCols) {
                newVals.add("")
            }
            item.copy(values = newVals)
        } else {
            item
        }
    }

    return TableListData(headers = headers, items = normalizedItems)
}

fun serializeTableListData(data: TableListData, sortCheckedToBottom: Boolean = false): String {
    val sb = StringBuilder()
    val isDefaultSingleHeader = data.headers.size == 1 && (data.headers.first() == "Item" || data.headers.first().isBlank())
    if (!isDefaultSingleHeader && data.headers.isNotEmpty() && data.headers.any { it.isNotBlank() }) {
        sb.append("[HEADERS] ").append(data.headers.joinToString(" | ")).append("\n")
    }

    val list = if (sortCheckedToBottom) data.items.sortedBy { it.isChecked } else data.items
    val itemsStr = list.joinToString("\n") { item ->
        val prefix = if (item.isChecked) "[x] " else "[ ] "
        val rowStr = item.values.joinToString(" | ")
        "$prefix$rowStr"
    }
    sb.append(itemsStr)
    return sb.toString()
}

fun parseTaskItems(content: String): List<TaskItem> {
    val tableData = parseTableListData(content)
    return tableData.items.map { item ->
        TaskItem(
            text = item.text,
            isChecked = item.isChecked,
            values = item.values
        )
    }
}

fun serializeTaskItems(items: List<TaskItem>, sortCheckedToBottom: Boolean = false): String {
    val list = if (sortCheckedToBottom) items.sortedBy { it.isChecked } else items
    return list.joinToString("\n") { item ->
        val prefix = if (item.isChecked) "[x] " else "[ ] "
        "$prefix${item.text}"
    }
}

fun formatNoteForCopying(note: Note): String {
    return formatNoteTextForCopying(
        title = note.title,
        content = note.content,
        isTaskList = note.isTaskList,
        webUrl = note.webUrl
    )
}

fun formatNoteTextForCopying(
    title: String,
    content: String,
    isTaskList: Boolean,
    webUrl: String? = null
): String {
    val sb = StringBuilder()
    if (title.isNotBlank()) {
        sb.append(title.trim()).append("\n\n")
    }

    if (isTaskList) {
        val tableData = parseTableListData(content)
        if (tableData.headers.size > 1) {
            val headerLine = tableData.headers.joinToString(" | ")
            sb.append(headerLine).append("\n")
            val separator = tableData.headers.joinToString(" | ") { "-".repeat(maxOf(3, it.length)) }
            sb.append(separator).append("\n")
            tableData.items.forEach { item ->
                val check = if (item.isChecked) "☑" else "☐"
                val rowVals = item.values.joinToString(" | ")
                sb.append("$check $rowVals\n")
            }
        } else {
            tableData.items.forEach { item ->
                val check = if (item.isChecked) "☑" else "☐"
                val itemText = item.values.firstOrNull() ?: item.text
                sb.append("$check $itemText\n")
            }
        }
    } else {
        sb.append(content)
    }

    if (!webUrl.isNullOrBlank() && !content.contains(webUrl)) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(webUrl.trim())
    }

    return sb.toString().trim()
}

