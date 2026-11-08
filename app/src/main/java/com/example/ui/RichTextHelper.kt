package com.example.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

data class RichTextState(
    val plainText: String,
    val boldRanges: List<IntRange>,
    val italicRanges: List<IntRange>,
    val underlineRanges: List<IntRange>
)

object RichTextHelper {

    fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        val valid = ranges.filter { it.first < it.last }.sortedBy { it.first }
        if (valid.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var cur = valid[0]
        for (i in 1 until valid.size) {
            val next = valid[i]
            if (next.first <= cur.last) {
                cur = IntRange(cur.first, maxOf(cur.last, next.last))
            } else {
                result.add(cur)
                cur = next
            }
        }
        result.add(cur)
        return result
    }

    fun toggleRangeStyle(
        start: Int,
        end: Int,
        currentRanges: List<IntRange>,
        totalLength: Int
    ): List<IntRange> {
        val s = start.coerceIn(0, totalLength)
        val e = end.coerceIn(0, totalLength)
        if (s >= e) return currentRanges

        val merged = mergeRanges(currentRanges)
        // Check if the entire selection [s, e] is already covered
        val fullyCovered = merged.any { it.first <= s && it.last >= e }

        return if (fullyCovered) {
            // Cut [s, e] out of existing ranges
            val result = mutableListOf<IntRange>()
            for (r in merged) {
                if (r.last <= s || r.first >= e) {
                    result.add(r)
                } else {
                    if (r.first < s) {
                        result.add(IntRange(r.first, s))
                    }
                    if (r.last > e) {
                        result.add(IntRange(e, r.last))
                    }
                }
            }
            mergeRanges(result)
        } else {
            // Add [s, e]
            mergeRanges(merged + listOf(IntRange(s, e)))
        }
    }

    fun updateRangesOnEdit(
        oldText: String,
        newText: String,
        ranges: List<IntRange>,
        isActive: Boolean
    ): List<IntRange> {
        if (oldText == newText) return ranges
        val merged = mergeRanges(ranges)

        var prefix = 0
        while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < (oldText.length - prefix) && suffix < (newText.length - prefix) &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }

        val editStart = prefix
        val editEnd = oldText.length - suffix
        val inserted = (newText.length - suffix) - prefix
        val deleted = editEnd - editStart
        val delta = inserted - deleted

        val updated = mutableListOf<IntRange>()

        for (r in merged) {
            when {
                r.last <= editStart -> {
                    updated.add(r)
                }
                r.first >= editEnd -> {
                    val ns = (r.first + delta).coerceIn(0, newText.length)
                    val ne = (r.last + delta).coerceIn(0, newText.length)
                    if (ns < ne) updated.add(IntRange(ns, ne))
                }
                else -> {
                    // Range intersects edit
                    val ns = when {
                        r.first < editStart -> r.first
                        isActive -> editStart
                        else -> (editStart + inserted).coerceIn(0, newText.length)
                    }
                    val ne = when {
                        r.last > editEnd -> (r.last + delta).coerceIn(0, newText.length)
                        isActive -> (editStart + inserted).coerceIn(0, newText.length)
                        else -> editStart.coerceIn(0, newText.length)
                    }
                    if (ns < ne) updated.add(IntRange(ns, ne))
                }
            }
        }

        if (isActive && inserted > 0) {
            updated.add(IntRange(editStart, editStart + inserted))
        }

        return mergeRanges(updated.filter { it.first >= 0 && it.last <= newText.length && it.first < it.last })
    }

    fun createRichVisualTransformation(
        boldSpans: List<IntRange>,
        italicSpans: List<IntRange>,
        underlineSpans: List<IntRange>
    ): VisualTransformation {
        return VisualTransformation { text ->
            val annotated = buildAnnotatedString {
                append(text.text)
                val len = text.length
                boldSpans.forEach { r ->
                    val s = r.first.coerceIn(0, len)
                    val e = r.last.coerceIn(0, len)
                    if (s < e) addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
                }
                italicSpans.forEach { r ->
                    val s = r.first.coerceIn(0, len)
                    val e = r.last.coerceIn(0, len)
                    if (s < e) addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, e)
                }
                underlineSpans.forEach { r ->
                    val s = r.first.coerceIn(0, len)
                    val e = r.last.coerceIn(0, len)
                    if (s < e) addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }

    fun serializeFormattedText(
        plainText: String,
        boldRanges: List<IntRange>,
        italicRanges: List<IntRange>,
        underlineRanges: List<IntRange>
    ): String {
        val bMerged = mergeRanges(boldRanges)
        val iMerged = mergeRanges(italicRanges)
        val uMerged = mergeRanges(underlineRanges)

        if (bMerged.isEmpty() && iMerged.isEmpty() && uMerged.isEmpty()) {
            return plainText
        }

        val sb = StringBuilder()
        var curB = false
        var curI = false
        var curU = false

        for (i in plainText.indices) {
            val inB = bMerged.any { i >= it.first && i < it.last }
            val inI = iMerged.any { i >= it.first && i < it.last }
            val inU = uMerged.any { i >= it.first && i < it.last }

            // Close tags if needed
            if (curU && !inU) { sb.append("</u>"); curU = false }
            if (curI && !inI) { sb.append("</i>"); curI = false }
            if (curB && !inB) { sb.append("</b>"); curB = false }

            // Open tags if needed
            if (!curB && inB) { sb.append("<b>"); curB = true }
            if (!curI && inI) { sb.append("<i>"); curI = true }
            if (!curU && inU) { sb.append("<u>"); curU = true }

            sb.append(plainText[i])
        }

        if (curU) sb.append("</u>")
        if (curI) sb.append("</i>")
        if (curB) sb.append("</b>")

        return sb.toString()
    }

    fun parseFormattedText(text: String): RichTextState {
        if (text.isEmpty()) {
            return RichTextState("", emptyList(), emptyList(), emptyList())
        }

        val bRanges = mutableListOf<IntRange>()
        val iRanges = mutableListOf<IntRange>()
        val uRanges = mutableListOf<IntRange>()

        val sb = StringBuilder()
        var i = 0
        val len = text.length

        val boldStack = mutableListOf<Int>()
        val italicStack = mutableListOf<Int>()
        val underlineStack = mutableListOf<Int>()

        while (i < len) {
            when {
                // HTML <b> or <strong>
                text.startsWith("<b>", i, ignoreCase = true) -> {
                    boldStack.add(sb.length)
                    i += 3
                }
                text.startsWith("</b>", i, ignoreCase = true) -> {
                    if (boldStack.isNotEmpty()) {
                        val s = boldStack.removeAt(boldStack.size - 1)
                        if (s < sb.length) bRanges.add(IntRange(s, sb.length))
                    }
                    i += 4
                }
                text.startsWith("<strong>", i, ignoreCase = true) -> {
                    boldStack.add(sb.length)
                    i += 8
                }
                text.startsWith("</strong>", i, ignoreCase = true) -> {
                    if (boldStack.isNotEmpty()) {
                        val s = boldStack.removeAt(boldStack.size - 1)
                        if (s < sb.length) bRanges.add(IntRange(s, sb.length))
                    }
                    i += 9
                }

                // HTML <i> or <em>
                text.startsWith("<i>", i, ignoreCase = true) -> {
                    italicStack.add(sb.length)
                    i += 3
                }
                text.startsWith("</i>", i, ignoreCase = true) -> {
                    if (italicStack.isNotEmpty()) {
                        val s = italicStack.removeAt(italicStack.size - 1)
                        if (s < sb.length) iRanges.add(IntRange(s, sb.length))
                    }
                    i += 4
                }
                text.startsWith("<em>", i, ignoreCase = true) -> {
                    italicStack.add(sb.length)
                    i += 4
                }
                text.startsWith("</em>", i, ignoreCase = true) -> {
                    if (italicStack.isNotEmpty()) {
                        val s = italicStack.removeAt(italicStack.size - 1)
                        if (s < sb.length) iRanges.add(IntRange(s, sb.length))
                    }
                    i += 5
                }

                // HTML <u>
                text.startsWith("<u>", i, ignoreCase = true) -> {
                    underlineStack.add(sb.length)
                    i += 3
                }
                text.startsWith("</u>", i, ignoreCase = true) -> {
                    if (underlineStack.isNotEmpty()) {
                        val s = underlineStack.removeAt(underlineStack.size - 1)
                        if (s < sb.length) uRanges.add(IntRange(s, sb.length))
                    }
                    i += 4
                }

                // Markdown **bold**
                text.startsWith("**", i) -> {
                    if (boldStack.isNotEmpty()) {
                        val s = boldStack.removeAt(boldStack.size - 1)
                        if (s < sb.length) bRanges.add(IntRange(s, sb.length))
                    } else {
                        boldStack.add(sb.length)
                    }
                    i += 2
                }

                // Markdown __underline__ or bold
                text.startsWith("__", i) -> {
                    if (underlineStack.isNotEmpty()) {
                        val s = underlineStack.removeAt(underlineStack.size - 1)
                        if (s < sb.length) uRanges.add(IntRange(s, sb.length))
                    } else {
                        underlineStack.add(sb.length)
                    }
                    i += 2
                }

                // Markdown *italic* (ensure not **)
                text.startsWith("*", i) && !text.startsWith("**", i) -> {
                    if (italicStack.isNotEmpty()) {
                        val s = italicStack.removeAt(italicStack.size - 1)
                        if (s < sb.length) iRanges.add(IntRange(s, sb.length))
                    } else {
                        italicStack.add(sb.length)
                    }
                    i += 1
                }

                else -> {
                    sb.append(text[i])
                    i++
                }
            }
        }

        // Close any dangling tags
        val totalLen = sb.length
        while (boldStack.isNotEmpty()) {
            val s = boldStack.removeAt(boldStack.size - 1)
            if (s < totalLen) bRanges.add(IntRange(s, totalLen))
        }
        while (italicStack.isNotEmpty()) {
            val s = italicStack.removeAt(italicStack.size - 1)
            if (s < totalLen) iRanges.add(IntRange(s, totalLen))
        }
        while (underlineStack.isNotEmpty()) {
            val s = underlineStack.removeAt(underlineStack.size - 1)
            if (s < totalLen) uRanges.add(IntRange(s, totalLen))
        }

        return RichTextState(
            plainText = sb.toString(),
            boldRanges = mergeRanges(bRanges),
            italicRanges = mergeRanges(iRanges),
            underlineRanges = mergeRanges(uRanges)
        )
    }
}
