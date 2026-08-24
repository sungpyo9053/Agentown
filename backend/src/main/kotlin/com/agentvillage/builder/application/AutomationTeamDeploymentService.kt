package com.agentvillage.builder.application

import com.agentvillage.agent.application.AgentDefinitionService
import com.agentvillage.agent.application.AgentService
import com.agentvillage.agent.application.GenerateDefinitionCommand
import com.agentvillage.agent.application.SaveAgentCommand
import com.agentvillage.builder.domain.AgentDefinition
import com.agentvillage.builder.domain.BuilderAutomationTeam
import com.agentvillage.builder.domain.BuilderAutomationTeamMember
import com.agentvillage.builder.domain.FieldDefinition
import com.agentvillage.builder.domain.GuideDefinition
import com.agentvillage.builder.infrastructure.BuilderAutomationTeamMemberRepository
import com.agentvillage.builder.infrastructure.BuilderAutomationTeamRepository
import com.agentvillage.common.domain.Visibility
import com.agentvillage.llmcredential.domain.LlmProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class AutomationEmployeeView(
    val agentId: UUID, val agentKey: String, val name: String, val role: String,
    val department: String, val sequenceNo: Int, val agentMarkdown: String, val guideMarkdown: String,
)

data class AutomationTeamView(
    val teamId: UUID, val workflowId: UUID, val workflowVersionId: UUID, val versionNo: Int,
    val category: String, val teamName: String, val workflowName: String,
    val employees: List<AutomationEmployeeView>,
)

@Service
class AutomationTeamDeploymentService(
    private val teams: BuilderAutomationTeamRepository,
    private val members: BuilderAutomationTeamMemberRepository,
    private val agentService: AgentService,
    private val definitionService: AgentDefinitionService,
) {
    @Transactional
    fun deploy(
        ownerId: UUID, workspaceId: UUID, workflowId: UUID, workflowVersionId: UUID,
        workflowName: String, objective: String, agents: List<AgentDefinition>, guides: List<GuideDefinition>,
    ): BuilderAutomationTeam {
        teams.findByWorkflowVersionId(workflowVersionId)?.let { return it }
        val teamName = teamName(workflowName, objective)
        val team = teams.save(BuilderAutomationTeam(
            workspaceId = workspaceId, workflowId = workflowId,
            workflowVersionId = workflowVersionId, name = teamName,
        ))
        val guideMarkdown = guides.joinToString("\n\n") { it.toMarkdown() }
        agents.forEachIndexed { index, designed ->
            val agentMarkdown = designed.toMarkdown()
            val employee = agentService.create(ownerId, SaveAgentCommand(
                name = designed.name.take(40), role = designed.role.take(100),
                personality = "사용자가 승인한 Workflow Version의 계약을 따르는 업무 자동화 팀원",
                department = teamName, characterKey = characterKey(designed),
                systemPrompt = agentMarkdown, script = agentMarkdown, guide = guideMarkdown,
                modelProvider = LlmProvider.OPENAI, modelName = "gpt-5.6-luna", credentialId = null,
                temperature = BigDecimal("0.20"), maxOutputTokens = 4_096, timeoutSeconds = 120,
                providerOptions = emptyMap(), visibility = Visibility.PRIVATE,
            ))
            definitionService.generate(employee.id, ownerId, GenerateDefinitionCommand(
                taskDescription = designed.role,
                desiredOutput = designed.outputSchema.joinToString("; ") { "${it.name}: ${it.description}" },
                prohibitions = designed.forbiddenRules.joinToString("\n"),
                inputSchema = jsonSchema(designed.inputSchema), outputSchema = jsonSchema(designed.outputSchema),
                requiredEvidence = designed.evidenceRequirements.joinToString("\n"), outputStyle = guideMarkdown,
                rewriteCriteria = "입출력 스키마, 행동 규칙 또는 Guide Definition을 만족하지 못한 경우",
                approvalCriteria = "Workflow Graph의 다음 단계 입력 계약을 만족하고 금지 규칙 위반이 없는 경우",
            ))
            members.save(BuilderAutomationTeamMember(
                teamId = team.id, agentId = employee.id, agentKey = designed.key, sequenceNo = index + 1,
                agentMarkdown = agentMarkdown, guideMarkdown = guideMarkdown,
            ))
        }
        return team
    }

    @Transactional(readOnly = true)
    fun list(ownerId: UUID, workspaceId: UUID, versionNumbers: Map<UUID, Int>, workflowNames: Map<UUID, String>): List<AutomationTeamView> =
        teams.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).map { team ->
            val employees = members.findAllByTeamIdOrderBySequenceNo(team.id).map { member ->
                val agent = agentService.getOwned(member.agentId, ownerId)
                AutomationEmployeeView(
                    agent.id, member.agentKey, agent.name, agent.role, agent.department ?: team.name,
                    member.sequenceNo, member.agentMarkdown, member.guideMarkdown,
                )
            }
            AutomationTeamView(
                team.id, team.workflowId, team.workflowVersionId, versionNumbers[team.workflowVersionId] ?: 0,
                team.category, team.name, workflowNames[team.workflowId].orEmpty(), employees,
            )
        }

    private fun teamName(workflowName: String, objective: String): String {
        val source = "$workflowName $objective".lowercase()
        return when {
            listOf("글쓰기", "블로그", "콘텐츠", "원고").any(source::contains) -> "글쓰기 자동화 팀"
            source.contains("뉴스") && source.contains("보고서") -> "뉴스 분석 보고서 자동화 팀"
            else -> "${workflowName.removeSuffix(" 팀").take(56)} 팀"
        }.take(60)
    }

    private fun characterKey(agent: AgentDefinition): String {
        val value = "${agent.key} ${agent.name} ${agent.role}".lowercase()
        return when {
            listOf("writer", "작성", "글").any(value::contains) -> "writer"
            listOf("review", "editor", "검토", "편집", "팩트").any(value::contains) -> "reviewer"
            listOf("design", "plan", "기획", "설계").any(value::contains) -> "designer"
            listOf("develop", "개발").any(value::contains) -> "developer"
            else -> "manager"
        }
    }

    private fun jsonSchema(fields: List<FieldDefinition>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to fields.associate { it.name to mapOf("type" to it.type, "description" to it.description) },
        "required" to fields.filter { it.required }.map { it.name }, "additionalProperties" to false,
    )
}
