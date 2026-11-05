package org.rjpd.msdc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private const val TAG = "S3Destinations"
private const val DESTINATIONS_PREFS = "s3_destinations"
private const val KEY_DESTINATIONS = "destinations"
private const val SECRETS_PREFS = "s3_secrets"
private const val SECRET_KEY_ALIAS = "msdc_secrets"
private const val SECRET_IV_LENGTH = 12

data class S3Destination(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "",
    val keyTemplate: String = "{directory}/{subdirectory}/{dataset}",
    val pathStyle: Boolean = true,
    val zipMode: Boolean = false,
    // Also the size up to which a file is sent in one request. 16 MiB: RustFS behind Caddy dropped
    // about half of 64 MiB uploads and few 16 MiB ones; a drop costs at most one part.
    val partSizeMib: Int = 16,
    val overwrite: Boolean = false,
    val trustedCertificate: String? = null
) {
    val displayName: String get() = name.ifBlank { bucket.ifBlank { endpoint } }

    // A profile can be edited to point elsewhere. Never reuse its sync markers or upload IDs
    // for a different bucket/key layout; retain the old state if the user switches back.
    internal val syncStateId: String
        get() = "$id-" + SigV4.sha256Hex(
            listOf(endpoint, region, bucket, pathStyle.toString(), keyTemplate, zipMode.toString())
                .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("endpoint", endpoint).put("region", region).put("bucket", bucket)
        .put("keyTemplate", keyTemplate).put("pathStyle", pathStyle).put("zipMode", zipMode)
        .put("partSizeMib", partSizeMib).put("overwrite", overwrite).put("trustedCertificate", trustedCertificate)

    companion object {
        fun fromJson(json: JSONObject) = S3Destination(
            id = json.getString("id"),
            name = json.optString("name"),
            endpoint = json.optString("endpoint"),
            region = json.optString("region", "us-east-1"),
            bucket = json.optString("bucket"),
            keyTemplate = json.optString("keyTemplate", "{directory}/{subdirectory}/{dataset}"),
            pathStyle = json.optBoolean("pathStyle", true),
            zipMode = json.optBoolean("zipMode", false),
            partSizeMib = json.optInt("partSizeMib", 16),
            overwrite = json.optBoolean("overwrite", false),
            trustedCertificate = json.optString("trustedCertificate").takeIf { it.isNotEmpty() && it != "null" }
        )
    }
}

/** Saved S3 destinations. Non-secret settings live in their own prefs file, never in the default prefs. */
object S3Destinations {

    fun list(context: Context): List<S3Destination> {
        val raw = context.getSharedPreferences(DESTINATIONS_PREFS, Context.MODE_PRIVATE).getString(KEY_DESTINATIONS, null)
            ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { S3Destination.fromJson(array.getJSONObject(it)) }
    }

    fun get(context: Context, id: String?): S3Destination? = id?.let { list(context).firstOrNull { d -> d.id == it } }

    fun save(context: Context, destination: S3Destination) {
        val updated = list(context).filter { it.id != destination.id } + destination
        write(context, updated)
    }

    fun delete(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
        SecretStore.remove(context, "$id.access_key")
        SecretStore.remove(context, "$id.secret_key")
    }

    private fun write(context: Context, destinations: List<S3Destination>) {
        context.getSharedPreferences(DESTINATIONS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DESTINATIONS, JSONArray(destinations.map { it.toJson() }).toString())
            .commit()
    }

    fun setAccessKey(context: Context, id: String, value: String) = SecretStore.put(context, "$id.access_key", value)
    fun setSecretKey(context: Context, id: String, value: String) = SecretStore.put(context, "$id.secret_key", value)
    fun hasAccessKey(context: Context, id: String) = SecretStore.contains(context, "$id.access_key")
    fun hasSecretKey(context: Context, id: String) = SecretStore.contains(context, "$id.secret_key")

    fun isComplete(context: Context, destination: S3Destination): Boolean =
        destination.endpoint.isNotBlank() && destination.bucket.isNotBlank() &&
                hasAccessKey(context, destination.id) && hasSecretKey(context, destination.id)

    /** Null when the destination is incomplete or its credentials can no longer be decrypted. */
    fun client(context: Context, destination: S3Destination, maxAttempts: Int = DEFAULT_S3_MAX_ATTEMPTS): S3Client? {
        if (destination.endpoint.isBlank() || destination.bucket.isBlank()) return null
        val accessKey = SecretStore.get(context, "${destination.id}.access_key") ?: return null
        val secretKey = SecretStore.get(context, "${destination.id}.secret_key") ?: return null
        return S3Client(
            destination.endpoint.trim(), destination.region.trim().ifEmpty { "us-east-1" }, destination.bucket.trim(),
            destination.pathStyle, accessKey, secretKey, destination.trustedCertificate?.let { decodeCertificate(it) },
            maxAttempts = maxAttempts
        )
    }

    fun decodeCertificate(base64Der: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64Der))) as X509Certificate

    fun encodeCertificate(certificate: X509Certificate): String = Base64.getEncoder().encodeToString(certificate.encoded)

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    /**
     * Checks what uploads need: write, list, an existence check that tells "missing" from "forbidden",
     * multipart with ListParts (for resume), and delete. Returns one line per step.
     */
    suspend fun testConnection(context: Context, destination: S3Destination): List<String> {
        val client = client(context, destination, maxAttempts = 2)
            ?: return listOf(context.getString(R.string.s3_test_incomplete))
        val results = mutableListOf<String>()

        suspend fun step(label: Int, block: suspend () -> Unit): Boolean = try {
            block()
            results += "✓ " + context.getString(label)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results += "✗ " + context.getString(label) + ": " + (e.message ?: e.javaClass.simpleName)
            false
        }

        val literalPrefix = destination.keyTemplate.substringBefore('{')
        val keyPrefix = literalPrefix.substring(0, literalPrefix.lastIndexOf('/') + 1).trimStart('/')
        val key = keyPrefix + ".msdc-test/" + UUID.randomUUID()
        val data = "MultiSensor connection test\n".toByteArray()
        val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data))

        if (!step(R.string.s3_test_write) { client.putObject(key, data.toRequestBody(), md5, SigV4.sha256Hex(data), "text/plain") }) {
            return results
        }
        step(R.string.s3_test_list) { check(key in client.listObjectKeys(key)) { "test object not listed" } }
        step(R.string.s3_test_exists) { check(client.headObject("$key-missing") == null) { "unexpected object" } }
        step(R.string.s3_test_multipart) {
            val uploadId = client.createMultipartUpload("$key.part")
            try {
                client.uploadPart("$key.part", uploadId, 1, data.toRequestBody(), md5, SigV4.sha256Hex(data))
                check(1 in client.listParts("$key.part", uploadId)) { "uploaded part not listed" }
            } finally {
                client.abortMultipartUpload("$key.part", uploadId)
            }
        }
        if (!step(R.string.s3_test_delete) { client.deleteObject(key) }) {
            results += context.getString(R.string.s3_test_left_behind, key)
        }
        return results
    }

}

/** Secrets encrypted with a non-exportable AES-GCM key in the Android Keystore; only ciphertext is stored. */
internal object SecretStore {
    fun put(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs(context).edit().putString(name, Base64.getEncoder().encodeToString(encrypted)).commit()
    }

    fun get(context: Context, name: String): String? {
        val stored = prefs(context).getString(name, null) ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, SECRET_IV_LENGTH))
            }
            String(cipher.doFinal(bytes, SECRET_IV_LENGTH, bytes.size - SECRET_IV_LENGTH))
        } catch (e: Exception) {
            // Keystore key lost or invalidated (data restored on another device, OEM keystore reset): ask again.
            Timber.tag(TAG).w("Stored secret %s can no longer be decrypted: %s", name, e.javaClass.simpleName)
            null
        }
    }

    fun contains(context: Context, name: String) = prefs(context).contains(name)

    fun remove(context: Context, name: String) {
        prefs(context).edit().remove(name).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(SECRET_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(SECRET_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
