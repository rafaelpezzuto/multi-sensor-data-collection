package org.rjpd.msdc

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

internal const val PREF_CUSTOM_UPLOAD_URL = "custom_upload_url"
internal const val PREF_CUSTOM_UPLOAD_TOKEN = "custom_upload_token"
internal const val PREF_CLOUD_STORAGE_TREE_URI = "cloud_storage_tree_uri"
internal const val PREF_SYNCED_DATASET_NAMES = "synced_dataset_names"

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 300_000
private const val BUFFER_SIZE = 8192
private const val LINE_FEED = "\r\n"
private const val TAG = "CloudSyncManager"
private const val UPLOAD_CACHE_DIR = "upload_cache"
private const val ZIP_MIME_TYPE = "application/zip"

enum class CloudSyncMode {
    HTTP_SERVER,
    SAF_STORAGE,
    NOT_CONFIGURED
}

data class SyncResult(
    val success: Boolean,
    val message: String,
    val statusCode: Int? = null
)

data class SyncProgressState(
    val isSyncing: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val currentDatasetName: String = "",
    val lastCompletedDataset: String? = null,
    val lastResult: SyncResult? = null,
    val isFinished: Boolean = false,
    val successCount: Int = 0,
    val failureCount: Int = 0
)

object CloudSyncManager {

    private val _syncProgress = MutableStateFlow(SyncProgressState())
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    fun startProgress(total: Int, initialDatasetName: String = "") {
        _syncProgress.value = SyncProgressState(
            isSyncing = true,
            current = if (total > 0) 1 else 0,
            total = total,
            currentDatasetName = initialDatasetName,
            isFinished = false
        )
    }

    fun updateProgress(current: Int, total: Int, datasetName: String) {
        _syncProgress.value = _syncProgress.value.copy(
            isSyncing = true,
            current = current,
            total = total,
            currentDatasetName = datasetName
        )
    }

    fun recordDatasetCompleted(datasetName: String, result: SyncResult) {
        val currentVal = _syncProgress.value
        val newSuccess = if (result.success) currentVal.successCount + 1 else currentVal.successCount
        val newFailure = if (!result.success) currentVal.failureCount + 1 else currentVal.failureCount
        _syncProgress.value = currentVal.copy(
            lastCompletedDataset = datasetName,
            lastResult = result,
            successCount = newSuccess,
            failureCount = newFailure
        )
    }

    fun finishSync(successCount: Int, failureCount: Int) {
        val current = _syncProgress.value
        _syncProgress.value = current.copy(
            isSyncing = false,
            current = current.total,
            currentDatasetName = "",
            isFinished = true,
            successCount = successCount,
            failureCount = failureCount
        )
    }

    fun resetProgress() {
        _syncProgress.value = SyncProgressState()
    }

    fun getCloudSyncMode(context: Context): CloudSyncMode {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val customUrl = sharedPreferences.getString(PREF_CUSTOM_UPLOAD_URL, null)?.trim()
        if (!customUrl.isNullOrEmpty()) {
            return CloudSyncMode.HTTP_SERVER
        }

        val treeUriStr = sharedPreferences.getString(PREF_CLOUD_STORAGE_TREE_URI, null)?.trim()
        if (!treeUriStr.isNullOrEmpty()) {
            return CloudSyncMode.SAF_STORAGE
        }

        return CloudSyncMode.NOT_CONFIGURED
    }

    fun getCustomUploadUrl(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_CUSTOM_UPLOAD_URL, "")?.trim() ?: ""
    }

    fun getCustomUploadToken(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_CUSTOM_UPLOAD_TOKEN, "")?.trim() ?: ""
    }

    fun getSafStorageUri(context: Context): Uri? {
        val uriStr = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_CLOUD_STORAGE_TREE_URI, null)?.trim() ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (_: Exception) {
            null
        }
    }

    fun setSafStorageUri(context: Context, uri: Uri?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val oldUri = sharedPreferences.getString(PREF_CLOUD_STORAGE_TREE_URI, null)
        val editor = sharedPreferences.edit()
        if (uri != null) {
            editor.putString(PREF_CLOUD_STORAGE_TREE_URI, uri.toString())
        } else {
            editor.remove(PREF_CLOUD_STORAGE_TREE_URI)
        }
        if (oldUri != uri?.toString()) {
            editor.remove(PREF_SYNCED_DATASET_NAMES)
        }
        editor.apply()
    }

    fun clearSyncedDatasetNames(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(PREF_SYNCED_DATASET_NAMES)
            .apply()
    }

    fun isDatasetSynced(context: Context, datasetName: String): Boolean {
        if (getCloudSyncMode(context) == CloudSyncMode.NOT_CONFIGURED) {
            return false
        }
        val syncedSet = PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_SYNCED_DATASET_NAMES, emptySet()) ?: emptySet()
        return syncedSet.contains(datasetName)
    }

    fun markDatasetAsSynced(context: Context, datasetName: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val currentSynced = sharedPreferences.getStringSet(PREF_SYNCED_DATASET_NAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentSynced.add(datasetName)
        sharedPreferences.edit().putStringSet(PREF_SYNCED_DATASET_NAMES, currentSynced).apply()
    }

    suspend fun syncDataset(context: Context, dataset: DatasetSummary): SyncResult {
        return when (getCloudSyncMode(context)) {
            CloudSyncMode.HTTP_SERVER -> syncViaHttp(context, dataset)
            CloudSyncMode.SAF_STORAGE -> syncViaSaf(context, dataset)
            CloudSyncMode.NOT_CONFIGURED -> SyncResult(
                success = false,
                message = "No cloud destination configured. Set a server URL or choose a folder."
            )
        }
    }

    private fun syncViaHttp(context: Context, dataset: DatasetSummary): SyncResult {
        val uploadUrlStr = getCustomUploadUrl(context)
        if (uploadUrlStr.isEmpty()) {
            return SyncResult(false, "Server upload URL is empty.")
        }

        val uploadToken = getCustomUploadToken(context)
        val (zipFile, isTempZip) = obtainDatasetZip(context, dataset)
            ?: return SyncResult(false, "Failed to prepare dataset zip archive.")

        var connection: HttpURLConnection? = null
        try {
            val url = URL(uploadUrlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }

            if (uploadToken.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $uploadToken")
            }

            val boundary = "===Boundary_${System.currentTimeMillis()}==="
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val prefix = buildMultipartPrefix(boundary, dataset, zipFile.name)
            val suffix = ("$LINE_FEED--$boundary--$LINE_FEED").toByteArray(Charsets.UTF_8)
            val totalContentLength = prefix.size + zipFile.length() + suffix.size

            if (totalContentLength <= Int.MAX_VALUE) {
                connection.setFixedLengthStreamingMode(totalContentLength)
            } else {
                connection.setChunkedStreamingMode(0)
            }

            connection.outputStream.use { output ->
                output.write(prefix)
                zipFile.inputStream().use { input ->
                    copyStreamWithBuffer(input, output)
                }
                output.write(suffix)
                output.flush()
            }

            val responseCode = connection.responseCode
            return if (responseCode in 200..299) {
                markDatasetAsSynced(context, dataset.name)
                SyncResult(true, "Dataset uploaded successfully.", responseCode)
            } else {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
                val detail = if (errorBody.isNotEmpty()) ": $errorBody" else ""
                SyncResult(false, "Server returned HTTP $responseCode$detail", responseCode)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "HTTP upload failed for: ${dataset.name}")
            return SyncResult(false, "Upload error: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
            if (isTempZip) {
                deleteTemporaryZip(zipFile)
            }
        }
    }

    private fun syncViaSaf(context: Context, dataset: DatasetSummary): SyncResult {
        val treeUri = getSafStorageUri(context)
            ?: return SyncResult(false, "No destination folder configured.")

        val (zipFile, isTempZip) = obtainDatasetZip(context, dataset)
            ?: return SyncResult(false, "Failed to prepare dataset zip archive.")

        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return SyncResult(false, "Cannot access selected folder.")

            if (!rootDoc.canWrite()) {
                return SyncResult(false, "Selected folder is not writable.")
            }

            val targetDirDoc = resolveTargetDirectory(rootDoc, dataset.directory, dataset.subdirectory, createIfMissing = true)
                ?: return SyncResult(false, "Could not create target directory in folder.")

            val existingFile = targetDirDoc.listFiles().firstOrNull { it.name?.equals(zipFile.name, ignoreCase = true) == true }
            val destFileDoc = existingFile
                ?: targetDirDoc.createFile(ZIP_MIME_TYPE, zipFile.name)
                ?: return SyncResult(false, "Could not create file in destination folder.")

            context.contentResolver.openOutputStream(destFileDoc.uri, "wt")?.use { output ->
                zipFile.inputStream().use { input ->
                    copyStreamWithBuffer(input, output)
                }
            } ?: return SyncResult(false, "Could not open destination output stream.")

            markDatasetAsSynced(context, dataset.name)
            return SyncResult(true, "Dataset exported to destination folder successfully.")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "SAF sync failed for: ${dataset.name}")
            return SyncResult(false, "Storage error: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            if (isTempZip) {
                deleteTemporaryZip(zipFile)
            }
        }
    }

    fun isCollectionPresentInSaf(context: Context, dataset: DatasetSummary, treeUri: Uri): Boolean {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            if (!rootDoc.exists()) return false

            val baseName = dataset.path.nameWithoutExtension
            val expectedFilename = getExportFilename(dataset)

            fun matches(doc: DocumentFile): Boolean {
                if (!doc.isFile || !doc.exists()) return false
                val name = doc.name ?: return false
                val nameMatches = name.equals(expectedFilename, ignoreCase = true) ||
                        name.equals("$baseName.zip", ignoreCase = true) ||
                        name.equals(dataset.path.name, ignoreCase = true) ||
                        (name.startsWith(baseName, ignoreCase = true) && name.endsWith(".zip", ignoreCase = true))

                if (!nameMatches) return false

                return try {
                    context.contentResolver.openInputStream(doc.uri)?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
            }

            val targetDirDoc = resolveTargetDirectory(rootDoc, dataset.directory, dataset.subdirectory, createIfMissing = false)

            if (targetDirDoc != null && targetDirDoc.exists()) {
                val targetFiles = targetDirDoc.listFiles()
                if (targetFiles.any { matches(it) }) return true

                val foundInTargetSub = targetFiles.filter { it.isDirectory && it.exists() }.any { subDoc ->
                    subDoc.listFiles().any { matches(it) }
                }
                if (foundInTargetSub) return true
            }

            val rootFiles = rootDoc.listFiles()
            if (rootFiles.any { matches(it) }) return true

            val foundInRootSub = rootFiles.filter { it.isDirectory && it.exists() && it != targetDirDoc }.any { subDoc ->
                val subFiles = subDoc.listFiles()
                if (subFiles.any { matches(it) }) return true
                subFiles.filter { it.isDirectory && it.exists() }.any { nestedDoc ->
                    nestedDoc.listFiles().any { matches(it) }
                }
            }

            foundInRootSub
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error checking cloud presence for: ${dataset.name}")
            false
        }
    }

    private fun resolveTargetDirectory(
        rootDoc: DocumentFile,
        directory: String,
        subdirectory: String,
        createIfMissing: Boolean
    ): DocumentFile? {
        val cleanDir = directory.trim()
        val cleanSub = subdirectory.trim()

        val normalizedDir = if (cleanDir.isNotEmpty() && !cleanDir.equals("Default", ignoreCase = true)) {
            generateInstanceName(cleanDir)
        } else {
            null
        }

        val normalizedSub = if (cleanSub.isNotEmpty() && !cleanSub.equals("Default", ignoreCase = true)) {
            generateInstanceName(cleanSub)
        } else {
            null
        }

        val finalSubName = if (normalizedSub != null && (normalizedDir == null || !normalizedSub.equals(normalizedDir, ignoreCase = true))) {
            normalizedSub
        } else {
            null
        }

        var currentDoc: DocumentFile = rootDoc

        if (normalizedDir != null) {
            currentDoc = if (createIfMissing) {
                currentDoc.findFile(normalizedDir)
                    ?: currentDoc.findFile(cleanDir)
                    ?: currentDoc.createDirectory(normalizedDir)
                    ?: return null
            } else {
                currentDoc.listFiles().firstOrNull {
                    it.isDirectory && (it.name.equals(normalizedDir, ignoreCase = true) || it.name.equals(cleanDir, ignoreCase = true))
                } ?: return null
            }
        }

        if (finalSubName != null) {
            currentDoc = if (createIfMissing) {
                currentDoc.findFile(finalSubName)
                    ?: currentDoc.findFile(cleanSub)
                    ?: currentDoc.createDirectory(finalSubName)
                    ?: return null
            } else {
                currentDoc.listFiles().firstOrNull {
                    it.isDirectory && (it.name.equals(finalSubName, ignoreCase = true) || it.name.equals(cleanSub, ignoreCase = true))
                } ?: return null
            }
        }

        return currentDoc
    }

    private fun obtainDatasetZip(context: Context, dataset: DatasetSummary): Pair<File, Boolean>? {
        val file = dataset.path
        if (!file.exists()) return null

        val exportFilename = getExportFilename(dataset)

        if (file.isFile && file.extension.lowercase() == "zip") {
            if (exportFilename != file.name) {
                return try {
                    val cacheZip = File(context.cacheDir, "$UPLOAD_CACHE_DIR/$exportFilename")
                    cacheZip.parentFile?.mkdirs()
                    file.copyTo(cacheZip, overwrite = true)
                    Pair(cacheZip, true)
                } catch (_: Exception) {
                    Pair(file, false)
                }
            }
            return Pair(file, false)
        }

        if (file.isDirectory) {
            return try {
                val cacheZip = File(context.cacheDir, "$UPLOAD_CACHE_DIR/$exportFilename")
                cacheZip.parentFile?.mkdirs()

                ZipOutputStream(FileOutputStream(cacheZip)).use { zipOut ->
                    file.walkTopDown().filter { it.isFile }.forEach { child ->
                        val relativePath = child.relativeTo(file).path
                        zipOut.putNextEntry(ZipEntry(relativePath))
                        child.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
                Pair(cacheZip, true)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error zipping dataset directory: ${file.name}")
                null
            }
        }

        return null
    }

    private fun deleteTemporaryZip(tempFile: File) {
        try {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not delete temporary zip: ${tempFile.absolutePath}")
        }
    }

    private fun buildMultipartPrefix(boundary: String, dataset: DatasetSummary, filename: String): ByteArray {
        val sb = StringBuilder()

        fun addFormField(name: String, value: String) {
            sb.append("--").append(boundary).append(LINE_FEED)
            sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(LINE_FEED)
            sb.append(LINE_FEED)
            sb.append(value).append(LINE_FEED)
        }

        addFormField("dataset_name", dataset.name)
        addFormField("directory", dataset.directory)
        addFormField("subdirectory", dataset.subdirectory)

        sb.append("--").append(boundary).append(LINE_FEED)
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(LINE_FEED)
        sb.append("Content-Type: $ZIP_MIME_TYPE").append(LINE_FEED)
        sb.append(LINE_FEED)

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun copyStreamWithBuffer(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
    }
}
