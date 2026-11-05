package org.rjpd.msdc

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

internal const val PREF_CUSTOM_UPLOAD_URL = "custom_upload_url"
internal const val PREF_CUSTOM_UPLOAD_TOKEN = "custom_upload_token"
internal const val PREF_CLOUD_STORAGE_TREE_URI = "cloud_storage_tree_uri"
internal const val PREF_SYNCED_DATASET_NAMES = "synced_dataset_names"

private const val TAG = "CloudSyncManager"
private const val UPLOAD_CACHE_DIR = "upload_cache"
private const val ZIP_MIME_TYPE = "application/zip"

// Secrets and sync bookkeeping never go into a dataset's metadata.json.
private val PREFS_EXCLUDED_FROM_METADATA = setOf(PREF_CUSTOM_UPLOAD_TOKEN, PREF_SYNCED_DATASET_NAMES)

fun preferencesForMetadata(all: Map<String, *>): MutableMap<String, Any?> =
    all.filterKeys { it !in PREFS_EXCLUDED_FROM_METADATA }.toMutableMap()

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

    suspend fun syncDataset(context: Context, dataset: DatasetSummary): SyncResult = withContext(Dispatchers.IO) {
        ensureActive()
        val result = when (getCloudSyncMode(context)) {
            CloudSyncMode.HTTP_SERVER -> syncViaHttp(context, dataset)
            CloudSyncMode.SAF_STORAGE -> syncViaSaf(context, dataset)
            CloudSyncMode.NOT_CONFIGURED -> SyncResult(
                success = false,
                message = "No cloud destination configured. Set a server URL or choose a folder."
            )
        }
        ensureActive()
        result
    }

    private suspend fun syncViaHttp(context: Context, dataset: DatasetSummary): SyncResult {
        val uploadUrlStr = getCustomUploadUrl(context)
        if (uploadUrlStr.isEmpty()) {
            return SyncResult(false, "Server upload URL is empty.")
        }

        val uploadToken = getCustomUploadToken(context)
        val (zipFile, isTempZip) = obtainDatasetZip(context, dataset)
            ?: return SyncResult(false, "Failed to prepare dataset zip archive.")

        try {
            val result = uploadDatasetArchive(uploadUrlStr, uploadToken, dataset, zipFile)
            currentCoroutineContext().ensureActive()
            if (result.success) markDatasetAsSynced(context, dataset.name)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).e(e, "HTTP upload failed for: ${dataset.name}")
            return SyncResult(false, "Upload error: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            if (isTempZip) {
                deleteTemporaryZip(zipFile)
            }
        }
    }

    private suspend fun syncViaSaf(context: Context, dataset: DatasetSummary): SyncResult {
        val treeUri = getSafStorageUri(context)
            ?: return SyncResult(false, "No destination folder configured.")

        val (zipFile, isTempZip) = obtainDatasetZip(context, dataset)
            ?: return SyncResult(false, "Failed to prepare dataset zip archive.")

        val cancellationContext = currentCoroutineContext()
        var incompleteFile: DocumentFile? = null
        try {
            cancellationContext.ensureActive()
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
            if (existingFile == null) incompleteFile = destFileDoc

            cancellationContext.ensureActive()
            context.contentResolver.openOutputStream(destFileDoc.uri, "wt")?.use { output ->
                incompleteFile = destFileDoc
                zipFile.inputStream().use { input ->
                    copySyncStream(input, output, cancellationContext)
                }
            } ?: return SyncResult(false, "Could not open destination output stream.")

            cancellationContext.ensureActive()
            incompleteFile = null
            markDatasetAsSynced(context, dataset.name)
            return SyncResult(true, "Dataset exported to destination folder successfully.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cancellationContext.ensureActive()
            Timber.tag(TAG).e(e, "SAF sync failed for: ${dataset.name}")
            return SyncResult(false, "Storage error: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            try {
                incompleteFile?.delete()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not remove incomplete destination archive")
            }
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

    private suspend fun obtainDatasetZip(context: Context, dataset: DatasetSummary): Pair<File, Boolean>? {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        val file = dataset.path
        if (!file.exists()) return null

        val exportFilename = getExportFilename(dataset)
        val isArchive = file.isFile && file.extension.equals("zip", ignoreCase = true)
        if (isArchive && exportFilename == file.name) return Pair(file, false)
        if (!isArchive && !file.isDirectory) return null

        val cacheZip = File(context.cacheDir, "$UPLOAD_CACHE_DIR/$exportFilename")
        var complete = false
        try {
            cacheZip.parentFile?.mkdirs()
            if (isArchive) {
                file.inputStream().use { input ->
                    FileOutputStream(cacheZip).use { copySyncStream(input, it, cancellationContext) }
                }
            } else {
                writeSyncZip(file, cacheZip, cancellationContext)
            }
            cancellationContext.ensureActive()
            complete = true
            return Pair(cacheZip, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cancellationContext.ensureActive()
            Timber.tag(TAG).e(e, "Error preparing dataset archive: ${file.name}")
            return if (isArchive) Pair(file, false) else null
        } finally {
            if (!complete) deleteTemporaryZip(cacheZip)
        }
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

}
