package org.rjpd.msdc

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs against a real S3-compatible server only when MSDC_S3_ENDPOINT, MSDC_S3_BUCKET, MSDC_S3_ACCESS_KEY and
 * MSDC_S3_SECRET_KEY are set (MSDC_S3_REGION defaults to us-east-1). Writes only under MSDC_S3_PREFIX
 * (default ".msdc-test/") and deletes what it creates. Local MinIO:
 *   docker run -p 9000:9000 minio/minio server /data
 */
class S3ClientIntegrationTest {
    private lateinit var client: S3Client
    private lateinit var prefix: String

    @Before
    fun setUp() {
        val env = System.getenv()
        assumeTrue("MSDC_S3_* not set", listOf("ENDPOINT", "BUCKET", "ACCESS_KEY", "SECRET_KEY").all { !env["MSDC_S3_$it"].isNullOrEmpty() })
        client = S3Client(
            env.getValue("MSDC_S3_ENDPOINT"), env["MSDC_S3_REGION"] ?: "us-east-1", env.getValue("MSDC_S3_BUCKET"), true,
            env.getValue("MSDC_S3_ACCESS_KEY"), env.getValue("MSDC_S3_SECRET_KEY")
        )
        prefix = (env["MSDC_S3_PREFIX"] ?: ".msdc-test/") + UUID.randomUUID() + "/"
    }

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun putHeadListDelete_andWrongMd5IsRejected() = runBlocking {
        val key = prefix + "SãoPaulo/Rota 1+x/ok.txt"
        val data = "hello msdc\n".toByteArray()
        val etag = client.putObject(key, data.toRequestBody(), b64(md5(data)), SigV4.sha256Hex(data), "text/plain", mapOf("md5chksum" to b64(md5(data))))
        assertEquals(md5(data).toHex(), etag)
        assertEquals(S3ObjectInfo(data.size.toLong(), etag, b64(md5(data))), client.headObject(key))
        assertTrue(key in client.listObjectKeys(prefix))
        try {
            client.putObject(prefix + "bad.txt", data.toRequestBody(), b64(md5("other".toByteArray())), SigV4.sha256Hex(data))
            fail("Expected BadDigest")
        } catch (e: S3Exception) {
            assertEquals(400, e.status)
        }
        try {
            client.putObject(key, data.toRequestBody(), b64(md5(data)), SigV4.sha256Hex(data), ifNoneMatch = true)
            fail("Expected PreconditionFailed")
        } catch (e: S3Exception) {
            assertEquals(412, e.status)
        }
        client.deleteObject(key)
        assertNull(client.headObject(key))
    }

    @Test
    fun multipartUploadResumeAndVerify() = runBlocking {
        val part1 = ByteArray(5 * 1024 * 1024) { (it * 31).toByte() }
        val part2 = ByteArray(1024) { 7 }
        val file = File.createTempFile("mpu", ".bin").apply { writeBytes(part1 + part2); deleteOnExit() }
        val key = prefix + "video.mp4"
        val whole = b64(md5(part1 + part2))
        val uploadId = client.createMultipartUpload(key, "video/mp4", mapOf("md5chksum" to whole))
        try {
            client.uploadPart(key, uploadId, 1, FileRangeBody(file, 0, part1.size.toLong(), file.length(), file.lastModified()), b64(md5(part1)), SigV4.sha256Hex(part1))
            // A fresh client, as after process death, learns the finished parts from the server.
            val resumed = S3Client(
                System.getenv("MSDC_S3_ENDPOINT"), System.getenv("MSDC_S3_REGION") ?: "us-east-1", System.getenv("MSDC_S3_BUCKET"), true,
                System.getenv("MSDC_S3_ACCESS_KEY"), System.getenv("MSDC_S3_SECRET_KEY")
            )
            assertEquals(mapOf(1 to md5(part1).toHex()), resumed.listParts(key, uploadId))
            val etag2 = resumed.uploadPart(key, uploadId, 2, FileRangeBody(file, part1.size.toLong(), part2.size.toLong(), file.length(), file.lastModified()), b64(md5(part2)), SigV4.sha256Hex(part2))
            resumed.completeMultipartUpload(key, uploadId, resumed.listParts(key, uploadId).also { assertEquals(md5(part2).toHex(), it[2]) })
            assertEquals(md5(part2).toHex(), etag2)

            val info = client.headObject(key)!!
            assertEquals(file.length(), info.size)
            assertEquals(md5(md5(part1) + md5(part2)).toHex() + "-2", info.etag)
            assertEquals(whole, info.md5Base64)
        } finally {
            client.abortMultipartUpload(key, uploadId)
            client.deleteObject(key)
        }
    }

    @Test
    fun abortedUploadIsGone() = runBlocking {
        val key = prefix + "aborted.bin"
        val uploadId = client.createMultipartUpload(key)
        val data = ByteArray(1024)
        client.uploadPart(key, uploadId, 1, data.toRequestBody(), b64(md5(data)), SigV4.sha256Hex(data))
        client.abortMultipartUpload(key, uploadId)
        try {
            client.listParts(key, uploadId)
            fail("Expected NoSuchUpload")
        } catch (e: S3Exception) {
            assertEquals(404, e.status)
        }
    }
}
