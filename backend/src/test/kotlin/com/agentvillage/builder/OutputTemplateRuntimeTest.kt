package com.agentvillage.builder

import com.agentvillage.builder.application.WorkflowNodeCatalog
import com.agentvillage.common.exception.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OutputTemplateRuntimeTest {
    private val slack = WorkflowNodeCatalog().require("slack.send.mock")

    @Test
    fun `preview and execute use the same registered renderer path`() {
        val config = mapOf("channel" to "#market-report", "rendererKey" to "slack.market-news.v1")
        val input = mapOf("result" to "구조화된 시장 보고서")

        assertThat(slack.simulate(config, input).output).isEqualTo(slack.execute(config, input).output)
        assertThat(slack.simulate(config, input).output).containsEntry("externalCallPerformed", false)
    }

    @Test
    fun `unregistered renderer is rejected instead of silently changing output`() {
        assertThatThrownBy {
            slack.simulate(mapOf("channel" to "#market-report", "rendererKey" to "llm-free-form"), mapOf("result" to "보고서"))
        }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("등록되지 않은 출력 렌더러")
    }
}
