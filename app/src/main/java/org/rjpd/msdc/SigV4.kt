package org.rjpd.msdc

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 for S3 header authentication.
 * Plain JVM code (no Android imports) so it can be checked against AWS's published examples.
 */
internal object SigV4 {

    val EMPTY_SHA256 = sha256Hex(ByteArray(0))

    /**
     * Returns the Authorization header value. [headers] must use lower-case names and include
     * host, x-amz-date and x-amz-content-sha256; every header in it is signed.
     */
    fun authorization(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        accessKey: String,
        secretKey: String,
        region: String
    ): String {
        val amzDate = headers.getValue("x-amz-date")
        val date = amzDate.substring(0, 8)
        val names = headers.keys.sorted()
        val signedHeaders = names.joinToString(";")
        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            names.joinToString("") { "$it:${headers.getValue(it).trim()}\n" },
            signedHeaders,
            headers.getValue("x-amz-content-sha256")
        ).joinToString("\n")

        val scope = "$date/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"

        var key = "AWS4$secretKey".toByteArray()
        for (part in listOf(date, region, "s3", "aws4_request")) {
            key = hmacSha256(key, part)
        }
        val signature = hmacSha256(key, stringToSign).toHex()
        return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    /** RFC 3986 encoding of UTF-8 bytes; only unreserved characters (and '/' in object keys) stay literal. */
    fun encode(value: String, keepSlash: Boolean = false): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.~" || (keepSlash && c == '/')) {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(c.code))
            }
        }
        return sb.toString()
    }

    fun canonicalQuery(query: Map<String, String>): String =
        query.entries.sortedBy { it.key }.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun hmacSha256(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray())
        }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
