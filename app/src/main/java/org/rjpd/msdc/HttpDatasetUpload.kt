package org.rjpd.msdc

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 300_000
private const val LINE_FEED = "\r\n"
private val HTTP_UPLOAD_CLIENT = OkHttpClient.Builder()
    .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    .writeTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    .build()

internal suspend fun uploadDatasetArchive(
    uploadUrl: String,
    token: String,
    dataset: DatasetSummary,
    archive: File
): SyncResult {
    val cancellationContext = currentCoroutineContext()
    cancellationContext.ensureActive()

    val boundary = "===Boundary_${System.currentTimeMillis()}==="
    val prefix = buildMultipartPrefix(boundary, dataset, archive.name)
    val suffix = ("$LINE_FEED--$boundary--$LINE_FEED").toByteArray(Charsets.UTF_8)
    val length = prefix.size + archive.length() + suffix.size
    val body = object : RequestBody() {
        override fun contentType() = "multipart/form-data; boundary=\"$boundary\"".toMediaType()
        override fun contentLength() = length

        override fun writeTo(sink: BufferedSink) {
            try {
                cancellationContext.ensureActive()
                sink.write(prefix)
                archive.inputStream().use { copySyncStream(it, sink.outputStream(), cancellationContext) }
                sink.write(suffix)
                cancellationContext.ensureActive()
            } catch (e: CancellationException) {
                // OkHttp's dispatcher is not a coroutine: report cancellation as an I/O failure.
                throw IOException("Upload cancelled", e)
            }
        }
    }
    val request = Request.Builder().url(uploadUrl).post(body).apply {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }.build()
    val call = HTTP_UPLOAD_CLIENT.newCall(request)

    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        if (it.isSuccessful) {
                            SyncResult(true, "Dataset uploaded successfully.", it.code)
                        } else {
                            val errorBody = it.body.string()
                            val detail = if (errorBody.isNotEmpty()) ": $errorBody" else ""
                            SyncResult(false, "Server returned HTTP ${it.code}$detail", it.code)
                        }
                    }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(result)
            }
        })
    }
}

private fun buildMultipartPrefix(boundary: String, dataset: DatasetSummary, filename: String): ByteArray {
    val body = StringBuilder()

    fun addFormField(name: String, value: String) {
        body.append("--").append(boundary).append(LINE_FEED)
        body.append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(LINE_FEED)
        body.append(LINE_FEED)
        body.append(value).append(LINE_FEED)
    }

    addFormField("dataset_name", dataset.name)
    addFormField("directory", dataset.directory)
    addFormField("subdirectory", dataset.subdirectory)
    body.append("--").append(boundary).append(LINE_FEED)
    body.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(LINE_FEED)
    body.append("Content-Type: application/zip").append(LINE_FEED)
    body.append(LINE_FEED)
    return body.toString().toByteArray(Charsets.UTF_8)
}
