package org.rjpd.msdc

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private const val TAG = "S3Sync"
private const val MIB = 1024L * 1024
private const val MAX_PARTS = 10_000
private const val STALE_UPLOAD_MS = 7L * 24 * 60 * 60 * 1000
private const val UPLOAD_RECORD_PREFIX = "mpu:"
// rclone stores a multipart object's MD5 under the same name, so both tools recognize each other's uploads.
private const val MD5_METADATA = "md5chksum"
private val CAPTURE_TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{3}")
private val MD5_ETAG = Regex("[0-9a-f]{32}(-\\d+)?")
private val FATAL_S3_CODES = setOf(
    "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "NoSuchBucket", "QuotaExceeded", "XMinioStorageFull"
)

/** Why a dataset was not uploaded. [stopRun] means every other dataset would fail the same way. */
class S3SyncException(message: String, val stopRun: Boolean = false) : Exception(message)

internal class FileDigests(val md5Base64: String, val partMd5Base64: List<String>, val partSha256: List<String>) {
    val md5Hex: String get() = Base64.getDecoder().decode(md5Base64).toHex()

    fun partMd5Hex(index: Int): String = Base64.getDecoder().decode(partMd5Base64[index]).toHex()

    /** S3 multipart ETag: MD5 of the concatenated binary part MD5s, then "-<part count>". */
    val multipartEtag: String
        get() {
            val md5 = MessageDigest.getInstance("MD5")
            partMd5Base64.forEach { md5.update(Base64.getDecoder().decode(it)) }
            return md5.digest().toHex() + "-" + partMd5Base64.size
        }

    /** Same size and a matching checksum: our md5chksum (or rclone's), a plain MD5 ETag, or our multipart ETag. */
    fun matches(remote: S3ObjectInfo, size: Long): Boolean =
        remote.size == size && (remote.md5Base64 == md5Base64 || remote.etag == md5Hex ||
                (partMd5Base64.size > 1 && remote.etag == multipartEtag))

    fun toJson(): JSONObject = JSONObject()
        .put("md5", md5Base64).put("partMd5", JSONArray(partMd5Base64)).put("partSha256", JSONArray(partSha256))

    companion object {
        fun fromJson(json: JSONObject): FileDigests {
            fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
            return FileDigests(json.getString("md5"), strings(json.getJSONArray("partMd5")), strings(json.getJSONArray("partSha256")))
        }
    }
}

/** An in-flight upload, saved after every part so an interrupted upload resumes instead of restarting. */
private data class UploadRecord(
    val key: String,
    val size: Long,
    val lastModified: Long,
    val partSize: Long,
    val startedAt: Long,
    val digests: FileDigests,
    val uploadId: String? = null,
    val completed: Map<Int, String> = emptyMap()
) {
    fun toJson(): String = JSONObject()
        .put("key", key).put("size", size).put("lastModified", lastModified).put("partSize", partSize)
        .put("startedAt", startedAt).put("digests", digests.toJson()).put("uploadId", uploadId)
        .put("completed", JSONObject(completed.mapKeys { it.key.toString() }))
        .toString()

    companion object {
        fun fromJson(text: String): UploadRecord {
            val json = JSONObject(text)
            val completed = json.getJSONObject("completed")
            return UploadRecord(
                json.getString("key"), json.getLong("size"), json.getLong("lastModified"), json.getLong("partSize"),
                json.getLong("startedAt"), FileDigests.fromJson(json.getJSONObject("digests")),
                json.optString("uploadId").takeIf { it.isNotEmpty() && it != "null" },
                completed.keys().asSequence().associate { it.toInt() to completed.getString(it) }
            )
        }
    }
}

internal data class PlannedObject(val file: File, val key: String, val relativePath: String)

/**
 * Renders a destination's key template for one dataset. {timestamp} is the capture time in the dataset
 * name (folders are named "<subdirectory>-<timestamp>"); empty segments are dropped.
 */
internal fun renderKeyBase(template: String, directory: String, subdirectory: String, datasetName: String): String {
    fun clean(value: String) = value.trim().replace('/', '-').replace('\\', '-')
        .takeUnless { it.equals("Default", ignoreCase = true) } ?: ""
    val dir = clean(directory)
    val sub = clean(subdirectory).takeUnless { it.equals(dir, ignoreCase = true) } ?: ""
    val name = if (datasetName.endsWith(".zip", ignoreCase = true)) datasetName.dropLast(4) else datasetName
    val timestamp = CAPTURE_TIMESTAMP.find(name)?.value ?: name
    val rendered = template
        .replace("{directory}", dir)
        .replace("{subdirectory}", sub)
        .replace("{dataset}", name)
        .replace("{timestamp}", timestamp)
        .split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
    return rendered.ifEmpty { name }
}

/** Objects for a dataset: the zip, or every file with metadata.json last so its presence means "complete". */
internal fun planObjects(datasetDir: File, keyBase: String, zip: File?): List<PlannedObject> =
    if (zip != null) {
        listOf(PlannedObject(zip, "$keyBase.zip", zip.name))
    } else {
        datasetDir.walkTopDown().filter { it.isFile }
            .map { val relative = it.relativeTo(datasetDir).invariantSeparatorsPath; PlannedObject(it, "$keyBase/$relative", relative) }
            .sortedWith(compareBy({ it.relativePath == "metadata.json" }, { it.relativePath }))
            .toList()
    }

/** Uniform parts (Cloudflare R2 requires it), grown only when a file would need more than 10,000 parts. */
internal fun partSizeFor(fileSize: Long, partSizeMib: Int): Long {
    val minimum = (fileSize + MAX_PARTS - 1) / MAX_PARTS
    return maxOf(partSizeMib * MIB, (minimum + MIB - 1) / MIB * MIB)
}

/** One pass over the file: whole-file MD5 plus MD5 and SHA-256 of every part. */
internal suspend fun hashFile(file: File, partSize: Long): FileDigests {
    val encoder = Base64.getEncoder()
    val whole = MessageDigest.getInstance("MD5")
    var partMd5 = MessageDigest.getInstance("MD5")
    var partSha = MessageDigest.getInstance("SHA-256")
    val partMd5s = mutableListOf<String>()
    val partShas = mutableListOf<String>()
    var inPart = 0L
    val buffer = ByteArray(MIB.toInt())
    file.inputStream().use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), partSize - inPart).toInt())
            if (read < 0) break
            whole.update(buffer, 0, read)
            partMd5.update(buffer, 0, read)
            partSha.update(buffer, 0, read)
            inPart += read
            if (inPart == partSize) {
                partMd5s += encoder.encodeToString(partMd5.digest())
                partShas += partSha.digest().toHex()
                partMd5 = MessageDigest.getInstance("MD5")
                partSha = MessageDigest.getInstance("SHA-256")
                inPart = 0
            }
        }
    }
    if (inPart > 0 || partMd5s.isEmpty()) {
        partMd5s += encoder.encodeToString(partMd5.digest())
        partShas += partSha.digest().toHex()
    }
    return FileDigests(encoder.encodeToString(whole.digest()), partMd5s, partShas)
}

internal fun contentTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"
    "m4a" -> "audio/mp4"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "zip" -> "application/zip"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}

object S3Sync {

    suspend fun syncDataset(
        context: Context,
        destination: S3Destination,
        dataset: DatasetSummary,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit
    ): SyncResult {
        val client = S3Destinations.client(context, destination)
            ?: return SyncResult(false, context.getString(R.string.s3_credentials_missing), stopRun = true)
        val keyBase = renderKeyBase(destination.keyTemplate, dataset.directory, dataset.subdirectory, dataset.name)
        return try {
            val zip = when {
                dataset.isZip -> dataset.path
                destination.zipMode -> uploadZip(context, dataset)
                else -> null
            }
            val objects = planObjects(dataset.path, keyBase, zip)
            val total = objects.sumOf { it.file.length() }
            var done = 0L
            var uploaded = 0
            onProgress(0, total)
            for (planned in objects) {
                if (uploadFile(context, client, destination, dataset.name, planned) { sent -> onProgress(done + sent, total) }) {
                    uploaded++
                }
                done += planned.file.length()
                onProgress(done, total)
            }
            if (zip != null && zip != dataset.path) zip.delete()
            SyncResult(true, context.getString(R.string.s3_sync_result, uploaded, objects.size - uploaded))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e("Upload of %s failed: %s: %s", dataset.name, e.javaClass.simpleName, e.message)
            val stopRun = when (e) {
                is S3SyncException -> e.stopRun
                is S3Exception -> e.status == 403 || e.status == 507 || e.code in FATAL_S3_CODES
                is FileChangedException -> false
                // Retries were exhausted: the network is gone, so later datasets would fail too.
                is IOException -> true
                else -> false
            }
            // A 403 on HEAD has no error body, so say what usually causes it.
            val message = if (e is S3Exception && e.status == 403) {
                context.getString(R.string.s3_access_denied, e.message)
            } else {
                e.message ?: e.javaClass.simpleName
            }
            SyncResult(false, message, stopRun = stopRun)
        }
    }

    /** Aborts every unfinished multipart upload of a destination (best effort) and forgets them. */
    suspend fun abortUnfinishedUploads(context: Context, destination: S3Destination) {
        val state = CloudSyncManager.syncState(context, destination.syncStateId)
        val client = S3Destinations.client(context, destination, maxAttempts = 2)
        for ((name, value) in state.all) {
            if (!name.startsWith(UPLOAD_RECORD_PREFIX)) continue
            try {
                val record = UploadRecord.fromJson(value as String)
                record.uploadId?.let { client?.abortMultipartUpload(record.key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("Could not abort upload %s: %s", name, e.message)
            }
            state.edit().remove(name).commit()
        }
    }

    // Zips a folder dataset into the cache, under a temporary name until complete, so an interrupted
    // upload can resume with the same bytes.
    private fun uploadZip(context: Context, dataset: DatasetSummary): File {
        val zip = File(context.cacheDir, "s3_upload/${dataset.name}.zip")
        if (zip.exists()) return zip
        if (context.cacheDir.usableSpace < dataset.sizeBytes + 64 * MIB) {
            throw S3SyncException(context.getString(R.string.s3_no_space_for_zip))
        }
        zip.parentFile?.mkdirs()
        val partial = File(zip.path + ".partial")
        zipEverything(dataset.path, partial.path)
        if (!partial.renameTo(zip)) throw IOException("Could not finish ${zip.name}")
        return zip
    }

    /** Returns true if the file was uploaded, false if an identical object was already there. */
    private suspend fun uploadFile(
        context: Context,
        client: S3Client,
        destination: S3Destination,
        datasetName: String,
        planned: PlannedObject,
        onSent: (Long) -> Unit
    ): Boolean {
        val file = planned.file
        val size = file.length()
        val lastModified = file.lastModified()
        val partSize = partSizeFor(size, destination.partSizeMib)
        val state = CloudSyncManager.syncState(context, destination.syncStateId)
        val recordName = "$UPLOAD_RECORD_PREFIX$datasetName/${planned.relativePath}"

        val saved = state.getString(recordName, null)?.let { UploadRecord.fromJson(it) }
        var record = saved?.takeIf {
            it.key == planned.key && it.size == size && it.lastModified == lastModified && it.partSize == partSize &&
                    System.currentTimeMillis() - it.startedAt < STALE_UPLOAD_MS
        }
        if (saved != null && record == null) {
            saved.uploadId?.let { client.abortMultipartUpload(saved.key, it) }
            state.edit().remove(recordName).commit()
        }
        val digests = record?.digests ?: hashFile(file, partSize)

        val remote = client.headObject(planned.key)
        if (remote != null) {
            if (digests.matches(remote, size) || !destination.overwrite) {
                record?.uploadId?.let { client.abortMultipartUpload(planned.key, it) }
                state.edit().remove(recordName).commit()
                if (digests.matches(remote, size)) return false
                throw S3SyncException(context.getString(R.string.s3_conflict, planned.key))
            }
        }

        try {
            if (digests.partMd5Base64.size == 1) {
                putSingle(context, client, planned, digests, size, lastModified, ifNoneMatch = !destination.overwrite, onSent)
            } else {
                if (record == null) {
                    record = UploadRecord(planned.key, size, lastModified, partSize, System.currentTimeMillis(), digests)
                }
                uploadParts(client, state, recordName, record, file, onSent, ifNoneMatch = !destination.overwrite)
            }
        } catch (e: FileChangedException) {
            state.getString(recordName, null)?.let { UploadRecord.fromJson(it).uploadId }?.let { client.abortMultipartUpload(planned.key, it) }
            state.edit().remove(recordName).commit()
            throw e
        }

        val uploaded = client.headObject(planned.key)
        val verified = uploaded != null && (digests.matches(uploaded, size) ||
                // Opaque ETag (e.g. server-side encryption): size matched and the server checked every part's Content-MD5.
                (uploaded.size == size && !MD5_ETAG.matches(uploaded.etag)))
        if (!verified) throw S3SyncException(context.getString(R.string.s3_verify_failed, planned.key))
        return true
    }

    private suspend fun putSingle(
        context: Context,
        client: S3Client,
        planned: PlannedObject,
        digests: FileDigests,
        size: Long,
        lastModified: Long,
        ifNoneMatch: Boolean,
        onSent: (Long) -> Unit
    ) {
        suspend fun put(conditional: Boolean) = client.putObject(
            planned.key, FileRangeBody(planned.file, 0, size, size, lastModified, onSent),
            digests.partMd5Base64[0], digests.partSha256[0], contentTypeFor(planned.file.name),
            mapOf(MD5_METADATA to digests.md5Base64), ifNoneMatch = conditional
        )
        try {
            put(ifNoneMatch)
        } catch (e: S3Exception) {
            when (e.status) {
                412 -> throw S3SyncException(context.getString(R.string.s3_conflict, planned.key))
                else -> throw e
            }
        }
    }

    private suspend fun uploadParts(
        client: S3Client,
        state: SharedPreferences,
        recordName: String,
        start: UploadRecord,
        file: File,
        onSent: (Long) -> Unit,
        ifNoneMatch: Boolean,
        restarted: Boolean = false
    ) {
        var record = start
        fun save() = state.edit().putString(recordName, record.toJson()).commit()

        val uploadId = record.uploadId ?: client.createMultipartUpload(
            record.key, contentTypeFor(file.name), mapOf(MD5_METADATA to record.digests.md5Base64)
        ).also {
            record = record.copy(uploadId = it, completed = emptyMap())
            save()
        }
        val known: Map<Int, String> = try {
            client.listParts(record.key, uploadId)
        } catch (e: S3Exception) {
            when {
                // The upload expired or was aborted elsewhere: start a new one, once.
                e.status == 404 && !restarted -> {
                    record = record.copy(uploadId = null, completed = emptyMap())
                    return uploadParts(client, state, recordName, record, file, onSent, ifNoneMatch, restarted = true)
                }
                // No ListParts permission: trust the parts this device saw complete.
                e.status == 403 -> record.completed
                else -> throw e
            }
        }

        val etags = sortedMapOf<Int, String>()
        val digests = record.digests
        for (index in digests.partMd5Base64.indices) {
            val number = index + 1
            val offset = index * record.partSize
            val length = minOf(record.partSize, record.size - offset)
            val expected = digests.partMd5Hex(index)
            if (known[number]?.trim('"') == expected) {
                etags[number] = expected
            } else {
                val body = FileRangeBody(file, offset, length, record.size, record.lastModified) { onSent(offset + it) }
                val etag = client.uploadPart(record.key, uploadId, number, body, digests.partMd5Base64[index], digests.partSha256[index])
                etags[number] = etag
                record = record.copy(completed = record.completed + (number to etag))
                save()
            }
            onSent(offset + length)
        }

        if (file.length() != record.size || file.lastModified() != record.lastModified) throw FileChangedException(file)
        try {
            client.completeMultipartUpload(record.key, uploadId, etags, ifNoneMatch = ifNoneMatch)
        } catch (e: S3Exception) {
            // NoSuchUpload after a retried Complete usually means the first one succeeded; the HEAD check decides.
            if (e.status != 404) throw e
        }
        state.edit().remove(recordName).commit()
    }
}
