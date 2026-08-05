package com.agentvillage.llmcredential

import com.agentvillage.llmcredential.infrastructure.AesGcmSecretEncryptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class AesGcmSecretEncryptorTest {
    private val key = ByteArray(32) { it.toByte() }
    private val encryptor = AesGcmSecretEncryptor("v1", mapOf("v1" to key))

    @Test
    fun `encrypts with unique nonce and authenticates cipher text`() {
        val first = encryptor.encrypt("sk-secret-value-1234".toCharArray())
        val second = encryptor.encrypt("sk-secret-value-1234".toCharArray())

        assertThat(first.cipherText).isNotEqualTo(second.cipherText)
        val decrypted = encryptor.decrypt(first)
        assertThat(decrypted.concatToString()).isEqualTo("sk-secret-value-1234")
        decrypted.fill('\u0000')

        val payload = Base64.getDecoder().decode(first.cipherText).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThatThrownBy {
            encryptor.decrypt(first.copy(cipherText = Base64.getEncoder().encodeToString(payload)))
        }.isInstanceOf(SecurityException::class.java)
    }
}

