package com.agentvillage.llmcredential.application

data class EncryptedSecret(val cipherText: String, val keyVersion: String)

interface SecretEncryptor {
    fun encrypt(plainText: CharArray): EncryptedSecret
    fun decrypt(secret: EncryptedSecret): CharArray
}

