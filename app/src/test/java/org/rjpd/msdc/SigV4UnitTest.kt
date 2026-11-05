package org.rjpd.msdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Expected signatures are AWS's published S3 SigV4 examples (examplebucket, 2013-05-24).
class SigV4UnitTest {
    private val accessKey = "AKIAIOSFODNN7EXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val host = "examplebucket.s3.amazonaws.com"
    private val amzDate = "20130524T000000Z"

    private fun signature(method: String, uri: String, query: Map<String, String>, headers: Map<String, String>): String =
        SigV4.authorization(method, uri, SigV4.canonicalQuery(query), headers, accessKey, secretKey, "us-east-1")
            .substringAfter("Signature=")

    @Test
    fun getObjectWithRange() {
        val headers = mapOf(
            "host" to host, "range" to "bytes=0-9", "x-amz-date" to amzDate, "x-amz-content-sha256" to SigV4.EMPTY_SHA256
        )
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signature("GET", "/test.txt", emptyMap(), headers))
    }

    @Test
    fun putObject() {
        val payload = SigV4.sha256Hex("Welcome to Amazon S3.".toByteArray())
        val headers = mapOf(
            "host" to host, "date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-date" to amzDate,
            "x-amz-storage-class" to "REDUCED_REDUNDANCY", "x-amz-content-sha256" to payload
        )
        val uri = "/" + SigV4.encode("test\$file.text", keepSlash = true)
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signature("PUT", uri, emptyMap(), headers))
    }

    @Test
    fun getBucketLifecycle() {
        val headers = mapOf("host" to host, "x-amz-date" to amzDate, "x-amz-content-sha256" to SigV4.EMPTY_SHA256)
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543", signature("GET", "/", mapOf("lifecycle" to ""), headers))
    }

    @Test
    fun listObjects() {
        val headers = mapOf("host" to host, "x-amz-date" to amzDate, "x-amz-content-sha256" to SigV4.EMPTY_SHA256)
        assertEquals(
            "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            signature("GET", "/", mapOf("prefix" to "J", "max-keys" to "2"), headers)
        )
    }

    @Test
    fun authorizationNamesSignedHeadersInOrder() {
        val headers = mapOf("x-amz-date" to amzDate, "host" to host, "x-amz-content-sha256" to SigV4.EMPTY_SHA256)
        val authorization = SigV4.authorization("GET", "/", "", headers, accessKey, secretKey, "us-east-1")
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=$accessKey/20130524/us-east-1/s3/aws4_request, "))
        assertTrue(authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date, "))
    }

    @Test
    fun encodeKeepsUnreservedAndEncodesUtf8() {
        assertEquals("S%C3%A3oPaulo/Rota%201%2Bx~/v_1-a.mp4", SigV4.encode("SãoPaulo/Rota 1+x~/v_1-a.mp4", keepSlash = true))
        assertEquals("a%2Fb%3D", SigV4.encode("a/b="))
    }
}
