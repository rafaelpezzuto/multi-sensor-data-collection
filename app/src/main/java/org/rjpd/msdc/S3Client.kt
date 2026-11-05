package org.rjpd.msdc

import android.annotation.SuppressLint
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.StringReader
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.w3c.dom.Element
import org.xml.sax.InputSource
import timber.log.Timber

internal const val DEFAULT_S3_MAX_ATTEMPTS = 10

private const val TAG = "S3Client"
private const val MAX_BACKOFF_MS = 60_000L
private const val MAX_CLOCK_SKEW_MS = 15 * 60_000L
private val RETRYABLE_CODES = setOf(
    "RequestTimeout", "RequestTimeTooSkewed", "SlowDown", "InternalError", "ServiceUnavailable",
    "BadDigest", "XAmzContentSHA256Mismatch"
)
private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private val XML: MediaType = "application/xml".toMediaType()

/** An S3 error response. The message carries only the error code and text, never request headers or signatures. */
class S3Exception(val status: Int, val code: String, message: String) : Exception("$code: $message (HTTP $status)") {
    val retryable: Boolean
        get() = (status >= 500 && status != 501) || status == 408 || status == 429 || code in RETRYABLE_CODES
}

/** The local file changed after it was hashed; resending it would upload inconsistent data. */
class FileChangedException(file: File) : IOException("File changed during upload: ${file.name}")

data class S3ObjectInfo(val size: Long, val etag: String, val md5Base64: String?)

/** Streams part of a file, failing instead of sending different bytes if the file changed since it was hashed. */
class FileRangeBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val expectedFileLength: Long,
    private val expectedLastModified: Long,
    private val onBytesSent: (Long) -> Unit = {}
) : RequestBody() {
    override fun contentType(): MediaType? = null
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        if (file.length() != expectedFileLength || file.lastModified() != expectedLastModified) {
            throw FileChangedException(file)
        }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            while (sent < length) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), length - sent).toInt())
                if (read < 0) throw FileChangedException(file)
                sink.write(buffer, 0, read)
                sent += read
                onBytesSent(sent)
            }
        }
    }
}

/**
 * Minimal S3 client for S3-compatible stores: SigV4 header auth, the payload's SHA-256 always signed,
 * and only baseline S3 features (no flexible checksums or aws-chunked uploads), so it works with
 * providers that implement just the core API.
 */
class S3Client(
    endpoint: String,
    private val region: String,
    private val bucket: String,
    private val pathStyle: Boolean,
    private val accessKey: String,
    private val secretKey: String,
    trustedCertificate: X509Certificate? = null,
    private val maxAttempts: Int = DEFAULT_S3_MAX_ATTEMPTS,
    private val retryBaseDelayMs: Long = 1000L
) {
    private val endpointUrl: HttpUrl = endpoint.toHttpUrl()
    private val client: OkHttpClient
    private val completeClient: OkHttpClient

    @Volatile
    private var clockOffsetMs = 0L

    init {
        val builder = baseClient.newBuilder()
        if (trustedCertificate != null) {
            val trustManager = trustManagerFor(trustedCertificate)
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }
        client = builder.build()
        // Servers may take minutes to assemble a large object before answering CompleteMultipartUpload.
        completeClient = client.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
    }

    /** Server time minus device time, learned from the server's Date header after a clock-skew error. */
    val clockOffsetMillis: Long get() = clockOffsetMs

    suspend fun headObject(key: String): S3ObjectInfo? = retrying {
        try {
            val response = send("HEAD", key)
            S3ObjectInfo(
                size = response.headers["Content-Length"]?.toLongOrNull() ?: -1L,
                etag = response.etag,
                md5Base64 = response.headers["x-amz-meta-md5chksum"]
            )
        } catch (e: S3Exception) {
            if (e.status == 404) null else throw e
        }
    }

    suspend fun putObject(
        key: String,
        body: RequestBody,
        md5Base64: String,
        sha256Hex: String,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        ifNoneMatch: Boolean = false
    ): String = retrying {
        val headers = mapOf("Content-MD5" to md5Base64) + metadata.mapKeys { "x-amz-meta-${it.key}" } +
                (if (ifNoneMatch) mapOf("If-None-Match" to "*") else emptyMap())
        send("PUT", key, body = body, payloadSha256 = sha256Hex, headers = headers, contentType = contentType).etag
    }

    suspend fun createMultipartUpload(key: String, contentType: String? = null, metadata: Map<String, String> = emptyMap()): String =
        retrying {
            val headers = metadata.mapKeys { "x-amz-meta-${it.key}" }
            val response = send("POST", key, mapOf("uploads" to ""), headers = headers, contentType = contentType)

            parseXml(response.body).text("UploadId") ?: throw S3Exception(response.code, "InvalidResponse", "No UploadId in response")
        }

    suspend fun uploadPart(key: String, uploadId: String, partNumber: Int, body: RequestBody, md5Base64: String, sha256Hex: String): String =
        retrying {
            val query = mapOf("partNumber" to partNumber.toString(), "uploadId" to uploadId)
            send("PUT", key, query, body, sha256Hex, mapOf("Content-MD5" to md5Base64)).etag
        }

    /** Parts the server already has, part number to ETag. The server's list is the truth when resuming. */
    suspend fun listParts(key: String, uploadId: String): Map<Int, String> {
        val parts = sortedMapOf<Int, String>()
        var marker: String? = null
        while (true) {
            val query = mapOf("uploadId" to uploadId) + (marker?.let { mapOf("part-number-marker" to it) } ?: emptyMap())
            val root = parseXml(retrying { send("GET", key, query) }.body)
            for (part in root.elements("Part")) {
                val number = part.text("PartNumber")?.toIntOrNull() ?: continue
                parts[number] = part.text("ETag")?.trim('"') ?: continue
            }
            marker = root.text("NextPartNumberMarker")
            if (root.text("IsTruncated") != "true" || marker.isNullOrEmpty()) return parts
        }
    }

    suspend fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: Map<Int, String>,
        ifNoneMatch: Boolean = false
    ): String {
        val xml = parts.toSortedMap().entries.joinToString("", "<CompleteMultipartUpload>", "</CompleteMultipartUpload>") {
            "<Part><PartNumber>${it.key}</PartNumber><ETag>\"${it.value}\"</ETag></Part>"
        }.toByteArray()
        return retrying {
            val headers = if (ifNoneMatch) mapOf("If-None-Match" to "*") else emptyMap()
            val response = send(
                method = "POST",
                key = key,
                query = mapOf("uploadId" to uploadId),
                body = xml.toRequestBody(XML),
                payloadSha256 = SigV4.sha256Hex(xml),
                headers = headers,
                slow = true
            )

            parseXml(response.body).text("ETag")?.trim('"')?.takeIf { it.isNotBlank() }
                ?: throw S3Exception(response.code, "InvalidResponse", "No ETag in completion response")
        }
    }

    suspend fun abortMultipartUpload(key: String, uploadId: String) = retrying {
        try {
            send("DELETE", key, mapOf("uploadId" to uploadId))
            Unit
        } catch (e: S3Exception) {
            if (e.status != 404) throw e
        }
    }

    suspend fun listObjectKeys(prefix: String, maxKeys: Int = 1000): List<String> = retrying {
        val response = send("GET", "", mapOf("list-type" to "2", "prefix" to prefix, "max-keys" to maxKeys.toString()))
        parseXml(response.body).elements("Contents").mapNotNull { it.text("Key") }
    }

    suspend fun deleteObject(key: String) = retrying {
        send("DELETE", key)
        Unit
    }

    private class S3Response(val code: Int, val headers: Headers, val body: String) {
        val etag: String get() = headers["ETag"]?.trim('"') ?: ""
    }

    private suspend fun send(
        method: String,
        key: String,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        payloadSha256: String = SigV4.EMPTY_SHA256,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
        slow: Boolean = false
    ): S3Response {
        val url = buildUrl(key, query)
        val host = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        val signedHeaders = headers.mapKeys { it.key.lowercase() } + mapOf(
            "host" to host,
            "x-amz-date" to AMZ_DATE.format(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffsetMs)),
            "x-amz-content-sha256" to payloadSha256
        )
        val authorization = SigV4.authorization(
            method, url.encodedPath, SigV4.canonicalQuery(query), signedHeaders, accessKey, secretKey, region
        )
        val requestBody = body ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody() else null
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .apply {
                signedHeaders.forEach { (name, value) -> if (name != "host") header(name, value) }
                header("Authorization", authorization)
                contentType?.let { header("Content-Type", it) }
            }
            .build()

        (if (slow) completeClient else client).newCall(request).await().use { response ->
            val text = if (method == "HEAD") "" else response.body.string()

            // CompleteMultipartUpload can answer 200 and still carry an error document.
            val embeddedError = response.isSuccessful && text.isNotBlank() &&
                    parseXml(text).localName == "Error"
            if (response.isSuccessful && !embeddedError) {
                return S3Response(response.code, response.headers, text)
            }

            var code = xmlText(text, "Code") ?: "HTTP${response.code}"
            val message = xmlText(text, "Message") ?: response.message
            val serverDate = response.headers.getDate("Date")?.time
            // HEAD errors have no body, so a 403 with a far-off server Date is treated as clock skew too.
            if (serverDate != null && (code == "RequestTimeTooSkewed" ||
                        (response.code == 403 && abs(serverDate - System.currentTimeMillis() - clockOffsetMs) > MAX_CLOCK_SKEW_MS))
            ) {
                clockOffsetMs = serverDate - System.currentTimeMillis()
                code = "RequestTimeTooSkewed"
            }

            throw S3Exception(response.code, code, message)
        }
    }

    private fun buildUrl(key: String, query: Map<String, String>): HttpUrl {
        val host = if (pathStyle) endpointUrl.host else "$bucket.${endpointUrl.host}"
        val port = if (endpointUrl.port == HttpUrl.defaultPort(endpointUrl.scheme)) "" else ":${endpointUrl.port}"
        val encodedKey = SigV4.encode(key, keepSlash = true)
        val path = when {
            !pathStyle -> "/$encodedKey"
            key.isEmpty() -> "/${SigV4.encode(bucket)}"
            else -> "/${SigV4.encode(bucket)}/$encodedKey"
        }
        val queryString = SigV4.canonicalQuery(query)
        return "${endpointUrl.scheme}://$host$port$path${if (queryString.isEmpty()) "" else "?$queryString"}".toHttpUrl()
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A rejected certificate will not fix itself; network drops (including TLS record errors) might.
                val untrusted = e is SSLPeerUnverifiedException || generateSequence(e as Throwable) { it.cause }.any { it is CertificateException }
                val retryable = (e is S3Exception && e.retryable) || (e is IOException && e !is FileChangedException && !untrusted)
                if (!retryable || attempt >= maxAttempts) throw e

                Timber.tag(TAG).w("Attempt $attempt failed, retrying: ${e.javaClass.simpleName}: ${e.message}")
                if (!(e is S3Exception && e.code == "RequestTimeTooSkewed")) {
                    val cappedDelayMs = minOf(MAX_BACKOFF_MS, retryBaseDelayMs shl minOf(attempt - 1, 16))
                    delay((cappedDelayMs * Random.nextDouble(0.5, 1.0)).toLong())
                }

                attempt++
            }
        }
    }

    companion object {
        // HTTP/1.1 only: against RustFS behind Caddy, HTTP/2 dropped more uploads than HTTP/1.1,
        // and one dropped HTTP/2 connection fails every request multiplexed on it.
        private val baseClient: OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        fun trustManagerFor(certificate: X509Certificate): X509TrustManager {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("server", certificate)
            }
            return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        /** Returns the top certificate of the chain the server presents, without trusting it. */
        fun fetchServerCertificate(endpoint: String): X509Certificate {
            val url = endpoint.toHttpUrl()
            var presented: Array<X509Certificate>? = null
            // Rejects every handshake: it only records the chain so the user can review it before pinning.
            @SuppressLint("CustomX509TrustManager")
            val capturing = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    presented = chain
                    throw CertificateException("Certificate captured for review, connection refused")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capturing), null) }
            try {
                (sslContext.socketFactory.createSocket(url.host, url.port) as SSLSocket).use {
                    it.soTimeout = 15_000
                    it.startHandshake()
                }
            } catch (_: IOException) {
                // Expected: the capturing trust manager always rejects the handshake.
            }
            return presented?.lastOrNull() ?: throw IOException("Server did not present a certificate")
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}

private fun parseXml(text: String): Element {
    if (text.contains("<!DOCTYPE", ignoreCase = true)) throw IOException("Unexpected DOCTYPE in S3 response")
    return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
}

private fun xmlText(text: String, tag: String): String? =
    try {
        if (text.isBlank()) null else parseXml(text).text(tag)
    } catch (_: Exception) {
        null
    }

// Element-order independent on purpose: RustFS lists <ETag> before <PartNumber>, AWS the other way round.
private fun Element.text(tag: String): String? = getElementsByTagNameNS("*", tag).item(0)?.textContent

private fun Element.elements(tag: String): List<Element> =
    getElementsByTagNameNS("*", tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
