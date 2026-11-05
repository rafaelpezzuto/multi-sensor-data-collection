package org.rjpd.msdc

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class S3ClientUnitTest {
    private lateinit var server: MockWebServer
    private lateinit var client: S3Client

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = S3Client(server.url("/").toString(), "us-east-1", "bucket", true, "AKTEST", "SECRETTEST", retryBaseDelayMs = 1)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(etag: String? = null, body: String = "") = MockResponse.Builder().code(200)
        .apply { if (etag != null) addHeader("ETag", "\"$etag\"") }
        .body(body)
        .build()

    private fun error(code: Int, s3Code: String, extraHeaders: Map<String, String> = emptyMap(), extraXml: String = "") =
        MockResponse.Builder().code(code)
            .apply { extraHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .body("<?xml version=\"1.0\"?><Error><Code>$s3Code</Code><Message>failed</Message>$extraXml</Error>")
            .build()

    private fun tempFile(bytes: ByteArray): File = File.createTempFile("part", ".bin").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    @Test
    fun headObject_encodesUtf8KeyAndSigns() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("ETag", "\"abc\"").addHeader("x-amz-meta-md5chksum", "bWQ1")
                .removeHeader("Content-Length").addHeader("Content-Length", "11").build()
        )
        val info = client.headObject("SãoPaulo/Rota 1+x/video.mp4")!!
        assertEquals(S3ObjectInfo(11, "abc", "bWQ1"), info)
        val request = server.takeRequest()
        assertEquals("/bucket/S%C3%A3oPaulo/Rota%201%2Bx/video.mp4", request.target)
        assertTrue(request.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential=AKTEST/"))
        assertEquals(SigV4.EMPTY_SHA256, request.headers["x-amz-content-sha256"])
    }

    @Test
    fun headObject_missingIsNull() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(client.headObject("missing"))
    }

    @Test
    fun uploadPart_connectionDroppedMidBody_isRetried() = runBlocking {
        val bytes = ByteArray(256 * 1024) { it.toByte() }
        val file = tempFile(bytes)
        // A response that drops the connection mid-body is never dequeued, so script "drop once, then succeed".
        var dropped = false
        server.dispatcher = object : Dispatcher() {
            override fun peek(): MockResponse =
                if (dropped) ok(etag = "part1") else MockResponse.Builder().onRequestBody(SocketEffect.CloseSocket()).build().also { dropped = true }
            override fun dispatch(request: RecordedRequest) = ok(etag = "part1")
        }
        val body = FileRangeBody(file, 0, bytes.size.toLong(), file.length(), file.lastModified())
        val etag = client.uploadPart("k", "upload-1", 1, body, "md5", SigV4.sha256Hex(bytes))
        assertTrue(dropped)
        assertEquals("part1", etag)
        // The dropped attempt is not recorded; the completed one carried the whole part again.
        assertEquals(1, server.requestCount)
        assertEquals(bytes.size.toLong(), server.takeRequest().bodySize)
    }

    @Test
    fun uploadPart_changedFile_isNotRetried() = runBlocking {
        val file = tempFile(ByteArray(1024))
        val body = FileRangeBody(file, 0, 1024, file.length() + 1, file.lastModified())
        try {
            client.uploadPart("k", "upload-1", 1, body, "md5", "sha")
            fail("Expected FileChangedException")
        } catch (_: FileChangedException) {
        }
        assertTrue(server.requestCount <= 1)
    }

    @Test
    fun complete_okWithErrorDocument_isRetried() = runBlocking {
        server.enqueue(ok(body = "<Error><Code>InternalError</Code><Message>try again</Message></Error>"))
        server.enqueue(ok(body = "<CompleteMultipartUploadResult><ETag>\"abc-2\"</ETag></CompleteMultipartUploadResult>"))
        assertEquals("abc-2", client.completeMultipartUpload("k", "u", mapOf(2 to "b", 1 to "a")))
        assertEquals(2, server.requestCount)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.indexOf("<PartNumber>1</PartNumber>") < body.indexOf("<PartNumber>2</PartNumber>"))
    }

    @Test
    fun complete_namespacedErrorDocument_isRetried() = runBlocking {
        server.enqueue(ok(body = "<Error xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Code>InternalError</Code><Message>try again</Message></Error>"))
        server.enqueue(ok(body = "<CompleteMultipartUploadResult><ETag>\"abc-2\"</ETag></CompleteMultipartUploadResult>"))
        assertEquals("abc-2", client.completeMultipartUpload("k", "u", mapOf(1 to "a")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun complete_missingEtag_isRejected() = runBlocking {
        server.enqueue(ok(body = "<CompleteMultipartUploadResult/>"))
        try {
            client.completeMultipartUpload("k", "u", mapOf(1 to "a"))
            fail("Expected invalid response")
        } catch (e: S3Exception) {
            assertEquals("InvalidResponse", e.code)
        }
    }

    @Test
    fun complete_conditionalWrite_preservesConflictAndSignsCondition() = runBlocking {
        server.enqueue(error(412, "PreconditionFailed"))
        try {
            client.completeMultipartUpload("k", "u", mapOf(1 to "a"), ifNoneMatch = true)
            fail("Expected conflict")
        } catch (e: S3Exception) {
            assertEquals(412, e.status)
        }
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("*", request.headers["If-None-Match"])
        assertTrue(request.headers["Authorization"]!!.contains("if-none-match"))
    }

    @Test
    fun complete_overwriteEnabled_omitsCondition() = runBlocking {
        server.enqueue(ok(body = "<CompleteMultipartUploadResult><ETag>\"abc-2\"</ETag></CompleteMultipartUploadResult>"))
        client.completeMultipartUpload("k", "u", mapOf(1 to "a"), ifNoneMatch = false)
        assertNull(server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun clockSkew_usesServerDateAndRetries() = runBlocking {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val serverTime = System.currentTimeMillis() + 3_600_000
        server.enqueue(error(403, "RequestTimeTooSkewed", mapOf("Date" to format.format(Date(serverTime)))))
        server.enqueue(ok())
        client.deleteObject("k")
        assertEquals(2, server.requestCount)
        assertTrue(client.clockOffsetMillis in 3_590_000..3_610_000)
        val first = server.takeRequest().headers["x-amz-date"]!!
        val retried = server.takeRequest().headers["x-amz-date"]!!
        assertTrue(retried > first)
    }

    @Test
    fun accessDenied_isNotRetried_andMessageHasNoSecrets() = runBlocking {
        server.enqueue(error(403, "SignatureDoesNotMatch", extraXml = "<AWSAccessKeyId>AKTEST</AWSAccessKeyId><StringToSign>AWS4-HMAC-SHA256 secret stuff</StringToSign>"))
        try {
            client.deleteObject("k")
            fail("Expected S3Exception")
        } catch (e: S3Exception) {
            assertEquals("SignatureDoesNotMatch", e.code)
            assertFalse(e.retryable)
            assertFalse(e.message!!.contains("AKTEST"))
            assertFalse(e.message!!.contains("StringToSign"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverError_isRetriedUntilSuccess() = runBlocking {
        server.enqueue(error(503, "SlowDown"))
        server.enqueue(MockResponse.Builder().code(502).body("<html>Bad gateway</html>").build())
        server.enqueue(ok())
        client.deleteObject("k")
        assertEquals(3, server.requestCount)
    }

    @Test
    fun listParts_paginatesAndAcceptsAnyElementOrder() = runBlocking {
        // RustFS lists <ETag> before <PartNumber>.
        server.enqueue(ok(body = "<ListPartsResult><IsTruncated>true</IsTruncated><NextPartNumberMarker>1</NextPartNumberMarker>" +
                "<Part><ETag>\"aaa\"</ETag><PartNumber>1</PartNumber><Size>5</Size></Part></ListPartsResult>"))
        server.enqueue(ok(body = "<ListPartsResult><IsTruncated>false</IsTruncated>" +
                "<Part><PartNumber>2</PartNumber><ETag>&quot;bbb&quot;</ETag></Part></ListPartsResult>"))
        assertEquals(mapOf(1 to "aaa", 2 to "bbb"), client.listParts("k", "u"))
        assertEquals("/bucket/k?uploadId=u", server.takeRequest().target)
        assertEquals("/bucket/k?part-number-marker=1&uploadId=u", server.takeRequest().target)
    }

    @Test
    fun createMultipartUpload_sendsMetadataAndReturnsUploadId() = runBlocking {
        server.enqueue(ok(body = "<InitiateMultipartUploadResult><UploadId>xyz</UploadId></InitiateMultipartUploadResult>"))
        assertEquals("xyz", client.createMultipartUpload("a/b.mp4", "video/mp4", mapOf("md5chksum" to "bWQ1")))
        val request = server.takeRequest()
        assertEquals("/bucket/a/b.mp4?uploads=", request.target)
        assertEquals("bWQ1", request.headers["x-amz-meta-md5chksum"])
        assertTrue(request.headers["Authorization"]!!.contains("x-amz-meta-md5chksum"))
    }
}
