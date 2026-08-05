package com.agentvillage.llmcredential.infrastructure

import com.agentvillage.llmcredential.application.EncryptedSecret
import com.agentvillage.llmcredential.application.SecretEncryptor
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmSecretEncryptor(
    private val currentVersion: String,
    private val keys: Map<String, ByteArray>,
    private val secureRandom: SecureRandom = SecureRandom(),
) : SecretEncryptor {
    init {
        require(keys.containsKey(currentVersion)) { "Current LLM encryption key version is missing" }
        require(keys.values.all { it.size == 32 }) { "LLM encryption keys must be 256 bits" }
    }

    override fun encrypt(plainText: CharArray): EncryptedSecret {
        val plainBytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(plainText)).toByteArray()
        val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.getValue(currentVersion), "AES"), GCMParameterSpec(TAG_BITS, nonce))
            val encrypted = cipher.doFinal(plainBytes)
            val payload = ByteBuffer.allocate(nonce.size + encrypted.size).put(nonce).put(encrypted).array()
            EncryptedSecret(Base64.getEncoder().encodeToString(payload), currentVersion)
        } finally {
            plainBytes.fill(0)
        }
    }

    override fun decrypt(secret: EncryptedSecret): CharArray {
        val key = keys[secret.keyVersion] ?: throw IllegalArgumentException("Unknown encryption key version")
        val payload = Base64.getDecoder().decode(secret.cipherText)
        require(payload.size > NONCE_SIZE + 16) { "Invalid encrypted secret" }
        val nonce = payload.copyOfRange(0, NONCE_SIZE)
        val cipherText = payload.copyOfRange(NONCE_SIZE, payload.size)
        val decrypted = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(cipherText)
        } catch (exception: AEADBadTagException) {
            throw SecurityException("Encrypted secret authentication failed", exception)
        } finally {
            cipherText.fill(0)
        }
        return try {
            StandardCharsets.UTF_8.decode(ByteBuffer.wrap(decrypted)).let { chars ->
                CharArray(chars.remaining()).also { chars.get(it) }
            }
        } finally {
            decrypted.fill(0)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128

        fun fromConfiguration(currentVersion: String, configuredKeys: String): AesGcmSecretEncryptor {
            val keys = configuredKeys.split(',').filter(String::isNotBlank).associate { entry ->
                val (version, base64Key) = entry.trim().split(':', limit = 2)
                version to Base64.getDecoder().decode(base64Key)
            }
            return AesGcmSecretEncryptor(currentVersion, keys)
        }
    }
}

private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also(::get)

