package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.application.BuilderGenerationService
import com.agentvillage.agent.application.AgentService
import com.agentvillage.builder.domain.BuilderGenerationJob
import com.agentvillage.builder.domain.BuilderGenerationStage
import com.agentvillage.builder.domain.BuilderGenerationStatus
import com.agentvillage.builder.domain.BuilderRunStatus
import com.agentvillage.builder.domain.BuilderConversationPurpose
import com.agentvillage.builder.domain.AgentDesignStatus
import com.agentvillage.builder.domain.DesignNodeKind
import com.agentvillage.builder.domain.WorkflowStatus
import com.agentvillage.builder.infrastructure.BuilderGenerationJobRepository
import com.agentvillage.builder.infrastructure.BuilderApprovalRepository
import com.agentvillage.builder.infrastructure.BuilderRequirementRepository
import com.agentvillage.builder.infrastructure.BuilderWorkflowRepository
import com.agentvillage.builder.infrastructure.BuilderRunRepository
import com.agentvillage.builder.infrastructure.BuilderUsageRecordRepository
import com.agentvillage.builder.infrastructure.BuilderWorkflowVersionRepository
import com.agentvillage.builder.infrastructure.HarnessTemplateRepository
import com.agentvillage.builder.infrastructure.HarnessTemplateVersionRepository
import com.agentvillage.builder.domain.HarnessTemplateVersionState
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
class BuilderMvpIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var service: BuilderService
    @Autowired lateinit var generation: BuilderGenerationService
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var requirements: BuilderRequirementRepository
    @Autowired lateinit var agents: AgentService
    @Autowired lateinit var workflowVersions: BuilderWorkflowVersionRepository
    @Autowired lateinit var builderRuns: BuilderRunRepository
    @Autowired lateinit var usageRecords: BuilderUsageRecordRepository
    @Autowired lateinit var generationJobs: BuilderGenerationJobRepository
    @Autowired lateinit var builderApprovals: BuilderApprovalRepository
    @Autowired lateinit var outputTemplates: HarnessTemplateRepository
    @Autowired lateinit var outputTemplateVersions: HarnessTemplateVersionRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var workflows: BuilderWorkflowRepository
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `latest recoverable generation job is newest conversation scoped owner safe view`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("generation-owner-$suffix@example.com", "password123", "generation_owner_$suffix", "생성 복구 소유자"))
        val stranger = identities.register(RegisterUserCommand("generation-stranger-$suffix@example.com", "password123", "generation_stranger_$suffix", "생성 복구 타인"))
        val first = service.createConversation(owner.id, "generation-first-$suffix")
        val second = service.createConversation(owner.id, "generation-second-$suffix")
        val empty = service.createConversation(owner.id, "generation-empty-$suffix")
        val strangerConversation = service.createConversation(stranger.id, "generation-stranger-conversation-$suffix")

        fun persist(snapshot: com.agentvillage.builder.application.BuilderSnapshot, status: BuilderGenerationStatus, key: String, instruction: String, createdAt: Instant): BuilderGenerationJob {
            val job = generationJobs.saveAndFlush(BuilderGenerationJob(
                workspaceId = snapshot.workspaceId,
                conversationId = snapshot.conversationId,
                workflowId = snapshot.workflowId,
                instruction = instruction,
                status = status,
                stage = when (status) {
                    BuilderGenerationStatus.RUNNING -> BuilderGenerationStage.CODEX_ANALYZING
                    BuilderGenerationStatus.FAILED -> BuilderGenerationStage.FAILED
                    BuilderGenerationStatus.SUCCEEDED -> BuilderGenerationStage.COMPLETED
                    BuilderGenerationStatus.CANCELLED -> BuilderGenerationStage.CANCELLED
                    BuilderGenerationStatus.QUEUED -> BuilderGenerationStage.REQUEST_ACCEPTED
                },
                idempotencyKey = key,
                errorCode = if (status == BuilderGenerationStatus.FAILED) "SAFE_GENERATION_FAILURE" else null,
                errorMessage = if (status == BuilderGenerationStatus.FAILED) "안전한 실패 안내" else null,
            ))
            jdbc.update("update builder_generation_jobs set created_at = ? where id = ?", Timestamp.from(createdAt), job.id)
            return job
        }

        val base = Instant.parse("2026-08-30T05:00:00Z")
        persist(first, BuilderGenerationStatus.QUEUED, "old-queued-$suffix", "old owner instruction", base)
        val newest = persist(first, BuilderGenerationStatus.FAILED, "new-failed-$suffix", "private persisted instruction", base.plusSeconds(10))
        persist(first, BuilderGenerationStatus.SUCCEEDED, "newer-succeeded-$suffix", "ignored completed instruction", base.plusSeconds(20))
        persist(first, BuilderGenerationStatus.CANCELLED, "newest-cancelled-$suffix", "ignored cancelled instruction", base.plusSeconds(30))
        persist(second, BuilderGenerationStatus.RUNNING, "other-conversation-$suffix", "other conversation secret", base.plusSeconds(40))
        persist(strangerConversation, BuilderGenerationStatus.RUNNING, "stranger-job-$suffix", "stranger workspace secret", base.plusSeconds(50))

        assertThat(generation.latestRecoverable(owner.id, first.conversationId)?.id).isEqualTo(newest.id)
        assertThat(generation.latestRecoverable(owner.id, empty.conversationId)).isNull()

        mvc.perform(get("/api/builder/conversations/${first.conversationId}/generation-jobs/latest-recoverable").with(user(principal(owner.id, owner.email))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(newest.id.toString()))
            .andExpect(jsonPath("$.conversationId").value(first.conversationId.toString()))
            .andExpect(jsonPath("$.workflowId").value(first.workflowId.toString()))
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errorCode").value("SAFE_GENERATION_FAILURE"))
            .andExpect(jsonPath("$.errorMessage").value("안전한 실패 안내"))
            .andExpect(jsonPath("$.instruction").doesNotExist())
            .andExpect(jsonPath("$.idempotencyKey").doesNotExist())

        mvc.perform(get("/api/builder/conversations/${empty.conversationId}/generation-jobs/latest-recoverable").with(user(principal(owner.id, owner.email))))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/builder/conversations/${strangerConversation.conversationId}/generation-jobs/latest-recoverable").with(user(principal(owner.id, owner.email))))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `cross purpose idempotency keys are rejected through both HTTP controller families`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("purpose-http-$suffix@example.com", "password123", "purpose_http_$suffix", "흐름 격리 검증"))
        val principal = AuthenticatedUser(owner.id, owner.email, "unused", true)

        mvc.perform(post("/api/builder/conversations").with(user(principal)).with(csrf()).header("Idempotency-Key", "session-auto-$suffix"))
            .andExpect(status().isOk)
        mvc.perform(post("/api/agent-development/sessions").with(user(principal)).with(csrf()).header("Idempotency-Key", "session-auto-$suffix"))
            .andExpect(status().isConflict)
        mvc.perform(post("/api/agent-development/sessions").with(user(principal)).with(csrf()).header("Idempotency-Key", "session-agent-$suffix"))
            .andExpect(status().isOk)
        mvc.perform(post("/api/builder/conversations").with(user(principal)).with(csrf()).header("Idempotency-Key", "session-agent-$suffix"))
            .andExpect(status().isConflict)

        var automation = service.createConversation(owner.id, "message-auto-conversation-$suffix")
        var agent = service.createConversation(owner.id, "message-agent-conversation-$suffix", BuilderConversationPurpose.AGENT_DEVELOPMENT)
        generationJobs.save(BuilderGenerationJob(workspaceId = automation.workspaceId, conversationId = automation.conversationId, workflowId = automation.workflowId, instruction = "자동화", idempotencyKey = "message-auto-$suffix"))
        mvc.perform(post("/api/agent-development/sessions/{id}/messages", agent.conversationId).with(user(principal)).with(csrf())
            .header("Idempotency-Key", "message-auto-$suffix").contentType(MediaType.APPLICATION_JSON)
            .content("""{"content":"계약서를 검토하는 에이전트"}"""))
            .andExpect(status().isConflict)
        generationJobs.save(BuilderGenerationJob(workspaceId = agent.workspaceId, conversationId = agent.conversationId, workflowId = agent.workflowId, instruction = "에이전트", idempotencyKey = "message-agent-$suffix"))
        mvc.perform(post("/api/builder/conversations/{id}/messages", automation.conversationId).with(user(principal)).with(csrf())
            .header("Idempotency-Key", "message-agent-$suffix").contentType(MediaType.APPLICATION_JSON)
            .content("""{"content":"수동 입력을 화면에 정리하는 자동화"}"""))
            .andExpect(status().isConflict)

        workflows.findById(automation.workflowId).orElseThrow().also { it.status = WorkflowStatus.WAITING_DESIGN_APPROVAL; workflows.save(it) }
        workflows.findById(agent.workflowId).orElseThrow().also { it.status = WorkflowStatus.WAITING_DESIGN_APPROVAL; workflows.save(it) }
        service.decideDesign(owner.id, automation.workflowId, false, "decision-auto-$suffix")
        mvc.perform(post("/api/agent-development/sessions/{id}/design-decision", agent.conversationId).with(user(principal)).with(csrf())
            .header("Idempotency-Key", "decision-auto-$suffix").contentType(MediaType.APPLICATION_JSON).content("""{"approve":false}"""))
            .andExpect(status().isConflict)
        service.decideDesign(owner.id, agent.workflowId, false, "decision-agent-$suffix")
        mvc.perform(post("/api/builder/workflows/{id}/design-decision", automation.workflowId).with(user(principal)).with(csrf())
            .header("Idempotency-Key", "decision-agent-$suffix").contentType(MediaType.APPLICATION_JSON).content("""{"approve":false}"""))
            .andExpect(status().isConflict)
    }

    @Test
    fun `agent development sessions stay separate and use chat defaults instead of automation questions`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("agent-dev-$suffix@example.com", "password123", "agent_dev_$suffix", "에이전트 개발 검증"))
        val automation = service.createConversation(owner.id, "automation-conversation-$suffix")
        var agent = service.createConversation(owner.id, "agent-conversation-$suffix", BuilderConversationPurpose.AGENT_DEVELOPMENT)

        assertThat(service.listConversations(owner.id).map { it.conversationId }).containsExactly(automation.conversationId)
        assertThat(service.listConversations(owner.id, BuilderConversationPurpose.AGENT_DEVELOPMENT).map { it.conversationId }).containsExactly(agent.conversationId)
        assertThatThrownBy { service.requireConversationPurpose(owner.id, automation.conversationId, BuilderConversationPurpose.AGENT_DEVELOPMENT) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.createConversation(owner.id, "automation-conversation-$suffix", BuilderConversationPurpose.AGENT_DEVELOPMENT) }
            .isInstanceOf(ConflictException::class.java)

        val principal = AuthenticatedUser(owner.id, owner.email, "unused", true)
        mvc.perform(get("/api/builder/conversations/{id}", agent.conversationId).with(user(principal))).andExpect(status().isNotFound)
        mvc.perform(get("/api/agent-development/sessions/{id}", automation.conversationId).with(user(principal))).andExpect(status().isNotFound)
        mvc.perform(get("/api/builder/workflows/{id}/graph", agent.workflowId).with(user(principal))).andExpect(status().isNotFound)

        val automationJob = generationJobs.save(BuilderGenerationJob(workspaceId = automation.workspaceId, conversationId = automation.conversationId, workflowId = automation.workflowId, instruction = "자동화", idempotencyKey = "automation-job-$suffix"))
        val agentJob = generationJobs.save(BuilderGenerationJob(workspaceId = agent.workspaceId, conversationId = agent.conversationId, workflowId = agent.workflowId, instruction = "에이전트", idempotencyKey = "agent-job-$suffix"))
        mvc.perform(get("/api/builder/generation-jobs/{id}", agentJob.id).with(user(principal))).andExpect(status().isNotFound)
        mvc.perform(get("/api/agent-development/jobs/{id}", automationJob.id).with(user(principal))).andExpect(status().isNotFound)

        agent = service.sendMessage(owner.id, agent.conversationId, "날씨를 분석해 옷차림을 추천하는 에이전트를 만들어줘.", "agent-message-$suffix")
        assertThat(agent.clarificationQuestions.map { it.field }).doesNotContain("inbound", "knowledgeSource", "approvalPolicy", "destination")
        assertThat(agent.messages.last { it.role == "USER" }.content).isEqualTo("날씨를 분석해 옷차림을 추천하는 에이전트를 만들어줘.")

        agent = service.decideDesign(owner.id, agent.workflowId, false, "agent-reject-$suffix")
        assertThat(agent.status).isEqualTo(WorkflowStatus.DRAFT)
        agent = service.sendMessage(owner.id, agent.conversationId, "추천 이유에 체감온도와 강수 확률을 반드시 포함해줘.", "agent-revise-$suffix")
        assertThat(agent.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(agent.messages.last { it.role == "USER" }.content).contains("체감온도", "강수 확률")

        agent = service.decideDesign(owner.id, agent.workflowId, true, "agent-approve-$suffix")
        val definition = agent.agentDefinitions.first()
        val updateBody = mapper.writeValueAsString(mapOf(
            "name" to "체감 날씨 코치", "role" to definition.role,
            "behaviorRules" to definition.behaviorRules, "forbiddenRules" to definition.forbiddenRules,
            "evidenceRequirements" to definition.evidenceRequirements, "toolKeys" to definition.toolKeys,
            "skillKeys" to definition.skillKeys, "memoryScope" to "NONE",
        ))
        val updated = mvc.perform(put("/api/agent-development/sessions/{id}/agents/{key}", agent.conversationId, definition.key)
            .with(user(principal)).with(csrf()).header("Idempotency-Key", "agent-config-$suffix")
            .contentType(MediaType.APPLICATION_JSON).content(updateBody))
            .andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(updated).contains("체감 날씨 코치", "NONE", "속성 수정")

        mvc.perform(put("/api/agent-development/sessions/{id}/agents/{key}", agent.conversationId, definition.key)
            .with(user(principal)).with(csrf()).header("Idempotency-Key", "agent-memory-$suffix")
            .contentType(MediaType.APPLICATION_JSON).content(updateBody.replace("\"NONE\"", "\"CONVERSATION\"")))
            .andExpect(status().isBadRequest)

        mvc.perform(post("/api/agent-development/sessions/{id}/simulations", agent.conversationId)
            .with(user(principal)).with(csrf()).header("Idempotency-Key", "agent-simulation-$suffix")
            .contentType(MediaType.APPLICATION_JSON).content("""{"input":{"text":"서울 12도, 비 올 확률 70%"}}"""))
            .andExpect(status().isOk)

        val latest = service.snapshot(owner.id, agent.conversationId)
        val previousVersion = latest.versions.last().id
        val restoredJson = mvc.perform(post("/api/agent-development/sessions/{id}/versions/{versionId}/restore", agent.conversationId, previousVersion)
            .with(user(principal)).with(csrf()).header("Idempotency-Key", "agent-restore-$suffix"))
            .andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        val restoredAgent = mapper.readTree(restoredJson)["agentDefinitions"].first()
        assertThat(restoredAgent["name"].asText()).isEqualTo(definition.name)
        assertThat(restoredAgent["memoryScope"].asText()).isEqualTo(definition.memoryScope)
    }

    @Test
    fun `approved writing harness imports the minimum persisted employee into writing automation team`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("writing-team-$suffix@example.com", "password123", "writing_team_$suffix", "글쓰기 팀 검증"))
        var snapshot = service.createConversation(owner.id, "writing-team-conversation-$suffix")
        snapshot = service.sendMessage(
            owner.id, snapshot.conversationId,
            "글쓰기 자동화를 수동으로 시작하고 사용자가 제공한 주제와 원문만 사용해 일반 독자용 한국어 블로그 초안을 작성한다. 콘텐츠 담당자 승인 후 화면에 표시한다.",
            "writing-team-message-$suffix",
        )
        assertThat(snapshot.agentDefinitions.map { it.key }).containsExactly("content-writer")
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "writing-team-design-$suffix")
        val agentPackage = service.harnessPackage(owner.id, snapshot.workflowId)
        assertThat(agentPackage.keys).contains(
            "agent.yaml", "workflow.yaml", "prompts/system.md", "prompts/reviewer.md",
            "schemas/input.schema.json", "schemas/output.schema.json", "tools/tools.yaml", "mcp.json",
            "examples/sample-input.json", ".env.example", "runners/python/runner.py", "runtime-targets.json", "README.md",
            "AGENTS.md", "CODEX.md", "workflow.json", "design-bundle.json", "manifest.json",
            "agents/content-writer.md", "schemas/final-output.schema.json", "policies/permissions.json", "policies/ai-budget.json",
        )
        assertThat(agentPackage.getValue("manifest.json")).contains("agentown-agent-package/v1", "python-local", "generic-package")
        assertThat(agentPackage.getValue("runners/python/runner.py")).contains("AgentownTFrameXAdapter").doesNotContain("Fixed Agentown mock runner")
        var run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("text" to "검증용 주제와 참고 원문"), "writing-team-run-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)
        run = service.decideExecution(owner.id, run.id, true, "writing-team-execution-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        snapshot = service.activate(owner.id, snapshot.workflowId, "writing-team-activation-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.ACTIVE)

        val team = service.activeAutomationTeams(owner.id).single()
        assertThat(team.category).isEqualTo("업무 자동화")
        assertThat(team.teamName).isEqualTo("글쓰기 자동화 팀")
        assertThat(team.employees.map { it.agentKey }).containsExactly("content-writer")
        assertThat(team.employees.map { it.name }).containsExactly("콘텐츠 작성자")
        assertThat(agents.list(owner.id).filter { it.department == "글쓰기 자동화 팀" }).hasSize(1)
    }

    @Test
    fun `natural language design compile version patch and simulation approval resume persist end to end`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("builder-$suffix@example.com", "password123", "builder_$suffix", "Builder 검증"))
        val stranger = identities.register(RegisterUserCommand("builder-other-$suffix@example.com", "password123", "builder_other_$suffix", "다른 워크스페이스"))

        var snapshot = service.createConversation(owner.id, "conversation-$suffix")
        val conversationId = snapshot.conversationId
        val workflowId = snapshot.workflowId
        snapshot = service.sendMessage(owner.id, conversationId, "고객 문의 답변하는 일을 자동화하고 싶어요.", "message-vague-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("inbound", "knowledgeSource", "approvalPolicy", "destination")
        assertThat(snapshot.proposal).isNull()
        assertThat(snapshot.graph).isNull()

        snapshot = service.sendMessage(owner.id, conversationId, "Slack #customer-support 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 원래 Slack 스레드로 전송한다.", "message-details-$suffix")
        assertThat(snapshot.conversationId).isEqualTo(conversationId)
        assertThat(snapshot.workflowId).isEqualTo(workflowId)
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.requirement?.steps).hasSize(8)
        assertThat(snapshot.proposal).isNotNull
        assertThat(snapshot.proposal?.agentDesign?.status).isEqualTo(AgentDesignStatus.READY_FOR_REVIEW)
        assertThat(snapshot.proposal?.agentDesign?.executionReadiness).isEqualTo(AgentDesignStatus.EXECUTION_NOT_CONFIGURED)
        assertThat(snapshot.proposal?.agentDesign?.review?.passed).isTrue()
        assertThat(snapshot.agentDefinitions.map { it.key }).containsExactly("support-answer-writer")
        assertThat(snapshot.agentDefinitions).hasSize(1)
        assertThat(snapshot.agentDefinitions.joinToString(" ") { "${it.name} ${it.role}" })
            .doesNotContain("승인 라우팅", "게시 에이전트", "문의 분류")
        assertThat(snapshot.guideDefinitions.map { it.key }).containsExactly("slack", "notion")
        assertThat(snapshot.graph).isNull()
        val generatedTemplate = outputTemplates.findByTemplateKey(snapshot.proposal!!.templateSelection!!.templateKey)!!
        assertThat(outputTemplateVersions.findByTemplateIdAndVersionNo(generatedTemplate.id, 1)!!.state).isEqualTo(HarnessTemplateVersionState.PREVIEWED)
        val plannedNodeTypes = snapshot.proposal!!.graphPlan!!.nodes.map { it.nodeType }

        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "design-approve-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.READY_TO_SIMULATE)
        assertThat(snapshot.graph?.nodes?.map { it.nodeType }).containsExactlyElementsOf(plannedNodeTypes)
        assertThat(snapshot.validation?.valid).isTrue()
        assertThat(snapshot.currentVersionId).isEqualTo(snapshot.approvedVersionId)
        val pinnedTemplateVersionId = workflowVersions.findById(snapshot.currentVersionId!!).orElseThrow().templateVersionId
        assertThat(pinnedTemplateVersionId).isNotNull()
        assertThat(outputTemplateVersions.findById(pinnedTemplateVersionId!!).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.APPROVED)

        jdbc.update("update builder_workflow_versions set graph_hash = ? where id = ?", "stored-authoritative-hash", snapshot.currentVersionId)
        snapshot = service.snapshot(owner.id, conversationId)
        assertThat(snapshot.validation!!.graphHash).isEqualTo("stored-authoritative-hash")

        val firstVersion = snapshot.currentVersionId!!
        snapshot = service.applyPatch(owner.id, snapshot.workflowId, "Slack 답변 전 담당자 승인을 추가해줘.", firstVersion, snapshot.validation!!.graphHash, "patch-1-$suffix")
        assertThat(snapshot.versions).hasSize(2)
        assertThat(snapshot.currentVersionId).isNotEqualTo(firstVersion)

        var run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "환불은 언제 처리되나요?", "token" to "must-not-persist"), "simulation-1-$suffix")
        assertThat(builderRuns.findById(run.id).orElseThrow().templateVersionId).isEqualTo(pinnedTemplateVersionId)
        assertThat(run.status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)
        assertThat(run.steps.map { it.nodeType }).containsExactly("slack.new_message.mock", "data.normalize", "notion.search.mock", "ai.generate", "quality.check", "human.approval")
        assertThat(run.steps.first().input["token"]).isEqualTo("***")
        assertThat(run.pendingApprovalId).isNotNull

        val duplicate = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "다른 입력"), "simulation-1-$suffix")
        assertThat(duplicate.id).isEqualTo(run.id)
        run = service.decideExecution(owner.id, run.id, true, "execution-approve-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(run.steps.last().nodeType).isEqualTo("workflow.end")
        assertThat(run.output).containsEntry("externalCallPerformed", false)
        assertThat(run.requirementMatched).isTrue()
        assertThat(service.decideExecution(owner.id, run.id, true, "execution-approve-$suffix").id).isEqualTo(run.id)

        snapshot = service.activate(owner.id, workflowId, "activation-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.ACTIVE)
        assertThat(outputTemplateVersions.findById(pinnedTemplateVersionId).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.ACTIVE)
        val teams = service.activeAutomationTeams(owner.id)
        assertThat(teams).hasSize(1)
        assertThat(teams.single().category).isEqualTo("업무 자동화")
        assertThat(teams.single().workflowVersionId).isEqualTo(snapshot.currentVersionId)
        assertThat(teams.single().employees.map { it.agentKey }).containsExactly("support-answer-writer")
        assertThat(teams.single().employees).allSatisfy { employee ->
            assertThat(employee.department).isEqualTo(teams.single().teamName)
            assertThat(employee.agentMarkdown).contains("## Role")
            assertThat(employee.guideMarkdown).isNotBlank()
        }
        assertThat(agents.list(owner.id).filter { it.department == teams.single().teamName }).hasSize(1)
        assertThat(service.activate(owner.id, workflowId, "activation-$suffix").status).isEqualTo(WorkflowStatus.ACTIVE)
        assertThat(service.activeAutomationTeams(owner.id).single().employees).hasSize(1)
        assertThat(service.activeAutomationTeams(stranger.id)).isEmpty()

        val stopped = service.stop(owner.id, workflowId, "workflow-stop-$suffix")
        assertThat(stopped.conversationId).isEqualTo(conversationId)
        assertThat(stopped.workflowId).isEqualTo(workflowId)
        assertThat(stopped.currentVersionId).isEqualTo(snapshot.currentVersionId)
        assertThat(stopped.status).isEqualTo(WorkflowStatus.STOPPED)

        assertThatThrownBy { service.snapshot(stranger.id, snapshot.conversationId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `unsupported Slack deletion returns guidance and preserves approved workflow on idempotent retry`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("unsupported-patch-$suffix@example.com", "password123", "unsupported_patch_$suffix", "지원하지 않는 수정 검증"))
        var snapshot = service.createConversation(owner.id, "unsupported-patch-conversation-$suffix")
        snapshot = service.sendMessage(
            owner.id,
            snapshot.conversationId,
            "Slack 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 Slack 스레드로 전송한다.",
            "unsupported-patch-message-$suffix",
        )
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "unsupported-patch-approve-$suffix")
        assertThat(snapshot.graph!!.nodes.map { it.nodeType }).contains("human.approval", "slack.reply.mock")
        val before = snapshot
        val idempotencyKey = "unsupported-patch-delete-$suffix"
        val body = """{"instruction":"Slack 노드를 삭제해줘.","baseVersionId":"${before.currentVersionId}","expectedGraphHash":"${before.validation!!.graphHash}"}"""

        repeat(2) {
            mvc.perform(
                post("/api/builder/workflows/${before.workflowId}/patches")
                    .with(user(principal(owner.id, owner.email)))
                    .with(csrf())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_GRAPH_PATCH"))
                .andExpect(jsonPath("$.message").value("요청한 변경은 아직 지원하지 않습니다. 현재 가능한 수정은 출력 템플릿 조정, Slack 전송을 이메일로 변경, Slack 답변 전 담당자 승인 추가입니다. 기존 자동화는 변경되지 않았습니다."))
        }

        val after = service.snapshot(owner.id, before.conversationId)
        assertThat(after.status).isEqualTo(before.status)
        assertThat(after.currentVersionId).isEqualTo(before.currentVersionId)
        assertThat(after.approvedVersionId).isEqualTo(before.approvedVersionId)
        assertThat(after.versions).isEqualTo(before.versions)
        assertThat(after.validation!!.graphHash).isEqualTo(before.validation!!.graphHash)
        assertThat(after.graph).isEqualTo(before.graph)
        assertThat(after.proposal).isEqualTo(before.proposal)
        assertThat(after.requirement).isEqualTo(before.requirement)
        assertThat(after.messages).isEqualTo(before.messages)
        assertThat(after.messages.map { it.content }).noneMatch { it.contains("Graph Patch를 검증해 새 버전") }
    }

    @Test
    fun `supported Slack to email replacement still creates a changed workflow version`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("email-patch-$suffix@example.com", "password123", "email_patch_$suffix", "이메일 수정 검증"))
        var snapshot = service.createConversation(owner.id, "email-patch-conversation-$suffix")
        snapshot = service.sendMessage(
            owner.id,
            snapshot.conversationId,
            "Slack 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 Slack 스레드로 전송한다.",
            "email-patch-message-$suffix",
        )
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "email-patch-approve-$suffix")
        val approvedVersionId = snapshot.approvedVersionId
        val approvedGraphHash = snapshot.validation!!.graphHash

        snapshot = service.applyPatch(
            owner.id,
            snapshot.workflowId,
            "Slack 전송을 이메일로 변경해줘.",
            snapshot.currentVersionId!!,
            approvedGraphHash,
            "email-patch-replace-$suffix",
        )

        assertThat(snapshot.versions).hasSize(2)
        assertThat(snapshot.currentVersionId).isNotEqualTo(approvedVersionId)
        assertThat(snapshot.approvedVersionId).isEqualTo(approvedVersionId)
        assertThat(snapshot.validation!!.graphHash).isNotEqualTo(approvedGraphHash)
        assertThat(snapshot.graph!!.nodes.map { it.nodeType }).contains("email.send.mock")
        assertThat(snapshot.graph!!.nodes.map { it.nodeType }).doesNotContain("slack.reply.mock", "slack.send.mock")
        assertThat(snapshot.requirement!!.outputs.joinToString(" ")).contains("이메일").doesNotContain("Slack")
        assertThat(snapshot.messages.last().content).contains("Graph Patch를 검증해 새 버전 2", "Slack 전송을 이메일 Mock 전송으로 변경")
    }

    @Test
    fun `missing approver asks only clarification then produces proposal after answer`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("clarify-$suffix@example.com", "password123", "clarify_$suffix", "질문 검증"))
        var snapshot = service.createConversation(owner.id, "clarify-conversation-$suffix")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Slack 문의를 Notion FAQ로 찾아 답변하고 싶다.", "clarify-message-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.single().field).isEqualTo("approvalPolicy")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "고객지원 팀장이 승인한다.", "clarify-answer-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
    }

    @Test
    fun `rejected automation design accepts cumulative natural language revision for re-review without a version`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("revision-$suffix@example.com", "password123", "revision_$suffix", "설계 수정 검증"))
        var snapshot = service.createConversation(owner.id, "revision-conversation-$suffix")
        val originalInstruction = "사용자가 수동 입력한 텍스트를 카테고리로 분류하고 담당자 승인 후 결과를 화면에 표시합니다."
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, originalInstruction, "revision-initial-$suffix")
        val rejectedProposal = snapshot.proposal
        val rejectedClassification = requireNotNull(rejectedProposal?.graphPlan).nodes.single { it.nodeType == "ai.classify" }

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.currentVersionId).isNull()
        assertThat(snapshot.approvedVersionId).isNull()
        assertThat(snapshot.versions).isEmpty()
        assertThat(usageRecords.findAll().count { it.ownerId == owner.id }).isEqualTo(1)

        snapshot = service.decideDesign(owner.id, snapshot.workflowId, false, "revision-reject-$suffix")

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.DRAFT)
        assertThat(snapshot.proposal).isEqualTo(rejectedProposal)
        assertThat(snapshot.currentVersionId).isNull()
        assertThat(snapshot.approvedVersionId).isNull()
        assertThat(snapshot.versions).isEmpty()
        assertThat(snapshot.messages.last().content).isEqualTo("설계가 반려되었습니다. 수정할 내용을 자연어로 알려 주세요.")
        assertThat(builderApprovals.findAll().filter { it.workflowId == snapshot.workflowId }.single().status)
            .isEqualTo(com.agentvillage.builder.domain.ApprovalStatus.REJECTED)

        val revision = "분석 담당과 작성 담당이 함께 처리하도록 바꿔 주세요."
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, revision, "revision-follow-up-$suffix")

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.requirement?.objective).contains(originalInstruction, revision)
        assertThat(snapshot.requirement?.decisions).contains("입력 유형 선택")
        assertThat(snapshot.requirement?.humanApprovalRequired).isTrue()
        assertThat(snapshot.agentDefinitions.map { it.key }).containsExactly("analyst", "writer")
        val revisedPlan = requireNotNull(snapshot.proposal?.graphPlan)
        assertThat(revisedPlan.nodes.map { it.nodeType }).contains("ai.classify", "human.approval")
        val revisedClassification = revisedPlan.nodes.single { it.nodeType == "ai.classify" }
        assertThat(revisedClassification.config)
            .containsEntry("agentKey", "analyst")
            .containsEntry("categories", rejectedClassification.config["categories"])
        assertThat(revisedPlan.nodes.single { it.config["agentKey"] == "writer" }.nodeType)
            .isEqualTo("ai.generate")
        assertThat(snapshot.proposal!!.capabilities).contains("입력 분류", "담당자 승인")
        assertThat(snapshot.proposal).isNotEqualTo(rejectedProposal)
        assertThat(snapshot.currentVersionId).isNull()
        assertThat(snapshot.approvedVersionId).isNull()
        assertThat(snapshot.versions).isEmpty()
        assertThat(usageRecords.findAll().count { it.ownerId == owner.id }).isEqualTo(1)
        assertThat(snapshot.messages.filter { it.role == "USER" }.map { it.content }).containsExactly(originalInstruction, revision)
        assertThat(snapshot.messages.last().content).isEqualTo("자동화 설계안이 준비되었습니다. 기능·에이전트·가이드를 확인하고 설계를 승인해 주세요.")
    }

    @Test
    fun `partial clarification answers accumulate and only unanswered fields are asked again`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("partial-$suffix@example.com", "password123", "partial_$suffix", "누적 답변 검증"))
        var snapshot = service.createConversation(owner.id, "partial-conversation-$suffix")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "고객 문의 답변을 자동화하고 싶어요.", "partial-vague-$suffix")
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("inbound", "knowledgeSource", "approvalPolicy", "destination")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Slack #customer-support 채널로 들어옵니다.", "partial-inbound-$suffix")
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("knowledgeSource", "approvalPolicy", "destination")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Notion 고객 FAQ 데이터베이스를 참고합니다.", "partial-knowledge-$suffix")
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("approvalPolicy", "destination")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "담당자가 검토하고 승인한 경우에만 원래 Slack 메시지 스레드로 전송합니다.", "partial-final-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.clarificationQuestions).isEmpty()
        assertThat(snapshot.proposal).isNotNull
    }

    @Test
    fun `scheduled news report compiles to one agent and safe mock delivery`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("writing-$suffix@example.com", "password123", "writing_$suffix", "글쓰기 답변 검증"))
        val snapshot = service.createConversation(owner.id, "writing-conversation-$suffix")

        val designed = service.sendMessage(
            owner.id,
            snapshot.conversationId,
            "매일 오전 8시에 네이버 경제·주식 뉴스를 수집해 시장 영향 보고서를 만들고 담당자 승인 후 Slack #market-report 채널로 전송해줘.",
            "writing-message-$suffix",
        )
        assertThat(designed.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(designed.agentDefinitions.map { it.key }).containsExactly("market-news-reporter")
        assertThat(designed.proposal?.templateSelection?.templateKey).isEqualTo("daily-market-news-report")
        assertThat(designed.proposal?.economics?.estimatedAiCallsPerRun).isEqualTo(1)
        assertThat(designed.proposal?.agentDesign?.workflow?.nodes?.map { it.kind }).contains(
            DesignNodeKind.START, DesignNodeKind.TRIGGER, DesignNodeKind.TOOL, DesignNodeKind.AGENT,
            DesignNodeKind.TEMPLATE, DesignNodeKind.USER_APPROVAL, DesignNodeKind.OUTPUT, DesignNodeKind.END,
        )
        assertThat(designed.proposal?.graphPlan?.nodes?.map { it.nodeType }).containsExactly(
            "schedule.trigger", "news.search.mock", "data.deduplicate", "ai.generate", "template.render", "human.approval", "slack.send.mock", "workflow.end",
        )
    }

    @Test
    fun `output template revision previews approves switches and rolls back without mutating active version`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("template-version-$suffix@example.com", "password123", "template_version_$suffix", "템플릿 버전 검증"))
        var snapshot = service.createConversation(owner.id, "template-version-conversation-$suffix")
        snapshot = service.sendMessage(
            owner.id, snapshot.conversationId,
            "매일 오전 8시에 네이버 경제·주식 뉴스를 수집해 시장 영향 보고서를 만들고 담당자 승인 후 Slack #market-report 채널로 전송해줘.",
            "template-version-message-$suffix",
        )
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "template-version-design-v1-$suffix")
        val workflowV1 = snapshot.currentVersionId!!
        val templateV1 = workflowVersions.findById(workflowV1).orElseThrow().templateVersionId!!
        var run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "시장 뉴스"), "template-version-run-v1-$suffix")
        run = service.decideExecution(owner.id, run.id, true, "template-version-run-approve-v1-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        snapshot = service.activate(owner.id, snapshot.workflowId, "template-version-activate-v1-$suffix")
        assertThat(outputTemplateVersions.findById(templateV1).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.ACTIVE)

        snapshot = service.applyPatch(
            owner.id, snapshot.workflowId, "보고서에 숫자를 더 많이 보여줘.", snapshot.currentVersionId!!,
            snapshot.validation!!.graphHash, "template-version-patch-v2-$suffix",
        )
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.versions).hasSize(1)
        assertThat(snapshot.proposal!!.templateSelection!!.version).isEqualTo(2)
        val template = outputTemplates.findByTemplateKey("daily-market-news-report")!!
        val templateV2 = outputTemplateVersions.findByTemplateIdAndVersionNo(template.id, 2)!!
        assertThat(templateV2.state).isEqualTo(HarnessTemplateVersionState.PREVIEWED)
        assertThat(templateV2.executionContract.toString()).contains("minimumNumericFacts=3")
        assertThat(outputTemplateVersions.findById(templateV1).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.ACTIVE)

        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "template-version-design-v2-$suffix")
        assertThat(workflowVersions.findById(snapshot.currentVersionId!!).orElseThrow().templateVersionId).isEqualTo(templateV2.id)
        assertThat(outputTemplateVersions.findById(templateV2.id).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.APPROVED)
        assertThat(outputTemplateVersions.findById(templateV1).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.ACTIVE)

        run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "시장 뉴스"), "template-version-run-v2-$suffix")
        run = service.decideExecution(owner.id, run.id, true, "template-version-run-approve-v2-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        snapshot = service.activate(owner.id, snapshot.workflowId, "template-version-activate-v2-$suffix")
        assertThat(outputTemplateVersions.findById(templateV2.id).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.ACTIVE)
        assertThat(outputTemplateVersions.findById(templateV1).orElseThrow().state).isEqualTo(HarnessTemplateVersionState.APPROVED)

        snapshot = service.restoreVersion(owner.id, snapshot.workflowId, workflowV1, "template-version-rollback-$suffix")
        assertThat(workflowVersions.findById(snapshot.currentVersionId!!).orElseThrow().templateVersionId).isEqualTo(templateV1)
    }

    @Test
    fun `legacy compiled workflow with unsupported requirement cannot start simulation`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("legacy-$suffix@example.com", "password123", "legacy_$suffix", "기존 설계 검증"))
        var snapshot = service.createConversation(owner.id, "legacy-conversation-$suffix")
        snapshot = service.sendMessage(
            owner.id,
            snapshot.conversationId,
            "Slack 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 Slack 스레드로 전송한다.",
            "legacy-message-$suffix",
        )
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "legacy-design-$suffix")
        val requirement = requirements.findByConversationId(snapshot.conversationId)!!
        requirement.structuredJson = requirement.structuredJson + mapOf(
            "objective" to "네이버 경제뉴스 보고서를 매일 8시에 Slack으로 전송한다.",
            "trigger" to "오전 8시 정기 실행",
            "inputs" to listOf("네이버 경제뉴스"),
            "outputs" to listOf("Slack 보고서"),
            "steps" to listOf("뉴스 수집", "보고서 작성", "Slack 전송"),
            "decisions" to listOf("포함할 뉴스 선택"),
            "exceptions" to listOf("뉴스 수집 실패"),
            "humanApprovalRequired" to false,
        )
        requirements.saveAndFlush(requirement)

        val invalidSnapshot = service.snapshot(owner.id, snapshot.conversationId)
        assertThat(invalidSnapshot.requirement?.objective).isEqualTo("네이버 경제뉴스 보고서를 매일 8시에 Slack으로 전송한다.")
        assertThat(invalidSnapshot.validation?.valid).isFalse()
        assertThat(invalidSnapshot.validation?.issues?.map { it.code }).contains("MEANING_REQUIREMENT_DROPPED", "MEANING_UNREQUESTED_INTEGRATION")

        assertThatThrownBy {
            service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "실행"), "legacy-run-$suffix")
        }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("뉴스 자료 수집 단계가 그래프에 없습니다")
    }

    @Test
    fun `unsupported developer automation is rejected before fake harness compilation`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("unsupported-$suffix@example.com", "password123", "unsupported_$suffix", "범위 검증"))
        var snapshot = service.createConversation(owner.id, "unsupported-conversation-$suffix")
        assertThatThrownBy {
            service.sendMessage(owner.id, snapshot.conversationId, "나는 백엔드 개발자인데 커밋까지 자동화해줘", "unsupported-message-$suffix")
        }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("개발 도구 쓰기·배포")
    }

    @ParameterizedTest(name = "모호한 요청은 설계하지 않고 질문한다 - {0}")
    @ValueSource(strings = [
        "고객 문의 답변하는 일을 자동화하고 싶어요.",
        "매일 하는 보고 업무를 줄이고 싶어요.",
        "회의 정리를 자동으로 해주세요.",
        "마케팅 업무를 자동화하고 싶습니다.",
        "신규 입사자 안내를 자동화해줘.",
        "영업 후속 작업을 자동으로 처리하고 싶어요.",
        "자료 조사와 요약을 알아서 해주세요.",
        "반복되는 백오피스 업무를 없애고 싶어요.",
        "콘텐츠 발행 과정을 자동화하고 싶습니다.",
    ])
    fun `ten vague requests ask four required questions before design`(instruction: String) {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("vague-$suffix@example.com", "password123", "vague_$suffix", "모호한 요청 검증"))
        var snapshot = service.createConversation(owner.id, "vague-conversation-$suffix")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, instruction, "vague-message-$suffix")

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.map { it.field })
            .containsExactly("inbound", "knowledgeSource", "approvalPolicy", "destination")
        assertThat(snapshot.proposal).isNull()
        assertThat(snapshot.agentDefinitions).isEmpty()
        assertThat(snapshot.graph).isNull()
        assertThat(snapshot.messages.last().content).isEqualTo("설계를 진행하려면 아래 4가지 정보가 더 필요합니다. 질문별 답변을 한 번에 작성해 주세요.")
        assertThat(snapshot.messages.last().content).doesNotContain(snapshot.clarificationQuestions.first().question)
    }

    @Test
    fun `stopped workflow preserves versions and blocks simulation patch and approval resume`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("stop-$suffix@example.com", "password123", "stop_$suffix", "중지 검증"))
        var snapshot = service.createConversation(owner.id, "stop-conversation-$suffix")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Slack 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 Slack 스레드로 전송한다.", "stop-message-$suffix")
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "stop-design-$suffix")
        val versionId = snapshot.currentVersionId!!
        val versionCount = snapshot.versions.size
        val run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "환불은 언제 처리되나요?"), "stop-run-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)

        snapshot = service.stop(owner.id, snapshot.workflowId, "stop-workflow-$suffix")

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.STOPPED)
        assertThat(snapshot.currentVersionId).isEqualTo(versionId)
        assertThat(snapshot.versions).hasSize(versionCount)
        assertThatThrownBy { service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "재실행"), "stop-run-again-$suffix") }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.applyPatch(owner.id, snapshot.workflowId, "Slack 답변 전 담당자 승인을 추가해줘.", versionId, snapshot.validation!!.graphHash, "stop-patch-$suffix") }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.decideExecution(owner.id, run.id, true, "stop-approval-$suffix") }
            .isInstanceOf(ConflictException::class.java)
    }

    private fun principal(ownerId: UUID, email: String) = AuthenticatedUser(ownerId, email, "unused", true)
}
