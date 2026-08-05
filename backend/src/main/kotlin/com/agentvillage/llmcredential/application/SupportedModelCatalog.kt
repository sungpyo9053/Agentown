package com.agentvillage.llmcredential.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.llmcredential.domain.LlmProvider
import org.springframework.stereotype.Component

data class SupportedModel(val id: String, val displayName: String)

@Component
class SupportedModelCatalog {
    private val models = mapOf(
        LlmProvider.OPENAI to listOf(
            SupportedModel("gpt-5", "GPT-5"),
            SupportedModel("gpt-5-mini", "GPT-5 mini"),
            SupportedModel("gpt-4.1", "GPT-4.1"),
            SupportedModel("gpt-4.1-mini", "GPT-4.1 mini"),
            SupportedModel("gpt-4o-mini", "GPT-4o mini"),
        ),
        LlmProvider.ANTHROPIC to listOf(
            SupportedModel("claude-opus-4-1", "Claude Opus 4.1"),
            SupportedModel("claude-sonnet-4-0", "Claude Sonnet 4"),
            SupportedModel("claude-3-5-haiku-latest", "Claude 3.5 Haiku"),
        ),
        LlmProvider.GOOGLE to listOf(
            SupportedModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
            SupportedModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
            SupportedModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite"),
        ),
    )

    fun list(provider: LlmProvider): List<SupportedModel> = models.getValue(provider)

    fun requireSupported(provider: LlmProvider, model: String) {
        if (models.getValue(provider).none { it.id == model }) {
            throw BadRequestException("LLM_MODEL_NOT_SUPPORTED", "${provider}에서 지원하지 않는 모델입니다.")
        }
    }
}
