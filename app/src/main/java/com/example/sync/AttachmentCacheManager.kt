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

    fun getLocalCacheFile(context: Context, noteId: String, fileName: String): File {
        val dir = File(context.cacheDir, "attachments/$noteId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, fileName)
    }

    suspend fun downloadAttachmentOnDemand(
        context: Context,
        noteId: String,
        fileName: String,
        downloadUrl: String
    ): File? = withContext(Dispatchers.IO) {
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
