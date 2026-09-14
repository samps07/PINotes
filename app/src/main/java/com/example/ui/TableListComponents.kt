package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TableListData
import com.example.data.TableTaskItem
import com.example.data.parseTableListData
import com.example.data.serializeTableListData

fun calculateColumnFooter(values: List<String>): String {
    val nonBlank = values.map { it.trim() }.filter { it.isNotEmpty() }
    if (nonBlank.isEmpty()) {
        return "0 items"
    }

    fun parseNumericValue(s: String): Double? {
        val cleaned = s.replace(",", "")
            .replace("$", "")
            .replace("€", "")
            .replace("£", "")
            .replace("₹", "")
            .replace("¥", "")
            .trim()
        return cleaned.toDoubleOrNull()
    }

    val numericValues = nonBlank.map { parseNumericValue(it) }
    val isAllNumeric = numericValues.all { it != null }

    if (isAllNumeric) {
        val sum = numericValues.filterNotNull().sum()
        val hasDecimals = nonBlank.any { it.contains(".") }
        val formattedSum = if (hasDecimals) {
            val formatted = String.format("%.2f", sum)
            if (formatted.endsWith(".00") && nonBlank.none { it.endsWith(".00") || it.contains(".0") }) {
                if (sum % 1.0 == 0.0) sum.toLong().toString() else formatted
            } else {
                formatted
            }
        } else {
            if (sum % 1.0 == 0.0) sum.toLong().toString() else String.format("%.2f", sum)
        }

        val currencyPrefix = nonBlank.firstOrNull()?.let { first ->
            val c = first.firstOrNull()
            if (c in listOf('$', '€', '£', '₹', '¥')) c.toString() else ""
        } ?: ""

        return "$currencyPrefix$formattedSum"
    } else {
        val count = nonBlank.size
        return if (count == 1) "1 item" else "$count items"
    }
}

@Composable
fun TableListNoteComponent(
    content: String,
    onContentChange: (String) -> Unit,
    isEditMode: Boolean,
    onSaveRequested: (() -> Unit)? = null,
    onDialogTextColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val tableData = remember(content) { parseTableListData(content) }
    var showCheckedDropdown by remember { mutableStateOf(false) }
    var showColumnConfigDialog by remember { mutableStateOf(false) }

    val uncheckedItems = remember(tableData) { tableData.items.filter { !it.isChecked } }
    val checkedItems = remember(tableData) { tableData.items.filter { it.isChecked } }

    val headers = tableData.headers
    val maxValCols = tableData.items.maxOfOrNull { it.values.size } ?: 1
    val colCount = maxOf(headers.size, maxValCols)
    val isSingleCol = colCount <= 1
    val colWidth = 150.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- Edit Mode Control Bar (+R, Manage Columns, +C) ---
        if (isEditMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // +R Button (Row)
                FilledTonalButton(
                    onClick = {
                        val emptyRowVals = List(colCount) { "" }
                        val newUnchecked = uncheckedItems.toMutableList()
                        newUnchecked.add(TableTaskItem(emptyRowVals, false))
                        val updatedList = newUnchecked + checkedItems
                        onContentChange(serializeTableListData(TableListData(headers, updatedList)))
                    },
                    modifier = Modifier.testTag("add_item_button"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("+R", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }

                // Manage Columns Button (no icon)
                OutlinedButton(
                    onClick = { showColumnConfigDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manage_columns_button"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Manage Columns ($colCount)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // +C Button (Column)
                FilledTonalButton(
                    onClick = {
                        val newHeaders = headers.toMutableList()
                        val nextName = if (newHeaders.isEmpty()) "Item" else "Column ${newHeaders.size + 1}"
                        newHeaders.add(nextName)
                        val newItems = tableData.items.map { item ->
                            val newVals = item.values.toMutableList()
                            newVals.add("")
                            item.copy(values = newVals)
                        }
                        onContentChange(serializeTableListData(TableListData(newHeaders, newItems)))
                    },
                    modifier = Modifier.testTag("add_column_button"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("+C", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // --- Unchecked Items Table ---
        val scrollState = rememberScrollState()
        val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidth = maxWidth
            val fixedCheckboxWidth = 44.dp
            val actionsWidth = if (isEditMode) 100.dp else 0.dp
            val fixedTotalWidth = fixedCheckboxWidth + actionsWidth
            val minColWidth = 120.dp

            val dynamicColWidth = if (colCount > 0) {
                val remaining = availableWidth - fixedTotalWidth
                val calculated = remaining / colCount
                maxOf(minColWidth, calculated)
            } else minColWidth

            val totalTableWidth = fixedTotalWidth + (dynamicColWidth * colCount)
            val needsHScroll = totalTableWidth > availableWidth
            val uncheckedTableWidth = if (needsHScroll) totalTableWidth else null

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
                border = BorderStroke(1.dp, gridLineColor)
            ) {
                val tableContent: @Composable () -> Unit = {
                    Column(
                        modifier = if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth()
                    ) {
                        // Header Row
                        if (headers.isNotEmpty()) {
                            Row(
                                modifier = (if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth())
                                    .height(IntrinsicSize.Min)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {}

                                headers.forEachIndexed { cIdx, hText ->
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(gridLineColor)
                                    )
                                    Box(
                                        modifier = if (uncheckedTableWidth == null) {
                                            Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                        } else {
                                            Modifier
                                                .width(dynamicColWidth)
                                                .fillMaxHeight()
                                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                        },
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                    Text(
                                        text = if (hText.isBlank()) {
                                            if (cIdx == 0 && headers.size == 1) "Item" else "Column ${cIdx + 1}"
                                        } else hText,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (isEditMode) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(gridLineColor)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .fillMaxHeight()
                                        .padding(horizontal = 6.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Actions",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth(),
                            color = gridLineColor,
                            thickness = 1.dp
                        )
                    }

                    // Unchecked Rows
                    if (uncheckedItems.isEmpty()) {
                        Box(
                            modifier = (if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth())
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isEditMode) "No active items. Tap '+R' to add." else "All items completed!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = onDialogTextColor.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        uncheckedItems.forEachIndexed { uIdx, item ->
                            Row(
                                modifier = (if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth())
                                    .height(IntrinsicSize.Min)
                                    .testTag("task_item_row_$uIdx"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Checkbox(
                                        checked = false,
                                        onCheckedChange = {
                                            val currentUnchecked = uncheckedItems.toMutableList()
                                            val itemToComplete = currentUnchecked.removeAt(uIdx)
                                            val currentChecked = checkedItems.toMutableList()
                                            currentChecked.add(itemToComplete.copy(isChecked = true))
                                            val updatedList = currentUnchecked + currentChecked
                                            val newContent = serializeTableListData(TableListData(headers, updatedList))
                                            onContentChange(newContent)
                                            if (!isEditMode) onSaveRequested?.invoke()
                                        },
                                        modifier = Modifier.testTag("task_checkbox_$uIdx")
                                    )
                                }

                                (0 until colCount).forEach { cIdx ->
                                    val valText = item.values.getOrElse(cIdx) { "" }
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(gridLineColor)
                                    )
                                    Box(
                                        modifier = if (uncheckedTableWidth == null) {
                                            Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        } else {
                                            Modifier
                                                .width(dynamicColWidth)
                                                .fillMaxHeight()
                                        },
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (isEditMode) {
                                            BasicTextField(
                                                value = valText,
                                                onValueChange = { newVal ->
                                                    val currentUnchecked = uncheckedItems.toMutableList()
                                                    val rowVals = item.values.toMutableList()
                                                    while (rowVals.size <= cIdx) rowVals.add("")
                                                    rowVals[cIdx] = newVal
                                                    currentUnchecked[uIdx] = item.copy(values = rowVals)
                                                    val updatedList = currentUnchecked + checkedItems
                                                    onContentChange(serializeTableListData(TableListData(headers, updatedList)))
                                                },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                    color = onDialogTextColor,
                                                    fontSize = 14.sp
                                                ),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                decorationBox = { innerTextField ->
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                                        contentAlignment = Alignment.CenterStart
                                                    ) {
                                                        if (valText.isEmpty()) {
                                                            val hName = headers.getOrElse(cIdx) { "" }
                                                            Text(
                                                                text = if (hName.isNotBlank()) hName else if (cIdx == 0 && colCount == 1) "Item" else "Column ${cIdx + 1}",
                                                                fontSize = 13.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                                            )
                                                        }
                                                        innerTextField()
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("task_input_${uIdx}_$cIdx")
                                            )
                                        } else {
                                            Text(
                                                text = valText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = onDialogTextColor,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 10.dp)
                                            )
                                        }
                                    }
                                }

                                if (isEditMode) {
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(gridLineColor)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .width(100.dp)
                                            .fillMaxHeight()
                                            .padding(horizontal = 2.dp)
                                    ) {
                                        if (uIdx > 0) {
                                            IconButton(
                                                onClick = {
                                                    val currentUnchecked = uncheckedItems.toMutableList()
                                                    val temp = currentUnchecked[uIdx]
                                                    currentUnchecked[uIdx] = currentUnchecked[uIdx - 1]
                                                    currentUnchecked[uIdx - 1] = temp
                                                    val updatedList = currentUnchecked + checkedItems
                                                    onContentChange(serializeTableListData(TableListData(headers, updatedList)))
                                                },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("task_move_up_$uIdx")
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        if (uIdx < uncheckedItems.size - 1) {
                                            IconButton(
                                                onClick = {
                                                    val currentUnchecked = uncheckedItems.toMutableList()
                                                    val temp = currentUnchecked[uIdx]
                                                    currentUnchecked[uIdx] = currentUnchecked[uIdx + 1]
                                                    currentUnchecked[uIdx + 1] = temp
                                                    val updatedList = currentUnchecked + checkedItems
                                                    onContentChange(serializeTableListData(TableListData(headers, updatedList)))
                                                },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("task_move_down_$uIdx")
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                val currentUnchecked = uncheckedItems.toMutableList()
                                                currentUnchecked.removeAt(uIdx)
                                                val updatedList = currentUnchecked + checkedItems
                                                onContentChange(serializeTableListData(TableListData(headers, updatedList)))
                                            },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("task_delete_$uIdx")
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Row",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            if (uIdx < uncheckedItems.size - 1) {
                                HorizontalDivider(
                                    modifier = if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth(),
                                    color = gridLineColor.copy(alpha = 0.15f),
                                    thickness = 1.dp
                                )
                            }
                        }
                    }

                    // Table Footer Row (Fixed, Non-Editable Summary)
                    HorizontalDivider(
                        modifier = if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth(),
                        color = gridLineColor,
                        thickness = 1.dp
                    )
                    Row(
                        modifier = (if (uncheckedTableWidth != null) Modifier.width(uncheckedTableWidth) else Modifier.fillMaxWidth())
                            .height(IntrinsicSize.Min)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Σ",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                        (0 until colCount).forEach { cIdx ->
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(gridLineColor)
                            )
                            val colValues = uncheckedItems.map { it.values.getOrElse(cIdx) { "" } }
                            val footerVal = calculateColumnFooter(colValues)
                            Box(
                                modifier = if (uncheckedTableWidth == null) {
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(horizontal = 8.dp, vertical = 8.dp)
                                } else {
                                    Modifier
                                        .width(dynamicColWidth)
                                        .fillMaxHeight()
                                        .padding(horizontal = 8.dp, vertical = 8.dp)
                                },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = footerVal,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("table_footer_col_$cIdx")
                                )
                            }
                        }
                        if (isEditMode) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(gridLineColor)
                            )
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }

            if (uncheckedTableWidth == null) {
                tableContent()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                ) {
                    tableContent()
                }
            }
        }

        // --- Checked Items Accordion (Collapsed by Default) ---
        if (checkedItems.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        RoundedCornerShape(12.dp)
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        RoundedCornerShape(12.dp)
                    )
            ) {
                // Header Dropdown Toggle Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCheckedDropdown = !showCheckedDropdown }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showCheckedDropdown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (showCheckedDropdown) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Checked Items (${checkedItems.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = onDialogTextColor.copy(alpha = 0.85f)
                        )
                    }

                    Text(
                        text = if (showCheckedDropdown) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                AnimatedVisibility(visible = showCheckedDropdown) {
                    val checkedScrollState = rememberScrollState()
                    val checkedActionsWidth = if (isEditMode) 48.dp else 0.dp
                    val checkedFixedTotalWidth = fixedCheckboxWidth + checkedActionsWidth
                    val checkedDynamicColWidth = if (colCount > 0) {
                        val remaining = availableWidth - checkedFixedTotalWidth
                        val calculated = remaining / colCount
                        maxOf(minColWidth, calculated)
                    } else minColWidth
                    val checkedTotalTableWidth = checkedFixedTotalWidth + (checkedDynamicColWidth * colCount)
                    val checkedNeedsHScroll = checkedTotalTableWidth > availableWidth
                    val checkedTableWidth = if (checkedNeedsHScroll) checkedTotalTableWidth else null

                    val checkedTableContent: @Composable () -> Unit = {
                        Column(
                            modifier = if (checkedTableWidth != null) Modifier.width(checkedTableWidth) else Modifier.fillMaxWidth()
                        ) {
                            checkedItems.forEachIndexed { cIdx, item ->
                                Row(
                                    modifier = (if (checkedTableWidth != null) Modifier.width(checkedTableWidth) else Modifier.fillMaxWidth())
                                        .height(IntrinsicSize.Min)
                                        .testTag("checked_item_row_$cIdx"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Uncheck box -> moves to TOP of unchecked list!
                                    Box(
                                        modifier = Modifier
                                            .width(44.dp)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Checkbox(
                                            checked = true,
                                            onCheckedChange = {
                                                val currentChecked = checkedItems.toMutableList()
                                                val uncompletedItem = currentChecked.removeAt(cIdx)
                                                val currentUnchecked = uncheckedItems.toMutableList()
                                                currentUnchecked.add(0, uncompletedItem.copy(isChecked = false))
                                                val updatedList = currentUnchecked + currentChecked
                                                val newContent = serializeTableListData(TableListData(headers, updatedList))
                                                onContentChange(newContent)
                                                if (!isEditMode) onSaveRequested?.invoke()
                                            },
                                            modifier = Modifier.testTag("checked_checkbox_$cIdx")
                                        )
                                    }

                                    (0 until colCount).forEach { colIdx ->
                                        val valText = item.values.getOrElse(colIdx) { "" }
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .fillMaxHeight()
                                                .background(gridLineColor)
                                        )
                                        Box(
                                            modifier = if (checkedTableWidth == null) {
                                                Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                            } else {
                                                Modifier
                                                    .width(checkedDynamicColWidth)
                                                    .fillMaxHeight()
                                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                            },
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(
                                                text = valText,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    textDecoration = TextDecoration.LineThrough
                                                ),
                                                color = onDialogTextColor.copy(alpha = 0.5f),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    if (isEditMode) {
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .fillMaxHeight()
                                                .background(gridLineColor)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .width(48.dp)
                                                .fillMaxHeight(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    val currentChecked = checkedItems.toMutableList()
                                                    currentChecked.removeAt(cIdx)
                                                    val updatedList = uncheckedItems + currentChecked
                                                    onContentChange(serializeTableListData(TableListData(headers, updatedList)))
                                                },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("checked_delete_$cIdx")
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete Checked Item",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                if (cIdx < checkedItems.size - 1) {
                                    HorizontalDivider(
                                        modifier = if (checkedTableWidth != null) Modifier.width(checkedTableWidth) else Modifier.fillMaxWidth(),
                                        color = gridLineColor.copy(alpha = 0.12f),
                                        thickness = 1.dp
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            color = gridLineColor,
                            thickness = 1.dp
                        )
                        if (checkedTableWidth == null) {
                            checkedTableContent()
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(checkedScrollState)
                            ) {
                                checkedTableContent()
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Column Header Management Dialog ---
    if (showColumnConfigDialog) {
        AlertDialog(
            onDismissRequest = { showColumnConfigDialog = false },
            title = {
                Text("Manage Columns", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Rename, reorder, or remove table columns:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    headers.forEachIndexed { hIdx, hName ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = hName,
                                    onValueChange = { newHName ->
                                        val newHeaders = headers.toMutableList()
                                        newHeaders[hIdx] = newHName
                                        onContentChange(serializeTableListData(TableListData(newHeaders, tableData.items)))
                                    },
                                    label = { Text("Column ${hIdx + 1}") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                if (hIdx > 0) {
                                    IconButton(
                                        onClick = {
                                            val newHeaders = headers.toMutableList()
                                            val tempH = newHeaders[hIdx]
                                            newHeaders[hIdx] = newHeaders[hIdx - 1]
                                            newHeaders[hIdx - 1] = tempH

                                            val newItems = tableData.items.map { item ->
                                                val vals = item.values.toMutableList()
                                                if (hIdx < vals.size) {
                                                    val tempV = vals[hIdx]
                                                    vals[hIdx] = vals[hIdx - 1]
                                                    vals[hIdx - 1] = tempV
                                                }
                                                item.copy(values = vals)
                                            }
                                            onContentChange(serializeTableListData(TableListData(newHeaders, newItems)))
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Move Left")
                                    }
                                }

                                if (hIdx < headers.size - 1) {
                                    IconButton(
                                        onClick = {
                                            val newHeaders = headers.toMutableList()
                                            val tempH = newHeaders[hIdx]
                                            newHeaders[hIdx] = newHeaders[hIdx + 1]
                                            newHeaders[hIdx + 1] = tempH

                                            val newItems = tableData.items.map { item ->
                                                val vals = item.values.toMutableList()
                                                if (hIdx + 1 < vals.size) {
                                                    val tempV = vals[hIdx]
                                                    vals[hIdx] = vals[hIdx + 1]
                                                    vals[hIdx + 1] = tempV
                                                }
                                                item.copy(values = vals)
                                            }
                                            onContentChange(serializeTableListData(TableListData(newHeaders, newItems)))
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Move Right")
                                    }
                                }

                                if (headers.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val newHeaders = headers.toMutableList()
                                            newHeaders.removeAt(hIdx)

                                            val newItems = tableData.items.map { item ->
                                                val vals = item.values.toMutableList()
                                                if (hIdx < vals.size) vals.removeAt(hIdx)
                                                item.copy(values = vals)
                                            }
                                            onContentChange(serializeTableListData(TableListData(newHeaders, newItems)))
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete Column",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            val newHeaders = headers.toMutableList()
                            val nextName = if (newHeaders.isEmpty()) "Item" else "Column ${newHeaders.size + 1}"
                            newHeaders.add(nextName)
                            val newItems = tableData.items.map { item ->
                                val newVals = item.values.toMutableList()
                                newVals.add("")
                                item.copy(values = newVals)
                            }
                            onContentChange(serializeTableListData(TableListData(newHeaders, newItems)))
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add New Column")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showColumnConfigDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
    }
}

@Composable
fun CardTablePreview(
    note: com.example.data.Note,
    onUpdate: (com.example.data.Note) -> Unit,
    noteText: Color,
    maxDisplayRows: Int = 3
) {
    val tableData = remember(note.content) { parseTableListData(note.content) }
    val uncheckedItems = remember(tableData) { tableData.items.filter { !it.isChecked } }
    val checkedItems = remember(tableData) { tableData.items.filter { it.isChecked } }
    val displayItems = remember(tableData) { uncheckedItems + checkedItems }

    val headers = tableData.headers
    val maxValCols = displayItems.maxOfOrNull { it.values.size } ?: 1
    val colCount = maxOf(headers.size, maxValCols)
    val isSingleCol = colCount <= 1
    val miniColWidth = 110.dp

    val hScrollState = rememberScrollState()
    val vScrollState = rememberScrollState()

    // Prevent scrolling inside list preview from scrolling the parent notes list when boundaries are hit
    val stopNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return Offset(0f, available.y)
            }
        }
    }

    val cardGridLineColor = noteText.copy(alpha = 0.16f)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        val fixedCheckboxWidth = 28.dp
        val minColWidth = 85.dp

        val dynamicColWidth = if (colCount > 0) {
            val remaining = availableWidth - fixedCheckboxWidth
            val calculated = remaining / colCount
            maxOf(minColWidth, calculated)
        } else minColWidth

        val totalTableWidth = fixedCheckboxWidth + (dynamicColWidth * colCount)
        val needsHScroll = totalTableWidth > availableWidth
        val previewTableWidth = if (needsHScroll) totalTableWidth else null

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, cardGridLineColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            val previewContent: @Composable () -> Unit = {
                Column(
                    modifier = if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth()
                ) {
                    // Header Row
                    if (headers.isNotEmpty()) {
                        Row(
                            modifier = (if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth())
                                .height(IntrinsicSize.Min)
                                .background(noteText.copy(alpha = 0.08f)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {}

                            headers.forEachIndexed { hIdx, hText ->
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(cardGridLineColor)
                                )
                                Box(
                                    modifier = if (previewTableWidth == null) {
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .padding(horizontal = 4.dp, vertical = 4.dp)
                                    } else {
                                        Modifier
                                            .width(dynamicColWidth)
                                            .fillMaxHeight()
                                            .padding(horizontal = 4.dp, vertical = 4.dp)
                                    },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = if (hText.isBlank()) {
                                            if (hIdx == 0 && headers.size == 1) "Item" else "Col ${hIdx + 1}"
                                        } else hText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = noteText.copy(alpha = 0.9f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth(),
                            color = cardGridLineColor,
                            thickness = 1.dp
                        )
                    }

                    // Row Items (Scrollable vertically with nested scroll isolated)
                    if (displayItems.isEmpty()) {
                        Text(
                            text = "Empty list",
                            style = MaterialTheme.typography.labelSmall,
                            color = noteText.copy(alpha = 0.5f),
                            modifier = Modifier.padding(8.dp)
                        )
                    } else {
                        Box(
                            modifier = (if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth())
                                .heightIn(max = 160.dp)
                                .nestedScroll(stopNestedScrollConnection)
                                .verticalScroll(vScrollState)
                        ) {
                            Column(modifier = if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth()) {
                                displayItems.forEachIndexed { idx, item ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = (if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth())
                                            .height(IntrinsicSize.Min)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(28.dp)
                                                .fillMaxHeight(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (item.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = noteText.copy(alpha = 0.7f),
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable {
                                                        val newUnchecked = uncheckedItems.toMutableList()
                                                        val newChecked = checkedItems.toMutableList()
                                                        if (item.isChecked) {
                                                            newChecked.remove(item)
                                                            newUnchecked.add(0, item.copy(isChecked = false))
                                                        } else {
                                                            newUnchecked.remove(item)
                                                            newChecked.add(item.copy(isChecked = true))
                                                        }
                                                        val newContent = serializeTableListData(
                                                            TableListData(headers, newUnchecked + newChecked)
                                                        )
                                                        onUpdate(note.copy(content = newContent))
                                                    }
                                            )
                                        }

                                        (0 until colCount).forEach { cIdx ->
                                            val valText = item.values.getOrElse(cIdx) { "" }
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .fillMaxHeight()
                                                    .background(cardGridLineColor)
                                            )
                                            Box(
                                                modifier = if (previewTableWidth == null) {
                                                    Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                                } else {
                                                    Modifier
                                                        .width(dynamicColWidth)
                                                        .fillMaxHeight()
                                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                                },
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Text(
                                                    text = valText,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        textDecoration = if (item.isChecked) TextDecoration.LineThrough else null
                                                    ),
                                                    color = noteText.copy(alpha = if (item.isChecked) 0.5f else 0.85f),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    if (idx < displayItems.size - 1) {
                                        HorizontalDivider(
                                            modifier = if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth(),
                                            color = cardGridLineColor.copy(alpha = 0.12f),
                                            thickness = 1.dp
                                        )
                                    }
                                }
                            }
                        }

                        // Footer Row in CardTablePreview
                        HorizontalDivider(
                            modifier = if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth(),
                            color = cardGridLineColor,
                            thickness = 1.dp
                        )
                        Row(
                            modifier = (if (previewTableWidth != null) Modifier.width(previewTableWidth) else Modifier.fillMaxWidth())
                                .height(IntrinsicSize.Min)
                                .background(noteText.copy(alpha = 0.08f)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Σ",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = noteText.copy(alpha = 0.7f)
                                )
                            }
                            (0 until colCount).forEach { cIdx ->
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(cardGridLineColor)
                                )
                                val colValues = uncheckedItems.map { it.values.getOrElse(cIdx) { "" } }
                                val footerVal = calculateColumnFooter(colValues)
                                Box(
                                    modifier = if (previewTableWidth == null) {
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .padding(horizontal = 4.dp, vertical = 3.dp)
                                    } else {
                                        Modifier
                                            .width(dynamicColWidth)
                                            .fillMaxHeight()
                                            .padding(horizontal = 4.dp, vertical = 3.dp)
                                    },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = footerVal,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = noteText.copy(alpha = 0.95f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (previewTableWidth == null) {
                previewContent()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(hScrollState)
                ) {
                    previewContent()
                }
            }
        }
    }
}
