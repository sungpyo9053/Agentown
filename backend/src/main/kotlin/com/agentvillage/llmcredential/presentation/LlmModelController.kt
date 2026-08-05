package com.agentvillage.llmcredential.presentation

import com.agentvillage.llmcredential.application.SupportedModel
import com.agentvillage.llmcredential.application.SupportedModelCatalog
import com.agentvillage.llmcredential.domain.LlmProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/llm-models")
class LlmModelController(private val catalog: SupportedModelCatalog) {
    @GetMapping
    fun list(@RequestParam provider: LlmProvider): List<SupportedModel> = catalog.list(provider)
}
