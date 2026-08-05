package com.agentvillage.llmcredential.infrastructure

import com.agentvillage.llmcredential.application.SecretEncryptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LlmCredentialConfiguration {
    @Bean
    fun secretEncryptor(
        @Value("\${llm.encryption.current-version}") currentVersion: String,
        @Value("\${llm.encryption.master-keys}") masterKeys: String,
    ): SecretEncryptor = AesGcmSecretEncryptor.fromConfiguration(currentVersion, masterKeys)
}

