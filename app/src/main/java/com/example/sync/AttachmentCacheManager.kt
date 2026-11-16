package com.example.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SyncAttachment(
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val storagePath: String,
    val downloadUrl: String
)

object AttachmentCacheManager {
    private const val TAG = "AttachmentCache"

    fun sanitizeFileName(rawName: String, fallbackExt: String = "bin"): String {
        val last = rawName.substringAfterLast('/').substringBefore('?').trim()
        val cleaned = last.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            cleaned
        } else {
            "att_${System.currentTimeMillis()}.$fallbackExt"
        }
    }

    fun getLocalCacheFile(context: Context, noteId: String, fileName: String): File {
        val safeNoteId = noteId.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }
        val dir = File(context.cacheDir, "attachments/$safeNoteId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val safeFile = sanitizeFileName(fileName)
        return File(dir, safeFile)
    }

    fun decodeAndCacheDataUri(
        context: Context,
        noteId: String,
        fileName: String,
        dataUri: String
    ): File? {
        if (!dataUri.startsWith("data:", ignoreCase = true)) return null
        return try {
            val commaIndex = dataUri.indexOf(',')
            if (commaIndex == -1) return null
            val header = dataUri.substring(5, commaIndex).lowercase()
            val payload = dataUri.substring(commaIndex + 1)
            val ext = when {
                header.contains("image/png") -> "png"
                header.contains("image/webp") -> "webp"
                else -> "jpg"
            }
            val safeName = if (fileName.isNotBlank()) sanitizeFileName(fileName, ext) else "inline_${dataUri.hashCode().toUInt()}.$ext"
            val localFile = getLocalCacheFile(context, noteId, safeName)
            if (localFile.exists() && localFile.length() > 0) {
                return localFile
            }
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            FileOutputStream(localFile).use { output ->
                output.write(bytes)
            }
            localFile
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode data URI for $noteId: ${e.message}", e)
            null
        }
    }

    suspend fun downloadAttachmentOnDemand(
        context: Context,
        noteId: String,
        fileName: String,
        downloadUrl: String
    ): File? = withContext(Dispatchers.IO) {
        if (downloadUrl.startsWith("data:", ignoreCase = true)) {
            return@withContext decodeAndCacheDataUri(context, noteId, fileName, downloadUrl)
        }
        val localFile = getLocalCacheFile(context, noteId, fileName)
        if (localFile.exists() && localFile.length() > 0) {
            Log.d(TAG, "Attachment restoration: Cache hit for $fileName (noteId: $noteId)")
            return@withContext localFile
        }

        Log.d(TAG, "Upload/Download starting: Download start for file $fileName via $downloadUrl")
        try {
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Upload/Download success: Download successful for $fileName stored locally")
                localFile
            } else {
                Log.e(TAG, "Upload/Download failure: Server returned response code ${connection.responseCode} for $fileName")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload/Download failure: Failed to download $fileName: ${e.message}", e)
            null
        }
    }

    // JSON serialization / deserialization helpers using org.json
    fun serializeAttachments(attachments: List<SyncAttachment>): String {
        val arr = org.json.JSONArray()
        for (att in attachments) {
            val obj = org.json.JSONObject()
            obj.put("fileName", att.fileName)
            obj.put("mimeType", att.mimeType)
            obj.put("fileSize", att.fileSize)
            obj.put("storagePath", att.storagePath)
            obj.put("downloadUrl", att.downloadUrl)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializeAttachments(jsonStr: String?): List<SyncAttachment> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        val list = mutableListOf<SyncAttachment>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SyncAttachment(
                        fileName = obj.optString("fileName", ""),
                        mimeType = obj.optString("mimeType", "application/octet-stream"),
                        fileSize = obj.optLong("fileSize", 0L),
                        storagePath = obj.optString("storagePath", ""),
                        downloadUrl = obj.optString("downloadUrl", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun listToMapList(attachments: List<SyncAttachment>): List<Map<String, Any>> {
        return attachments.map { att ->
            mapOf(
                "fileName" to att.fileName,
                "mimeType" to att.mimeType,
                "fileSize" to att.fileSize,
                "storagePath" to att.storagePath,
                "downloadUrl" to att.downloadUrl
            )
        }
    }

    fun mapListToList(maps: List<Any>?): List<SyncAttachment> {
        if (maps == null) return emptyList()
        val list = mutableListOf<SyncAttachment>()
        for (item in maps) {
            if (item is Map<*, *>) {
                val fileName = (item["fileName"] as? String) ?: ""
                val mimeType = (item["mimeType"] as? String) ?: "application/octet-stream"
                val fileSize = (item["fileSize"] as? Number)?.toLong() ?: 0L
                val storagePath = (item["storagePath"] as? String) ?: ""
                val downloadUrl = (item["downloadUrl"] as? String) ?: ""
                if (fileName.isNotEmpty()) {
                    list.add(SyncAttachment(fileName, mimeType, fileSize, storagePath, downloadUrl))
                }
            }
        }
        return list
    }
}
