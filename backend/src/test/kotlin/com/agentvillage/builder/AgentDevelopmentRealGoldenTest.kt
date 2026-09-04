package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "REAL_AGENT_DEVELOPMENT_GOLDEN", matches = "true")
class AgentDevelopmentRealGoldenTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `real model preserves agent request without injecting automation flow`() {
        val command = System.getenv("REAL_CODEX_COMMAND") ?: requireNotNull(findCodex())
        val home = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val runner = CodexCliRunner(command, 180, home)
        require(runner.hasSharedAuth())
        val model = CodexCliMetaAgentModel(mock<CredentialDirectory>(), runner, mapper, System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna")
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val request = "업로드한 계약서에서 위험 조항을 찾고 조항별 근거와 수정 제안을 설명하는 AI 에이전트를 만들어줘."
        val bundle = pipeline.generateDesign(
            context(),
            agentDevelopmentPrompt(request),
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
            userInstruction = request,
        )

        assertThat(bundle.clarificationQuestions).isEmpty()
        assertThat(bundle.agentDefinitions).isNotEmpty
        assertThat(bundle.guideDefinitions).isNotEmpty
        assertThat(bundle.requirement.objective).containsAnyOf("계약", "조항", "위험")
        val plan = requireNotNull(bundle.proposal.graphPlan)
        val nodeTypes = plan.nodes.map { it.nodeType }
        assertThat(nodeTypes).doesNotContain("schedule.trigger", "news.search.mock", "human.approval", "slack.send.mock", "slack.reply.mock", "notion.search.mock", "notion.read_page.mock", "email.send.mock")
        assertThat(bundle.agentDefinitions.joinToString(" ") { "${it.name} ${it.role}" }).containsAnyOf("계약", "조항", "위험")
        val design = requireNotNull(bundle.proposal.agentDesign)
        assertThat(design.simulationScenarios.single().input.values.joinToString(" ")).containsAnyOf("계약", "조항", "위험")
        assertThat(design.simulationScenarios.single().input.values.joinToString(" ")).doesNotContain("다음 요청은 업무 자동화 배치가 아니라")
        assertThat(design.retryPolicy.retryableErrors).isEmpty()
        assertThat(bundle.proposal.integrations).allMatch { it.contains("없음") }
        assertThat(bundle.agentDefinitions).allMatch {
            it.toolKeys.isEmpty() && it.connectorKeys.isEmpty() && !it.approvalPolicy.required
        }
        val executableSurface = mapper.writeValueAsString(mapOf(
            "requirementObjective" to bundle.requirement.objective,
            "requirementInputs" to bundle.requirement.inputs,
            "requirementOutputs" to bundle.requirement.outputs,
            "proposalName" to bundle.proposal.name,
            "proposalSummary" to bundle.proposal.summary,
            "capabilities" to bundle.proposal.capabilities,
            "integrations" to bundle.proposal.integrations,
            "approvalPoints" to bundle.proposal.approvalPoints,
            "agents" to bundle.agentDefinitions.map {
                mapOf(
                    "key" to it.key,
                    "name" to it.name,
                    "role" to it.role,
                    "behaviorRules" to it.behaviorRules,
                    "toolKeys" to it.toolKeys,
                    "connectorKeys" to it.connectorKeys,
                    "approvalRequired" to it.approvalPolicy.required,
                )
            },
            "guides" to bundle.guideDefinitions,
            "graph" to plan,
            "workflow" to design.workflow,
            "simulationScenarios" to design.simulationScenarios,
        )).lowercase()
        // Safety rules may name forbidden connectors while explicitly prohibiting them. Only the
        // positive/executable surface is checked for unwanted capabilities and stale examples.
        assertThat(executableSurface).doesNotContain(
            "환불", "faq", "slack", "notion", "schedule.trigger", "news.search", "email.send",
            "human.approval", "예약 실행", "mock_connector",
        )

        val target = Path.of("build/reports/agent-development-real-golden.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), mapOf(
            "mode" to "real-codex-cli",
            "model" to model.modelName,
            "request" to request,
            "requirement" to bundle.requirement,
            "proposal" to bundle.proposal,
            "agents" to bundle.agentDefinitions,
            "guides" to bundle.guideDefinitions,
            "nodeTypes" to nodeTypes,
            "agentDesign" to design,
            "forbiddenContentChecked" to listOf("환불", "FAQ", "Slack", "Notion", "schedule.trigger", "news.search", "email.send", "human.approval", "예약 실행", "MOCK_CONNECTOR"),
        ))
    }

    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun findCodex() = listOf(Path.of(System.getProperty("user.home"), ".local", "bin", "codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex")).firstOrNull(Files::isExecutable)?.toString()
}
