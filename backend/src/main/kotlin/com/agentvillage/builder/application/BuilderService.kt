package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.*
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class BuilderMessageView(val id: UUID, val role: String, val content: String, val workflowVersionId: UUID?, val createdAt: Instant)
data class WorkflowVersionView(
    val id: UUID,
    val versionNo: Int,
    val graphHash: String,
    val changeSummary: String,
    val approved: Boolean,
    val outputTemplateVersionId: UUID?,
    val createdAt: Instant,
)
data class StepRunView(val nodeId: String, val nodeType: String, val sequenceNo: Int, val status: BuilderStepStatus, val input: Map<String, Any?>, val output: Map<String, Any?>?, val errorMessage: String?)
data class RunView(
    val id: UUID,
    val status: BuilderRunStatus,
    val mode: BuilderRunMode,
    val currentNodeId: String?,
    val outputTemplateVersionId: UUID?,
    val output: Map<String, Any?>?,
    val requirementMatched: Boolean?,
    val steps: List<StepRunView>,
    val pendingApprovalId: UUID?,
    val attemptCount: Int,
    val failureCode: String?,
    val failureMessage: String?,
)

data class AgentDefinitionUpdate(
    val name: String,
    val role: String,
    val behaviorRules: List<String>,
    val forbiddenRules: List<String>,
    val evidenceRequirements: List<String>,
    val toolKeys: List<String>,
    val skillKeys: List<String>,
    val memoryScope: String,
)
data class TFrameXFlowImport(
    val baseVersionId: UUID,
    val expectedGraphHash: String,
    val tframexCommit: String,
    val designBundle: Map<String, Any?>,
    val runtimeDefinition: Map<String, Any?>,
)
data class BuilderSnapshot(
    val workspaceId: UUID,
    val conversationId: UUID,
    val workflowId: UUID,
    val status: WorkflowStatus,
    val requirement: AutomationRequirement?,
    val clarificationQuestions: List<ClarificationQuestion>,
    val proposal: AutomationProposal?,
    val agentDefinitions: List<AgentDefinition>,
    val agentMarkdown: List<String>,
    val guideDefinitions: List<GuideDefinition>,
    val guideMarkdown: List<String>,
    val graph: WorkflowGraph?,
    val validation: WorkflowValidationResult?,
    val currentVersionId: UUID?,
    val approvedVersionId: UUID?,
    val messages: List<BuilderMessageView>,
    val versions: List<WorkflowVersionView>,
)
data class BuilderConversationSummary(val conversationId: UUID, val workflowId: UUID, val title: String, val status: WorkflowStatus, val currentVersionNo: Int?, val updatedAt: Instant)
@Service
class BuilderService(
    private val workspaces: BuilderWorkspaceRepository,
    private val conversations: BuilderConversationRepository,
    private val messages: BuilderMessageRepository,
    private val requirements: BuilderRequirementRepository,
    private val proposals: BuilderProposalRepository,
    private val workflows: BuilderWorkflowRepository,
    private val versions: BuilderWorkflowVersionRepository,
    private val approvals: BuilderApprovalRepository,
    private val runs: BuilderRunRepository,
    private val stepRuns: BuilderStepRunRepository,
    private val pipeline: StructuredMetaAgentPipeline,
    private val usageLimiter: BuilderUsageLimiter,
    private val jobProgress: BuilderJobProgressService,
    private val validator: WorkflowGraphValidator,
    private val graphTranslator: WorkflowGraphTranslator,
    private val generationDrafts: AgentGenerationDraftService,
    private val catalog: WorkflowNodeCatalog,
    private val teamDeployments: AutomationTeamDeploymentService,
    private val packageRenderer: HarnessPackageRenderer,
    private val tframexCompiler: TFrameXDefinitionCompiler,
    private val tframexRuntime: TFrameXCoreRuntimeClient,
    private val templateCatalog: HarnessTemplateCatalogService,
    private val mapper: ObjectMapper,
) {
    @Transactional
    fun createConversation(ownerId: UUID, idempotencyKey: String, purpose: BuilderConversationPurpose = BuilderConversationPurpose.AUTOMATION): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val workspace = workspaces.findByOwnerId(ownerId) ?: workspaces.save(BuilderWorkspace(ownerId = ownerId))
        conversations.findByWorkspaceIdAndIdempotencyKey(workspace.id, idempotencyKey)?.let {
            if (it.purpose != purpose) throw ConflictException("IDEMPOTENCY_KEY_REUSED", "다른 제품 흐름에서 사용한 Idempotency-Key입니다.")
            return snapshot(ownerId, it.id)
        }
        val workflowId = UUID.randomUUID()
        val initialName = if (purpose == BuilderConversationPurpose.AGENT_DEVELOPMENT) "새 AI 에이전트" else "새 업무 자동화"
        val conversation = conversations.save(BuilderConversation(workspaceId = workspace.id, workflowId = workflowId, title = initialName, purpose = purpose, idempotencyKey = idempotencyKey))
        workflows.save(BuilderWorkflow(id = workflowId, workspaceId = workspace.id, conversationId = conversation.id, name = initialName))
        return snapshot(ownerId, conversation.id)
    }

    @Transactional(readOnly = true)
    fun requireConversationPurpose(ownerId: UUID, conversationId: UUID, purpose: BuilderConversationPurpose) {
        val conversation = context(ownerId, conversationId).conversation
        if (conversation.purpose != purpose) throw NotFoundException("BUILDER_CONVERSATION_NOT_FOUND", "요청한 대화를 찾을 수 없습니다.")
    }

    @Transactional(readOnly = true)
    fun requireWorkflowPurpose(ownerId: UUID, workflowId: UUID, purpose: BuilderConversationPurpose) {
        val conversation = workflowContext(ownerId, workflowId).conversation
        if (conversation.purpose != purpose) throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
    }

    @Transactional(readOnly = true)
    fun requireRunPurpose(ownerId: UUID, runId: UUID, purpose: BuilderConversationPurpose) {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val run = runs.findByIdAndWorkspaceId(runId, workspace.id) ?: throw NotFoundException("BUILDER_RUN_NOT_FOUND", "실행을 찾을 수 없습니다.")
        requireWorkflowPurpose(ownerId, run.workflowId, purpose)
    }

    @Transactional
    fun sendMessage(ownerId: UUID, conversationId: UUID, instruction: String, idempotencyKey: String, jobId: UUID? = null): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = context(ownerId, conversationId)
        if (context.workflow.status == WorkflowStatus.STOPPED) throw ConflictException("WORKFLOW_STOPPED", "중지된 자동화는 수정하거나 실행할 수 없습니다. 새 자동화를 만들어 주세요.")
        if (context.workflow.status == WorkflowStatus.FAILED) transition(context.workflow, WorkflowStatus.DRAFT)
        messages.findByConversationIdAndIdempotencyKey(conversationId, idempotencyKey)?.let { return snapshot(ownerId, conversationId) }
        val workflow = context.workflow
        val message = messages.save(BuilderMessage(conversationId = conversationId, role = "USER", content = instruction.trim(), workflowVersionId = workflow.currentVersionId, idempotencyKey = idempotencyKey))
        if (workflow.currentVersionId != null && (
                isOutputTemplatePatch(instruction) ||
                    isSlackToEmailPatch(instruction) ||
                    isHumanApprovalAdditionPatch(instruction)
            )
        ) {
            applyNaturalPatch(context, instruction, message.id)
            return snapshot(ownerId, conversationId)
        }
        if (requirements.findByConversationId(conversationId) == null) {
            analyzeAndDesign(context, instruction, idempotencyKey, jobId)
        } else if (workflow.status == WorkflowStatus.NEEDS_CLARIFICATION) {
            analyzeAndDesign(context, cumulativeInstruction(conversationId), idempotencyKey, jobId, consumeUsage = false)
        } else if (isRejectedDraft(context) || (workflow.status == WorkflowStatus.DRAFT && context.conversation.purpose == BuilderConversationPurpose.AGENT_DEVELOPMENT)) {
            analyzeAndDesign(
                context,
                cumulativeInstruction(conversationId),
                idempotencyKey,
                jobId,
                consumeUsage = false,
                revision = context.conversation.purpose == BuilderConversationPurpose.AGENT_DEVELOPMENT,
            )
        } else {
            throw ConflictException("BUILDER_MESSAGE_NOT_APPLICABLE", "현재 단계에서는 수정 요청이나 승인 작업을 사용해 주세요.")
        }
        return snapshot(ownerId, conversationId)
    }

    @Transactional
    fun recordGenerationFailure(ownerId: UUID, conversationId: UUID, instruction: String, idempotencyKey: String, message: String) {
        val context = context(ownerId, conversationId)
        generationDrafts.fail(conversationId, message)
        if (messages.findByConversationIdAndIdempotencyKey(conversationId, idempotencyKey) == null) {
            messages.save(BuilderMessage(conversationId = conversationId, role = "USER", content = instruction.trim(), workflowVersionId = context.workflow.currentVersionId, idempotencyKey = idempotencyKey))
        }
        context.workflow.status = WorkflowStatus.FAILED
        messages.save(BuilderMessage(conversationId = conversationId, role = "ASSISTANT", content = "생성에 실패했습니다. 입력은 보존되었습니다. 다시 시도해 주세요: ${message.take(300)}", workflowVersionId = context.workflow.currentVersionId))
    }

    private fun analyzeAndDesign(
        context: OwnedContext,
        instruction: String,
        idempotencyKey: String,
        jobId: UUID?,
        consumeUsage: Boolean = true,
        revision: Boolean = false,
    ) {
        val pipelineContext = context.pipeline(jobId)
        pipeline.preflight(pipelineContext)
        if (consumeUsage) {
            if (revision) usageLimiter.claimRevision(pipelineContext, idempotencyKey)
            else usageLimiter.claim(pipelineContext, idempotencyKey)
        }
        val designInstruction = if (context.conversation.purpose == BuilderConversationPurpose.AGENT_DEVELOPMENT) agentDevelopmentInstruction(instruction) else instruction
        val mode = if (context.conversation.purpose == BuilderConversationPurpose.AGENT_DEVELOPMENT) StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT else StructuredMetaAgentPipeline.DesignMode.AUTOMATION
        generationDrafts.start(pipelineContext, instruction, mode)
        var bundle = pipeline.generateDesign(pipelineContext, designInstruction, mode, userInstruction = instruction)
        generationDrafts.checkpoint(context.conversation.id, bundle)
        bundle = generationDrafts.reloadBundle(context.conversation.id)
        if (bundle.clarificationQuestions.isEmpty()) {
            val firstValidation = validateGeneratedDesign(context.workflow.id, bundle, instruction)
            if (!firstValidation.valid) {
                generationDrafts.validationFailed(context.conversation.id, firstValidation.issues)
                bundle = pipeline.generateDesign(pipelineContext, designInstruction, mode, firstValidation.issues, bundle, userInstruction = instruction)
                generationDrafts.checkpoint(context.conversation.id, bundle)
                bundle = generationDrafts.reloadBundle(context.conversation.id)
                val repairedValidation = validateGeneratedDesign(context.workflow.id, bundle, instruction)
                if (!repairedValidation.valid) {
                    generationDrafts.validationFailed(context.conversation.id, repairedValidation.issues)
                    throw BadRequestException(
                        if (repairedValidation.issues.any { it.code.startsWith("MEANING_") }) "WORKFLOW_REQUIREMENT_MISMATCH" else "WORKFLOW_VALIDATION_FAILED",
                        repairedValidation.issues.joinToString(" ") { it.message },
                    )
                }
            }
        }
        val requirement = bundle.requirement
        val questions = if (context.conversation.purpose == BuilderConversationPurpose.AGENT_DEVELOPMENT) emptyList() else bundle.clarificationQuestions
        require(requirement.objective.isNotBlank() && requirement.steps.isNotEmpty())
        val map = mapper.convertValue(requirement, object : TypeReference<Map<String, Any?>>() {}).toMutableMap()
        map["clarificationQuestions"] = mapper.convertValue(questions, object : TypeReference<List<Map<String, Any?>>>() {})
        requirements.findByConversationId(context.conversation.id)?.let { it.structuredJson = map }
            ?: requirements.save(BuilderRequirementEntity(conversationId = context.conversation.id, structuredJson = map))
        if (questions.isNotEmpty()) {
            if (context.workflow.status != WorkflowStatus.NEEDS_CLARIFICATION) transition(context.workflow, WorkflowStatus.NEEDS_CLARIFICATION)
            messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "설계를 진행하려면 아래 ${questions.size}가지 정보가 더 필요합니다. 질문별 답변을 한 번에 작성해 주세요."))
        } else {
            saveDesign(context, bundle, instruction, jobId)
            requireCompletePackage(packageRenderer.render(bundle))
            generationDrafts.complete(context.conversation.id)
        }
    }

    private fun validateGeneratedDesign(workflowId: UUID, bundle: MetaAgentDesignBundle, sourceInstruction: String): WorkflowValidationResult =
        runCatching {
            validator.validate(graphTranslator.translate(workflowId, bundle.proposal), bundle.requirement, bundle.proposal, bundle.agentDefinitions, sourceInstruction)
        }.getOrElse { exception ->
            WorkflowValidationResult(
                valid = false,
                graphHash = "",
                issues = listOf(ValidationIssue("WORKFLOW_GRAPH_TRANSLATION_FAILED", exception.message ?: "그래프 변환에 실패했습니다.")),
            )
        }

    private fun saveDesign(context: OwnedContext, bundle: MetaAgentDesignBundle, sourceInstruction: String, jobId: UUID?) {
        jobProgress.running(jobId, BuilderGenerationStage.DESIGN_SAVING)
        val proposal = bundle.proposal
        val agents = bundle.agentDefinitions
        val guides = bundle.guideDefinitions
        requireValidDesign(compileGraph(context.workflow.id, proposal), bundle.requirement, proposal, agents, sourceInstruction)
        proposals.findByConversationId(context.conversation.id)?.let {
            it.proposalJson = mapper.convertValue(proposal, mapType())
            it.agentDefinitionsJson = mapper.convertValue(agents, listMapType())
            it.guideDefinitionsJson = mapper.convertValue(guides, listMapType())
        } ?: proposals.save(BuilderProposalEntity(conversationId = context.conversation.id, proposalJson = mapper.convertValue(proposal, mapType()), agentDefinitionsJson = mapper.convertValue(agents, listMapType()), guideDefinitionsJson = mapper.convertValue(guides, listMapType())))
        templateCatalog.preview(bundle)
        context.workflow.name = proposal.name
        if (context.workflow.status == WorkflowStatus.NEEDS_CLARIFICATION) transition(context.workflow, WorkflowStatus.PROPOSAL_READY) else transition(context.workflow, WorkflowStatus.PROPOSAL_READY)
        transition(context.workflow, WorkflowStatus.WAITING_DESIGN_APPROVAL)
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "자동화 설계안이 준비되었습니다. 기능·에이전트·가이드를 확인하고 설계를 승인해 주세요."))
    }

    @Transactional
    fun decideDesign(ownerId: UUID, workflowId: UUID, approve: Boolean, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        if (context.workflow.status == WorkflowStatus.STOPPED) throw ConflictException("WORKFLOW_STOPPED", "중지된 자동화입니다.")
        approvals.findByWorkspaceIdAndIdempotencyKey(context.workspace.id, idempotencyKey)?.let {
            if (it.workflowId != workflowId || it.approvalType != ApprovalType.DESIGN) {
                throw ConflictException("IDEMPOTENCY_KEY_REUSED", "다른 워크플로우 또는 승인 작업에서 사용한 Idempotency-Key입니다.")
            }
            return snapshot(ownerId, context.conversation.id)
        }
        if (context.workflow.status != WorkflowStatus.WAITING_DESIGN_APPROVAL) throw ConflictException("INVALID_WORKFLOW_STATE", "설계 승인 대기 상태에서만 처리할 수 있습니다.")
        val design = if (approve) storedDesign(context.conversation.id) else null
        val graph = design?.let {
            BuilderMvpSupportPolicy.requireSupported(it.requirement)
            compileGraph(workflowId, it.proposal).also { graph -> requireValidDesign(graph, it.requirement, it.proposal, it.agents, cumulativeInstruction(context.conversation.id)) }
        }
        val approval = BuilderApproval(workspaceId = context.workspace.id, workflowId = workflowId, approvalType = ApprovalType.DESIGN, idempotencyKey = idempotencyKey, status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED, decidedBy = ownerId, decidedAt = Instant.now())
        approvals.save(approval)
        if (!approve) {
            transition(context.workflow, WorkflowStatus.DRAFT)
            messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = DESIGN_REJECTED_MESSAGE))
            return snapshot(ownerId, context.conversation.id)
        }
        transition(context.workflow, WorkflowStatus.APPROVED)
        transition(context.workflow, WorkflowStatus.COMPILING)
        val approvedDesign = checkNotNull(design)
        val approvedGraph = checkNotNull(graph)
        pipeline.record(context.pipeline(), "compile_workflow", approvedDesign.agents.size, approvedGraph.nodes.size)
        transition(context.workflow, WorkflowStatus.VALIDATING)
        val validation = validator.validate(approvedGraph, approvedDesign.requirement, approvedDesign.proposal, approvedDesign.agents, cumulativeInstruction(context.conversation.id))
        pipeline.record(context.pipeline(), "validate_workflow", approvedGraph.nodes.size, validation.issues.size)
        if (!validation.valid) { transition(context.workflow, WorkflowStatus.FAILED); throw BadRequestException("WORKFLOW_VALIDATION_FAILED", validation.issues.joinToString(" ") { it.message }) }
        val version = saveVersion(context.workflow, approvedGraph, "최초 승인 설계 컴파일", approved = true)
        context.workflow.approvedVersionId = version.id
        transition(context.workflow, WorkflowStatus.READY_TO_SIMULATE)
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "설계 승인과 서버 검증이 완료되었습니다. 캔버스와 샘플 시뮬레이션을 사용할 수 있습니다.", workflowVersionId = version.id))
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional
    fun applyPatch(ownerId: UUID, workflowId: UUID, instruction: String, baseVersionId: UUID, expectedHash: String, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        if (context.workflow.status == WorkflowStatus.STOPPED) throw ConflictException("WORKFLOW_STOPPED", "중지된 자동화는 수정할 수 없습니다.")
        messages.findByConversationIdAndIdempotencyKey(context.conversation.id, idempotencyKey)?.let { return snapshot(ownerId, context.conversation.id) }
        val current = currentVersion(context.workflow)
        if (current.id != baseVersionId || current.graphHash != expectedHash) throw ConflictException("WORKFLOW_VERSION_CONFLICT", "캔버스가 최신 버전이 아닙니다. 새 버전을 불러와 다시 적용해 주세요.")
        val message = messages.save(BuilderMessage(conversationId = context.conversation.id, role = "USER", content = instruction, workflowVersionId = current.id, idempotencyKey = idempotencyKey))
        applyNaturalPatch(context, instruction, message.id)
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional
    fun updateAgentDefinition(ownerId: UUID, workflowId: UUID, agentKey: String, update: AgentDefinitionUpdate, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        messages.findByConversationIdAndIdempotencyKey(context.conversation.id, idempotencyKey)?.let { return snapshot(ownerId, context.conversation.id) }
        val version = currentVersion(context.workflow)
        val proposalEntity = proposals.findByConversationId(context.conversation.id)
            ?: throw ConflictException("WORKFLOW_PROPOSAL_NOT_FOUND", "에이전트 설계안을 찾을 수 없습니다.")
        val proposal = mapper.convertValue(proposalEntity.proposalJson, AutomationProposal::class.java)
        val requirementEntity = requirements.findByConversationId(context.conversation.id)
            ?: throw ConflictException("WORKFLOW_REQUIREMENT_NOT_FOUND", "에이전트 요구사항을 찾을 수 없습니다.")
        val requirement = mapper.convertValue(requirementEntity.structuredJson.filterKeys { it != "clarificationQuestions" }, AutomationRequirement::class.java)
        val agents: List<AgentDefinition> = mapper.convertValue(proposalEntity.agentDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java))
        val guides: List<GuideDefinition> = mapper.convertValue(proposalEntity.guideDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, GuideDefinition::class.java))
        val index = agents.indexOfFirst { it.key == agentKey }
        if (index < 0) throw NotFoundException("AGENT_DEFINITION_NOT_FOUND", "수정할 에이전트를 찾을 수 없습니다.")
        if (update.name.isBlank() || update.name.length > 80 || update.role.isBlank() || update.role.length > 1_000) {
            throw BadRequestException("INVALID_AGENT_DEFINITION", "에이전트 이름과 역할을 확인해 주세요.")
        }
        val bindings = proposal.resourcePlan?.bindings.orEmpty()
        val allowedTools = bindings.filter { it.resourceKind == ResourceKind.TOOL }.map { it.resourceKey }.toSet()
        val allowedSkills = bindings.filter { it.resourceKind == ResourceKind.SKILL }.map { it.resourceKey }.toSet()
        if (!allowedTools.containsAll(update.toolKeys) || !allowedSkills.containsAll(update.skillKeys)) {
            throw BadRequestException("UNBOUND_AGENT_RESOURCE", "설계에 바인딩되지 않은 도구나 스킬은 선택할 수 없습니다.")
        }
        val boundMemoryScopes = bindings.filter { it.resourceKind == ResourceKind.MEMORY }
            .mapNotNull { binding ->
                setOf("SESSION", "CONVERSATION", "PROJECT").firstOrNull { scope ->
                    binding.resourceKey.equals(scope, true) || binding.resourceKey.endsWith(".${scope.lowercase()}")
                }
            }.toSet()
        if (update.memoryScope !in (boundMemoryScopes + "NONE")) {
            throw BadRequestException("UNBOUND_AGENT_MEMORY", "서버 설계에 바인딩되지 않은 메모리 범위는 선택할 수 없습니다.")
        }
        fun rules(values: List<String>) = values.map(String::trim).filter(String::isNotBlank).distinct().take(20)
        val updatedAgents = agents.toMutableList().also { list ->
            list[index] = list[index].copy(
                name = update.name.trim(), role = update.role.trim(),
                behaviorRules = rules(update.behaviorRules), forbiddenRules = rules(update.forbiddenRules),
                evidenceRequirements = rules(update.evidenceRequirements), toolKeys = update.toolKeys.distinct(),
                skillKeys = update.skillKeys.distinct(), memoryScope = update.memoryScope,
            )
        }
        val designBundle = MetaAgentDesignBundle(requirement, emptyList(), proposal.copy(agentDesign = null), updatedAgents, guides)
        val updatedProposal = proposal.copy(agentDesign = AgentDesignAssembler().assemble(designBundle, agentDevelopment = true))
        proposalEntity.proposalJson = mapper.convertValue(updatedProposal, mapType())
        proposalEntity.agentDefinitionsJson = mapper.convertValue(updatedAgents, listMapType())
        val saved = saveVersion(context.workflow, graph(version), "${update.name.trim()} 속성 수정", approved = false, templateOverride = version)
        context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
        messages.save(BuilderMessage(
            conversationId = context.conversation.id, role = "ASSISTANT",
            content = "${update.name.trim()}의 역할, 규칙, 리소스 설정을 새 버전 ${saved.versionNo}에 반영했습니다.",
            workflowVersionId = saved.id, idempotencyKey = idempotencyKey,
        ))
        return snapshot(ownerId, context.conversation.id)
    }

    private fun applyNaturalPatch(context: OwnedContext, instruction: String, sourceMessageId: UUID) {
        val current = currentVersion(context.workflow)
        val graph = graph(current)
        if (isGraphDeletionPatch(instruction)) throw BadRequestException("UNSUPPORTED_GRAPH_PATCH", UNSUPPORTED_GRAPH_PATCH_MESSAGE)
        if (isOutputTemplatePatch(instruction)) {
            val baseTemplateVersionId = current.templateVersionId
                ?: throw ConflictException("OUTPUT_TEMPLATE_VERSION_REQUIRED", "현재 Workflow에 출력 템플릿 버전이 없습니다.")
            val derived = templateCatalog.derivePreview(baseTemplateVersionId, instruction)
            val proposalEntity = proposals.findByConversationId(context.conversation.id)
                ?: throw ConflictException("WORKFLOW_PROPOSAL_NOT_FOUND", "자동화 설계안을 찾을 수 없습니다.")
            val proposal = normalizeProposal(mapper.convertValue(proposalEntity.proposalJson, AutomationProposal::class.java))
            val selection = requireNotNull(proposal.templateSelection)
            val contract = mapper.convertValue(derived.executionContract, TemplateExecutionContract::class.java)
            proposalEntity.proposalJson = mapper.convertValue(proposal.copy(
                templateSelection = selection.copy(version = derived.versionNo, source = "GENERATED", matchReason = "기존 출력 템플릿을 자연어 요청으로 복제·수정"),
                executionContract = contract,
                templateRevisionPreview = TemplateRevisionPreview(
                    baseVersion = derived.versionNo - 1,
                    previewVersion = derived.versionNo,
                    request = instruction,
                    changes = contract.qualityRules.filter { (key, value) -> proposal.executionContract?.qualityRules?.get(key) != value },
                ),
            ), mapType())
            context.workflow.status = WorkflowStatus.WAITING_DESIGN_APPROVAL
            messages.save(BuilderMessage(
                conversationId = context.conversation.id, role = "ASSISTANT",
                content = "출력 템플릿 v${derived.versionNo} 미리보기를 만들었습니다. 기존 v${derived.versionNo - 1}과 비교해 승인하면 새 Workflow Version으로 전환합니다: $instruction",
                workflowVersionId = current.id,
            ))
            return
        }
        if (!isHumanApprovalAdditionPatch(instruction) && !isCsvSummaryPatch(instruction) && !isSlackToEmailPatch(instruction)) throw BadRequestException("UNSUPPORTED_GRAPH_PATCH", UNSUPPORTED_GRAPH_PATCH_MESSAGE)
        if (isCsvSummaryPatch(instruction)) {
            val compare = graph.nodes.singleOrNull { it.nodeType == NodeType.DATA_CSV_COMPARE.wireName }
                ?: throw BadRequestException("PATCH_TARGET_NOT_FOUND", "CSV 비교 Function을 찾지 못했습니다.")
            val renderer = graph.nodes.singleOrNull { it.nodeType == NodeType.TEMPLATE_RENDER.wireName }
                ?: throw BadRequestException("PATCH_TARGET_NOT_FOUND", "CSV 표 Renderer를 찾지 못했습니다.")
            val oldEdge = graph.edges.singleOrNull { it.source == compare.id && it.target == renderer.id }
                ?: throw BadRequestException("PATCH_TARGET_NOT_FOUND", "CSV 비교와 표 Renderer 연결을 찾지 못했습니다.")
            val agent = AgentDefinition(
                "change-summary-writer", "변경사항 요약자", "결정적 CSV 비교 결과에서 중요한 변경만 사람이 이해하기 쉽게 요약한다.",
                listOf(FieldDefinition("changedRows", "array", true, "결정적 CSV 비교 결과")), listOf(FieldDefinition("summary", "string", true, "중요 변경 요약")),
                listOf("원본 비교 결과를 보존한다", "중요 변경의 이유를 간결히 설명한다"), listOf("변경 행을 새로 만들지 않는다", "비교 Function을 대체하지 않는다"), listOf("changedRows"),
            )
            val summaryNode = WorkflowNode("change-summary-${current.versionNo + 1}", NodeType.AI_GENERATE.wireName, "중요 변경 요약", NodePosition((compare.position.x + renderer.position.x) / 2, compare.position.y), mapOf("instruction" to "CSV 변경 행 중 중요한 부분을 사람이 이해하기 쉽게 요약", "agentKey" to agent.key))
            val patch = GraphPatch(current.id, current.graphHash, listOf(
                RemoveEdge(oldEdge.id), AddNode(summaryNode),
                AddEdge(WorkflowEdge("e-${compare.id}-${summaryNode.id}", compare.id, summaryNode.id, bindings = mapOf("changedRows" to "changedRows"))),
                AddEdge(WorkflowEdge("e-${summaryNode.id}-${renderer.id}", summaryNode.id, renderer.id, bindings = mapOf("changedRows" to "changedRows", "summary" to "summary"))),
            ), "CSV 비교 결과 요약 Agent 추가")
            val patched = applyPatch(graph, patch)
            val requirementEntity = requirements.findByConversationId(context.conversation.id) ?: throw ConflictException("WORKFLOW_REQUIREMENT_NOT_FOUND", "자동화 요구사항을 찾을 수 없습니다.")
            val requirement = storedRequirement(context.conversation.id).copy(steps = storedRequirement(context.conversation.id).steps + "중요 변경 요약", outputs = storedRequirement(context.conversation.id).outputs + "중요 변경 요약")
            requirementEntity.structuredJson = mapper.convertValue(requirement, mapType()) + mapOf("clarificationQuestions" to emptyList<Any>())
            val proposalEntity = proposals.findByConversationId(context.conversation.id) ?: throw ConflictException("WORKFLOW_PROPOSAL_NOT_FOUND", "자동화 설계안을 찾을 수 없습니다.")
            val proposal = normalizeProposal(mapper.convertValue(proposalEntity.proposalJson, AutomationProposal::class.java))
            val plan = requireNotNull(proposal.graphPlan)
            val summaryPlan = WorkflowNodePlan(summaryNode.id, summaryNode.nodeType, summaryNode.label, summaryNode.config)
            val patchedPlan = plan.copy(nodes = plan.nodes.flatMap { if (it.id == compare.id) listOf(it, summaryPlan) else listOf(it) }, edges = plan.edges.filterNot { it.id == oldEdge.id } + listOf(
                WorkflowEdgePlan("e-${compare.id}-${summaryNode.id}", compare.id, summaryNode.id, bindings = listOf(WorkflowFieldBinding("changedRows", "changedRows"))),
                WorkflowEdgePlan("e-${summaryNode.id}-${renderer.id}", summaryNode.id, renderer.id, bindings = listOf(WorkflowFieldBinding("changedRows", "changedRows"), WorkflowFieldBinding("summary", "summary"))),
            ))
            val agents: List<AgentDefinition> = mapper.convertValue(proposalEntity.agentDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java))
            val patchedAgents = agents + agent
            proposalEntity.proposalJson = mapper.convertValue(proposal.copy(capabilities = proposal.capabilities + "중요 변경 요약", graphPlan = patchedPlan, resourcePlan = null, agentDesign = null), mapType())
            proposalEntity.agentDefinitionsJson = mapper.convertValue(patchedAgents, listMapType())
            val storedProposal = mapper.convertValue(proposalEntity.proposalJson, AutomationProposal::class.java)
            requireValidDesign(patched, requirement, storedProposal, patchedAgents)
            val version = saveVersion(context.workflow, patched, patch.summary, approved = false)
            context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
            messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "기존 CSV 비교 Function을 유지하고 중요 변경 요약 Agent만 추가해 새 버전 ${version.versionNo}로 저장했습니다.", workflowVersionId = version.id))
            return
        }
        if (!isPatchInstruction(instruction)) throw BadRequestException("UNSUPPORTED_GRAPH_PATCH", "MVP에서는 'Slack 답변 전 담당자 승인 추가' 수정만 지원합니다.")
        val replaceSlackWithEmail = listOf("이메일", "메일").any(instruction::contains) &&
            listOf("Slack", "슬랙").any { instruction.contains(it, true) } && listOf("바꿔", "변경", "대신").any(instruction::contains)
        if (replaceSlackWithEmail) {
            val slack = graph.nodes.firstOrNull { it.nodeType in setOf(NodeType.SLACK_REPLY_MOCK.wireName, NodeType.SLACK_SEND_MOCK.wireName) }
                ?: throw BadRequestException("PATCH_TARGET_NOT_FOUND", "변경할 Slack 전송 노드를 찾지 못했습니다.")
            val recipient = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(instruction)?.value ?: "연결 시 입력 필요"
            val replacement = slack.copy(nodeType = NodeType.EMAIL_SEND_MOCK.wireName, label = "이메일 전송 (Mock · 연결 필요)", config = mapOf("recipient" to recipient, "rendererKey" to "plain-text.v1", "connectionStatus" to "UNRESOLVED"), connectionId = null)
            val hour = Regex("(?:오전\\s*)?(\\d{1,2})시").find(instruction)?.groupValues?.get(1)?.toIntOrNull()
            val schedule = graph.nodes.firstOrNull { it.nodeType == NodeType.SCHEDULE_TRIGGER.wireName }
            val renderer = graph.nodes.firstOrNull { it.nodeType == NodeType.TEMPLATE_RENDER.wireName }
            val operations = buildList<GraphPatchOperation> {
                add(ReplaceNode(slack.id, replacement))
                if (hour != null && schedule != null) add(UpdateNodeConfig(schedule.id, mapOf("cron" to "0 0 $hour * * *", "timezone" to "Asia/Seoul")))
                if (renderer != null) add(UpdateNodeConfig(renderer.id, mapOf("rendererKey" to "plain-text.v1")))
            }
            val patchSummary = if (hour == null) "Slack 전송을 이메일 Mock 전송으로 변경" else "실행 시간을 ${hour}시로 바꾸고 Slack 전송을 이메일 Mock 전송으로 변경"
            val patch = GraphPatch(current.id, current.graphHash, operations, patchSummary)
            val patched = applyPatch(graph, patch)
            val requirementEntity = requirements.findByConversationId(context.conversation.id) ?: throw ConflictException("WORKFLOW_REQUIREMENT_NOT_FOUND", "자동화 요구사항을 찾을 수 없습니다.")
            val currentRequirement = mapper.convertValue(requirementEntity.structuredJson.filterKeys { it != "clarificationQuestions" }, AutomationRequirement::class.java)
            val patchedRequirement = currentRequirement.copy(
                trigger = if (hour != null) "매일 ${hour}시 예약 실행" else currentRequirement.trigger,
                outputs = currentRequirement.outputs
                    .map { it.replace("Slack 스레드", "이메일").replace("Slack", "이메일") }
                    .let { outputs -> if (outputs.any { it.contains("이메일") }) outputs else outputs + "이메일 전송 (Mock · 연결 필요)" }
                    .distinct(),
                steps = currentRequirement.steps.map { step -> when {
                    hour != null && (step.contains("실행") || step.contains("Schedule", true)) -> "매일 ${hour}시 실행"
                    step.contains("Slack") && listOf("답변", "전송", "회신").any(step::contains) -> "이메일 전송 (Mock · 연결 필요)"
                    else -> step
                } },
                unresolvedQuestions = if (recipient == "연결 시 입력 필요") currentRequirement.unresolvedQuestions + UnresolvedQuestion("email-recipient", "받는 이메일 주소를 입력해 주세요.", true) else currentRequirement.unresolvedQuestions,
            )
            requirementEntity.structuredJson = mapper.convertValue(patchedRequirement, mapType()) + mapOf("clarificationQuestions" to emptyList<Any>())
            val proposalEntity = proposals.findByConversationId(context.conversation.id) ?: throw ConflictException("WORKFLOW_PROPOSAL_NOT_FOUND", "자동화 설계안을 찾을 수 없습니다.")
            val currentProposal = normalizeProposal(mapper.convertValue(proposalEntity.proposalJson, AutomationProposal::class.java))
            val patchedProposal = currentProposal.copy(
                capabilities = currentProposal.capabilities.map { if (it.contains("Slack") && listOf("답변", "전송", "회신", "미리보기").any(it::contains)) "이메일 전송 (Mock · 연결 필요)" else it },
                integrations = (currentProposal.integrations.filterNot { it.contains("Slack") } + listOf("Slack Mock (문의 수신)", "Email Mock · 연결 필요")).distinct(),
                graphPlan = currentProposal.graphPlan?.copy(nodes = currentProposal.graphPlan.nodes.map { node -> when (node.id) {
                    slack.id -> WorkflowNodePlan(replacement.id, replacement.nodeType, replacement.label, replacement.config)
                    schedule?.id -> WorkflowNodePlan(node.id, node.nodeType, if (hour != null) "매일 ${hour}시 실행" else node.label, if (hour != null) node.config + mapOf("cron" to "0 0 $hour * * *", "timezone" to "Asia/Seoul") else node.config)
                    renderer?.id -> WorkflowNodePlan(node.id, node.nodeType, "이메일 템플릿 렌더링", node.config + ("rendererKey" to "plain-text.v1"))
                    else -> node
                } }),
                resourcePlan = null,
                agentDesign = null,
            )
            proposalEntity.proposalJson = mapper.convertValue(patchedProposal, mapType())
            val designAgents: List<AgentDefinition> = mapper.convertValue(proposalEntity.agentDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java))
            requireValidDesign(patched, patchedRequirement, patchedProposal, designAgents)
            val version = saveVersion(context.workflow, patched, patch.summary, approved = false)
            context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
            val recipientNotice = if (recipient == "연결 시 입력 필요") " 받는 이메일 주소는 아직 없으므로 실행 연결 전에 입력해 주세요." else ""
            messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "Graph Patch를 검증해 새 버전 ${version.versionNo}로 저장했습니다: ${patch.summary}.$recipientNotice", workflowVersionId = version.id))
            return
        }
        if (graph.nodes.any { it.nodeType == NodeType.HUMAN_APPROVAL.wireName }) {
            val version = saveVersion(context.workflow, graph, "담당자 승인 노드 확인 및 유지", approved = true)
            context.workflow.approvedVersionId = version.id
            messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "담당자 승인 노드가 이미 올바른 위치에 있어 새 버전 ${version.versionNo}에서 유지했습니다.", workflowVersionId = version.id))
            return
        }
        val generate = graph.nodes.firstOrNull { it.nodeType == NodeType.AI_GENERATE.wireName } ?: throw BadRequestException("PATCH_TARGET_NOT_FOUND", "AI 생성 노드를 찾지 못했습니다.")
        val reply = graph.nodes.firstOrNull { it.nodeType == NodeType.SLACK_REPLY_MOCK.wireName } ?: throw BadRequestException("PATCH_TARGET_NOT_FOUND", "Slack 답변 노드를 찾지 못했습니다.")
        val oldEdge = graph.edges.firstOrNull { it.source == generate.id && it.target == reply.id } ?: throw BadRequestException("PATCH_TARGET_NOT_FOUND", "수정할 연결을 찾지 못했습니다.")
        val approvalNode = WorkflowNode("approval-${current.versionNo + 1}", NodeType.HUMAN_APPROVAL.wireName, "담당자 승인", NodePosition((generate.position.x + reply.position.x) / 2, generate.position.y), mapOf("approver" to "담당자"))
        val patch = GraphPatch(current.id, current.graphHash, listOf(RemoveEdge(oldEdge.id), AddNode(approvalNode), AddEdge(WorkflowEdge("e-${generate.id}-${approvalNode.id}", generate.id, approvalNode.id)), AddEdge(WorkflowEdge("e-${approvalNode.id}-${reply.id}", approvalNode.id, reply.id))), "Slack 답변 전 담당자 승인 추가")
        val patched = applyPatch(graph, patch)
        val validation = validator.validate(patched)
        if (!validation.valid) throw BadRequestException("WORKFLOW_PATCH_INVALID", validation.issues.joinToString(" ") { it.message })
        val version = saveVersion(context.workflow, patched, patch.summary, approved = false)
        pipeline.record(context.pipeline(), "compile_workflow", patch.operations.size, patched.nodes.size)
        pipeline.record(context.pipeline(), "validate_workflow", patched.nodes.size, 0)
        context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "Graph Patch를 검증해 새 버전 ${version.versionNo}로 저장했습니다: ${patch.summary}", workflowVersionId = version.id))
    }

    private fun applyPatch(graph: WorkflowGraph, patch: GraphPatch): WorkflowGraph {
        val nodes = graph.nodes.toMutableList(); val edges = graph.edges.toMutableList()
        patch.operations.forEach { operation -> when (operation) {
            is AddNode -> nodes += operation.node
            is RemoveNode -> { nodes.removeIf { it.id == operation.nodeId }; edges.removeIf { it.source == operation.nodeId || it.target == operation.nodeId } }
            is UpdateNodeConfig -> { val index = nodes.indexOfFirst { it.id == operation.nodeId }; if (index < 0) throw BadRequestException("PATCH_NODE_NOT_FOUND", operation.nodeId); nodes[index] = nodes[index].copy(config = nodes[index].config + operation.config) }
            is ReplaceNode -> { val index = nodes.indexOfFirst { it.id == operation.nodeId }; if (index < 0) throw BadRequestException("PATCH_NODE_NOT_FOUND", operation.nodeId); nodes[index] = operation.node.copy(id = operation.nodeId) }
            is MoveNode -> { val index = nodes.indexOfFirst { it.id == operation.nodeId }; if (index < 0) throw BadRequestException("PATCH_NODE_NOT_FOUND", operation.nodeId); nodes[index] = nodes[index].copy(position = operation.position) }
            is AddEdge -> edges += operation.edge
            is RemoveEdge -> if (!edges.removeIf { it.id == operation.edgeId }) throw BadRequestException("PATCH_EDGE_NOT_FOUND", operation.edgeId)
        } }
        return graph.copy(nodes = nodes, edges = edges)
    }

    @Transactional
    fun restoreVersion(ownerId: UUID, workflowId: UUID, versionId: UUID, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        if (context.workflow.status == WorkflowStatus.STOPPED) throw ConflictException("WORKFLOW_STOPPED", "중지된 자동화는 복원할 수 없습니다.")
        messages.findByConversationIdAndIdempotencyKey(context.conversation.id, idempotencyKey)?.let { return snapshot(ownerId, context.conversation.id) }
        val target = versions.findByIdAndWorkflowId(versionId, workflowId) ?: throw NotFoundException("WORKFLOW_VERSION_NOT_FOUND", "버전을 찾을 수 없습니다.")
        restoreDesignSnapshot(context.conversation.id, target.designSnapshotJson)
        val restored = saveVersion(context.workflow, graph(target), "버전 ${target.versionNo} 복원", approved = false, templateOverride = target)
        context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "버전 ${target.versionNo}을 새 버전 ${restored.versionNo}으로 복원했습니다.", workflowVersionId = restored.id, idempotencyKey = idempotencyKey))
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional
    fun startSimulation(ownerId: UUID, workflowId: UUID, input: Map<String, Any?>, idempotencyKey: String): RunView {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        runs.findByWorkspaceIdAndIdempotencyKey(context.workspace.id, idempotencyKey)?.let {
            if (it.workflowId != workflowId) throw ConflictException("IDEMPOTENCY_KEY_REUSED", "다른 워크플로우 실행에서 사용한 Idempotency-Key입니다.")
            return runView(it)
        }
        if (context.workflow.status !in setOf(WorkflowStatus.READY_TO_SIMULATE, WorkflowStatus.READY_TO_ACTIVATE)) throw ConflictException("INVALID_WORKFLOW_STATE", "시뮬레이션 준비 상태가 아닙니다.")
        val design = storedDesign(context.conversation.id)
        BuilderMvpSupportPolicy.requireSupported(design.requirement)
        val version = currentVersion(context.workflow)
        val graph = graph(version)
        requireValidDesign(graph, design.requirement, design.proposal, design.agents, cumulativeInstruction(context.conversation.id))
        context.workflow.status = WorkflowStatus.SIMULATING
        val run = runs.save(BuilderRun(
            workspaceId = context.workspace.id, workflowId = workflowId, workflowVersionId = version.id,
            templateVersionId = version.templateVersionId, inputJson = mask(input), idempotencyKey = idempotencyKey,
        ))
        pipeline.record(context.pipeline(), "simulate_workflow", input.size, graph.nodes.size)
        if (context.conversation.purpose == BuilderConversationPurpose.AGENT_DEVELOPMENT) {
            executeWithTFrameX(context, run, graph, design.agents, input)
        } else {
            executeFrom(context, run, graph, graph.entryNodeId, input)
        }
        return runView(run)
    }

    private fun executeWithTFrameX(
        context: OwnedContext,
        run: BuilderRun,
        graph: WorkflowGraph,
        agents: List<AgentDefinition>,
        input: Map<String, Any?>,
    ) {
        val result = try {
            tframexRuntime.execute(context.workflow.name, graph, agents, input)
        } catch (exception: com.agentvillage.common.exception.ApiException) {
            run.status = if (exception.code == "EXECUTION_NOT_CONFIGURED") BuilderRunStatus.EXECUTION_NOT_CONFIGURED else BuilderRunStatus.FAILED
            run.failureCode = exception.code
            run.failureMessage = exception.message.take(500)
            run.requirementMatched = false
            context.workflow.status = WorkflowStatus.SIMULATION_FAILED
            return
        } catch (exception: Exception) {
            run.status = BuilderRunStatus.FAILED
            run.failureCode = "TFRAMEX_RUNTIME_UNAVAILABLE"
            run.failureMessage = "TFrameX Core Runtime에 연결할 수 없습니다."
            run.requirementMatched = false
            context.workflow.status = WorkflowStatus.SIMULATION_FAILED
            return
        }
        result.trace.filter { it["kind"] in setOf("agent_end", "agent_error", "tool_end", "tool_error") }.forEachIndexed { index, event ->
            val kind = event["kind"]?.toString()
            val agent = event["agent"]?.toString() ?: "tframex-agent-$index"
            val tool = event["tool"]?.toString()
            val step = stepRuns.save(BuilderStepRun(
                runId = run.id,
                nodeId = tool ?: agent,
                nodeType = if (tool == null) "tframex.agent" else "tframex.tool",
                sequenceNo = index + 1,
                status = when (kind) {
                    "agent_error", "tool_error" -> BuilderStepStatus.FAILED
                    else -> BuilderStepStatus.SUCCEEDED
                },
                inputJson = mapOf("input" to event["input"]),
            ))
            if (kind in setOf("agent_end", "tool_end")) step.outputJson = mapOf("output" to event["output"])
            if (kind in setOf("agent_error", "tool_error")) step.errorMessage = event["error"]?.toString()
        }
        if (result.status != "SUCCEEDED") {
            run.status = if (result.status == "EXECUTION_NOT_CONFIGURED") BuilderRunStatus.EXECUTION_NOT_CONFIGURED else BuilderRunStatus.FAILED
            run.failureCode = result.code ?: result.status
            run.failureMessage = (result.message ?: "TFrameX 실행에 실패했습니다.").take(500)
            run.requirementMatched = false
            context.workflow.status = WorkflowStatus.SIMULATION_FAILED
            return
        }
        val design = storedDesign(context.conversation.id)
        val projected = projectOutput(result.output, design.proposal.outputSchema)
        val issue = validateFields(design.proposal.outputSchema, projected, "최종 출력")
        run.outputJson = if (issue == null) projected else projected + ("validationError" to issue)
        run.requirementMatched = issue == null
        run.status = if (issue == null) BuilderRunStatus.SUCCEEDED else BuilderRunStatus.FAILED
        run.failureCode = if (issue == null) null else "FINAL_OUTPUT_SCHEMA_INVALID"
        run.failureMessage = issue
        context.workflow.status = if (issue == null) WorkflowStatus.READY_TO_ACTIVATE else WorkflowStatus.SIMULATION_FAILED
    }

    @Transactional(readOnly = true)
    fun getRun(ownerId: UUID, runId: UUID): RunView {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        return runView(runs.findByIdAndWorkspaceId(runId, workspace.id) ?: throw NotFoundException("RUN_NOT_FOUND", "실행을 찾을 수 없습니다."))
    }

    @Transactional
    fun decideExecution(ownerId: UUID, runId: UUID, approve: Boolean, idempotencyKey: String): RunView {
        requireIdempotency(idempotencyKey)
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val run = runs.findByIdAndWorkspaceId(runId, workspace.id) ?: throw NotFoundException("RUN_NOT_FOUND", "실행을 찾을 수 없습니다.")
        approvals.findByWorkspaceIdAndIdempotencyKey(workspace.id, idempotencyKey)?.let {
            if (it.runId != runId || it.approvalType != ApprovalType.EXECUTION) {
                throw ConflictException("IDEMPOTENCY_KEY_REUSED", "다른 실행 또는 승인 작업에서 사용한 Idempotency-Key입니다.")
            }
            return runView(run)
        }
        val context = workflowContext(ownerId, run.workflowId)
        if (context.workflow.status == WorkflowStatus.STOPPED) throw ConflictException("WORKFLOW_STOPPED", "중지된 자동화는 승인 후 재개할 수 없습니다.")
        val design = storedDesign(context.conversation.id)
        BuilderMvpSupportPolicy.requireSupported(design.requirement)
        requireValidDesign(graph(versions.findById(run.workflowVersionId).orElseThrow()), design.requirement, design.proposal, design.agents, cumulativeInstruction(context.conversation.id))
        if (run.status != BuilderRunStatus.WAITING_APPROVAL) throw ConflictException("RUN_NOT_WAITING_APPROVAL", "승인 대기 실행이 아닙니다.")
        val approval = approvals.findByRunIdAndStatus(runId, ApprovalStatus.PENDING) ?: throw NotFoundException("APPROVAL_NOT_FOUND", "승인 요청을 찾을 수 없습니다.")
        approval.idempotencyKey = idempotencyKey; approval.status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED; approval.decidedBy = ownerId; approval.decidedAt = Instant.now()
        val waiting = stepRuns.findAllByRunIdOrderBySequenceNo(runId).last { it.status == BuilderStepStatus.WAITING_APPROVAL }
        if (!approve) { waiting.status = BuilderStepStatus.FAILED; waiting.errorMessage = "사용자가 실행을 거절했습니다."; run.status = BuilderRunStatus.FAILED; workflowContext(ownerId, run.workflowId).workflow.status = WorkflowStatus.SIMULATION_FAILED; return runView(run) }
        waiting.status = BuilderStepStatus.SUCCEEDED; waiting.outputJson = waiting.inputJson + ("approved" to true)
        val graph = graph(versions.findById(run.workflowVersionId).orElseThrow())
        val edge = graph.edges.firstOrNull { it.source == waiting.nodeId }
        if (edge == null) finishRun(context, run, graph, waiting.outputJson.orEmpty())
        else executeFrom(context, run, graph, edge.target, bindEdge(waiting.outputJson.orEmpty(), edge))
        return runView(run)
    }

    private fun executeFrom(context: OwnedContext, run: BuilderRun, graph: WorkflowGraph, startNodeId: String, initialInput: Map<String, Any?>) {
        val agents = storedDesign(context.conversation.id).agents.associateBy { it.key }
        var nodeId: String? = startNodeId; var value = initialInput; var sequence = stepRuns.findAllByRunIdOrderBySequenceNo(run.id).size
        while (nodeId != null) {
            val node = graph.nodes.first { it.id == nodeId }; val contract = catalog.require(node.nodeType)
            contract.validateInput(value).takeIf { it.isNotEmpty() }?.let { throw BadRequestException("INVALID_NODE_INPUT", it.joinToString()) }
            val agent = node.config["agentKey"]?.toString()?.let(agents::get)
            val nodeInput = agent?.let { enrichAgentInput(it, value) } ?: value
            agent?.let { validateFields(it.inputSchema, nodeInput, "${node.label} 입력") }
                ?.let { throw BadRequestException("INVALID_NODE_INPUT", it) }
            val step = stepRuns.save(BuilderStepRun(runId = run.id, nodeId = node.id, nodeType = node.nodeType, sequenceNo = ++sequence, status = BuilderStepStatus.RUNNING, inputJson = mask(nodeInput)))
            run.currentNodeId = node.id
            val simulated = contract.simulate(node.config, nodeInput)
            val result = if (agent != null && node.nodeType == NodeType.AI_GENERATE.wireName) {
                simulated.copy(output = materializeAgentOutput(simulated.output, agent))
            } else simulated
            if (result.pauses) {
                step.status = BuilderStepStatus.WAITING_APPROVAL; run.status = BuilderRunStatus.WAITING_APPROVAL
                approvals.save(BuilderApproval(workspaceId = context.workspace.id, workflowId = run.workflowId, runId = run.id, approvalType = ApprovalType.EXECUTION, idempotencyKey = "run:${run.id}:node:${node.id}"))
                return
            }
            agent?.let { validateFields(it.outputSchema, projectOutput(result.output, it.outputSchema), "${node.label} 출력") }
                ?.let { throw BadRequestException("INVALID_NODE_OUTPUT", it) }
            step.status = BuilderStepStatus.SUCCEEDED; step.outputJson = mask(result.output); value = result.output
            val outgoing = graph.edges.filter { it.source == node.id }
            val edge = nextEdge(graph, node, value)
            if (node.nodeType == NodeType.CONDITION_BRANCH.wireName && outgoing.isNotEmpty()) {
                value = value + ("branchMatched" to (edge != null))
                step.outputJson = mask(value)
            }
            nodeId = edge?.target
            if (edge != null) value = bindEdge(value, edge)
        }
        finishRun(context, run, graph, value)
    }

    private fun nextEdge(graph: WorkflowGraph, node: WorkflowNode, output: Map<String, Any?>): WorkflowEdge? {
        val outgoing = graph.edges.filter { it.source == node.id }
        if (node.nodeType != NodeType.CONDITION_BRANCH.wireName) return outgoing.firstOrNull()
        return outgoing.firstOrNull { edge -> branchMatches(edge.condition, output) }
    }

    private fun bindEdge(output: Map<String, Any?>, edge: WorkflowEdge): Map<String, Any?> {
        val bound = output.toMutableMap()
        edge.bindings.forEach { (target, source) ->
            when {
                source == "context" -> bound[target] = output
                output.containsKey(source) -> bound[target] = output[source]
                else -> throw BadRequestException("INVALID_EDGE_BINDING", "${edge.id}의 '$source' 출력이 없어 '$target' 입력을 만들 수 없습니다.")
            }
        }
        return bound
    }

    private fun branchMatches(condition: String, output: Map<String, Any?>): Boolean {
        val match = Regex("^([A-Za-z][A-Za-z0-9]*)=(true|false|[A-Za-z0-9_-]+)$").matchEntire(condition.trim()) ?: return false
        val actual = output[match.groupValues[1]] ?: return false
        return actual.toString().equals(match.groupValues[2], ignoreCase = true)
    }

    private fun finishRun(context: OwnedContext, run: BuilderRun, graph: WorkflowGraph, output: Map<String, Any?>): String? {
        val design = storedDesign(context.conversation.id)
        val projected = projectOutput(output, design.proposal.outputSchema)
        val issue = simulationOutcomeIssue(graph, output) ?: validateFields(design.proposal.outputSchema, projected, "최종 출력")
        run.currentNodeId = null
        val safetyMetadata = output["externalCallPerformed"]?.let { mapOf("externalCallPerformed" to it) }.orEmpty()
        run.outputJson = mask(if (issue == null) projected + safetyMetadata else projected + safetyMetadata + ("validationError" to issue))
        run.requirementMatched = issue == null
        run.status = if (issue == null) BuilderRunStatus.SUCCEEDED else BuilderRunStatus.FAILED
        context.workflow.status = if (issue == null) WorkflowStatus.READY_TO_ACTIVATE else WorkflowStatus.SIMULATION_FAILED
        pipeline.record(context.pipeline(), "review_simulation", output.size, if (issue == null) 1 else 0)
        return null
    }

    private fun projectOutput(output: Map<String, Any?>, fields: List<FieldDefinition>): Map<String, Any?> =
        if (fields.isEmpty()) output else fields.mapNotNull { field -> output[field.name]?.let { field.name to it } }.toMap()

    private fun enrichAgentInput(agent: AgentDefinition, input: Map<String, Any?>): Map<String, Any?> {
        if (input.containsKey("text") || agent.inputSchema.none { it.required && it.name == "text" }) return input
        val text = listOf("topic", "sourceText", "message", "customerInquiry", "normalizedText", "analysis")
            .mapNotNull { key -> input[key]?.toString()?.takeIf(String::isNotBlank) }
            .joinToString("\n")
        return if (text.isBlank()) input else input + ("text" to text)
    }

    private fun materializeAgentOutput(output: Map<String, Any?>, agent: AgentDefinition): Map<String, Any?> {
        val generatedText = listOf("draftResponse", "draft", "result", "summary", "report", "reproductionSteps")
            .firstNotNullOfOrNull { output[it] as? String }
            ?: "제공된 근거와 입력을 선언된 출력 계약에 맞춰 처리한 검증용 결과입니다."
        val completed = output.toMutableMap()
        agent.outputSchema.forEach { field ->
            if (!completed.containsKey(field.name)) {
                completed[field.name] = when (field.type.lowercase()) {
                    "string" -> generatedText
                    "array" -> emptyList<Any>()
                    "object" -> emptyMap<String, Any?>()
                    "boolean" -> false
                    "number", "integer" -> 0
                    else -> generatedText
                }
            }
        }
        return completed
    }

    private fun validateFields(fields: List<FieldDefinition>, value: Map<String, Any?>, label: String): String? {
        fields.filter { it.required && !value.containsKey(it.name) }.firstOrNull()?.let { return "$label 필수 필드 '${it.name}'이 없습니다." }
        fields.forEach { field ->
            val actual = value[field.name] ?: return@forEach
            val valid = when (field.type.lowercase()) {
                "string" -> actual is String
                "array" -> actual is List<*>
                "object" -> actual is Map<*, *>
                "boolean" -> actual is Boolean
                "number", "integer" -> actual is Number
                else -> true
            }
            if (!valid) return "$label 필드 '${field.name}'의 타입이 ${field.type}이 아닙니다."
        }
        return null
    }

    private fun simulationOutcomeIssue(graph: WorkflowGraph, output: Map<String, Any?>): String? {
        if (output["branchMatched"] == false) return "조건 분기에 일치하는 안전한 실행 경로가 없습니다."
        if (graph.nodes.any { it.nodeType == NodeType.CONDITION_BRANCH.wireName } && output.keys.none { it in setOf("evidenceFound", "category", "priceWithinBudget", "qualityPassed") }) {
            return "조건 분기 결과가 최종 출력에 남지 않았습니다."
        }
        if (graph.nodes.any { it.nodeType == NodeType.KNOWLEDGE_SEARCH_MOCK.wireName } && output["evidenceFound"] == false && output["needsAssigneeReview"] != true) {
            return "검색 근거가 없지만 담당자 확인 필요 상태가 없습니다."
        }
        val generationExpected = output["evidenceFound"] != false && (output["category"] == null || output["category"].toString().equals("BUG", true))
        if (generationExpected && graph.nodes.any { it.nodeType == NodeType.AI_GENERATE.wireName }) {
            val generated = listOf("draftResponse", "draft", "result", "report", "summary", "reproductionSteps").mapNotNull { output[it]?.toString() }.firstOrNull(String::isNotBlank)
                ?: return "AI 단계가 유효한 업무 결과를 생성하지 않았습니다."
            if (generated.trim().startsWith("[Mock]")) return "Mock AI가 구조화 결과 대신 지침 또는 입력을 되풀이했습니다."
        }
        if (graph.nodes.any { it.nodeType == NodeType.DATA_CSV_COMPARE.wireName } && !output.containsKey("changedRows")) return "CSV 비교 결과가 없습니다."
        if (output.containsKey("unresolvedTool") && output["requiresUserAction"] != true) return "미지원 도구의 사용자 조치 상태가 없습니다."
        return null
    }

    @Transactional(readOnly = true)
    fun snapshot(ownerId: UUID, conversationId: UUID): BuilderSnapshot {
        val context = context(ownerId, conversationId)
        val requirementEntity = requirements.findByConversationId(conversationId)
        val requirement = requirementEntity?.let { mapper.convertValue(it.structuredJson.filterKeys { key -> key != "clarificationQuestions" }, AutomationRequirement::class.java) }
        val questions: List<ClarificationQuestion> = requirementEntity?.structuredJson?.get("clarificationQuestions")?.let { mapper.convertValue(it, mapper.typeFactory.constructCollectionType(List::class.java, ClarificationQuestion::class.java)) } ?: emptyList()
        val proposalEntity = proposals.findByConversationId(conversationId)
        val proposal = proposalEntity?.let { normalizeProposal(mapper.convertValue(it.proposalJson, AutomationProposal::class.java)) }
        val agents: List<AgentDefinition> = proposalEntity?.let { mapper.convertValue(it.agentDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java)) } ?: emptyList()
        val guides: List<GuideDefinition> = proposalEntity?.let { mapper.convertValue(it.guideDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, GuideDefinition::class.java)) } ?: emptyList()
        val version = context.workflow.currentVersionId?.let { versions.findById(it).orElse(null) }
        val graph = version?.let(::graph)
        val validation = graph?.let {
            if (requirement != null && proposal != null) validator.validate(it, requirement, proposal, agents, if ((version?.versionNo ?: 1) == 1) cumulativeInstruction(conversationId) else null)
            else validator.validate(it)
        }?.copy(graphHash = version!!.graphHash)
        return BuilderSnapshot(context.workspace.id, conversationId, context.workflow.id, context.workflow.status, requirement, questions, proposal, agents, agents.map { it.toMarkdown() }, guides, guides.map { it.toMarkdown() }, graph, validation, version?.id, context.workflow.approvedVersionId, messages.findAllByConversationIdOrderByCreatedAt(conversationId).map { BuilderMessageView(it.id, it.role, it.content, it.workflowVersionId, it.createdAt) }, versions.findAllByWorkflowIdOrderByVersionNoDesc(context.workflow.id).map { WorkflowVersionView(it.id, it.versionNo, it.graphHash, it.changeSummary, it.approved, it.templateVersionId, it.createdAt) })
    }

    @Transactional(readOnly = true)
    fun workflowSnapshot(ownerId: UUID, workflowId: UUID): BuilderSnapshot {
        val context = workflowContext(ownerId, workflowId)
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional(readOnly = true)
    fun listConversations(ownerId: UUID, purpose: BuilderConversationPurpose = BuilderConversationPurpose.AUTOMATION): List<BuilderConversationSummary> {
        val workspace = workspaces.findByOwnerId(ownerId) ?: return emptyList()
        return conversations.findTop20ByWorkspaceIdAndPurposeOrderByCreatedAtDesc(workspace.id, purpose).mapNotNull { conversation ->
            workflows.findByConversationId(conversation.id)?.let { workflow ->
                val versionNo = workflow.currentVersionId?.let { versions.findById(it).orElse(null)?.versionNo }
                BuilderConversationSummary(conversation.id, workflow.id, workflow.name, workflow.status, versionNo, workflow.updatedAt)
            }
        }
    }

    private fun agentDevelopmentInstruction(instruction: String) = agentDevelopmentPrompt(instruction)

    @Transactional
    fun activate(ownerId: UUID, workflowId: UUID, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        approvals.findByWorkspaceIdAndIdempotencyKey(context.workspace.id, idempotencyKey)?.let {
            if (it.workflowId != workflowId || it.approvalType != ApprovalType.ACTIVATION) {
                throw ConflictException("IDEMPOTENCY_KEY_REUSED", "다른 워크플로우 또는 승인 작업에서 사용한 Idempotency-Key입니다.")
            }
            return snapshot(ownerId, context.conversation.id)
        }
        if (context.workflow.status != WorkflowStatus.READY_TO_ACTIVATE) throw ConflictException("INVALID_WORKFLOW_STATE", "성공한 시뮬레이션이 있는 버전만 회사에 배치할 수 있습니다.")
        val design = storedDesign(context.conversation.id)
        BuilderMvpSupportPolicy.requireSupported(design.requirement)
        val version = currentVersion(context.workflow)
        requireValidDesign(graph(version), design.requirement, design.proposal, design.agents, cumulativeInstruction(context.conversation.id))
        if (runs.findFirstByWorkflowIdAndWorkflowVersionIdAndStatusOrderByUpdatedAtDesc(workflowId, version.id, BuilderRunStatus.SUCCEEDED) == null) {
            throw ConflictException("SIMULATION_SUCCESS_REQUIRED", "현재 버전의 성공한 시뮬레이션이 필요합니다.")
        }
        requireCompletePackage(packageRenderer.render(MetaAgentDesignBundle(
            design.requirement, emptyList(), design.proposal, design.agents, design.guides,
        )))
        teamDeployments.deploy(
            ownerId = ownerId, workspaceId = context.workspace.id, workflowId = workflowId,
            workflowVersionId = version.id, workflowName = context.workflow.name,
            objective = design.requirement.objective, agents = design.agents, guides = design.guides,
        )
        requireNotNull(version.templateVersionId) { "Output template version is required" }.also(templateCatalog::activate)
        approvals.save(BuilderApproval(workspaceId = context.workspace.id, workflowId = workflowId, approvalType = ApprovalType.ACTIVATION, idempotencyKey = idempotencyKey, status = ApprovalStatus.APPROVED, decidedBy = ownerId, decidedAt = Instant.now()))
        transition(context.workflow, WorkflowStatus.ACTIVE)
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "Workflow Version ${version.versionNo}을 우리 회사의 업무 자동화 팀으로 배치했습니다.", workflowVersionId = version.id))
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional(readOnly = true)
    fun harnessPackage(ownerId: UUID, workflowId: UUID): Map<String, String> {
        val context = workflowContext(ownerId, workflowId)
        val design = storedDesign(context.conversation.id)
        val version = currentVersion(context.workflow)
        requireValidDesign(graph(version), design.requirement, design.proposal, design.agents, cumulativeInstruction(context.conversation.id))
        return (packageRenderer.render(MetaAgentDesignBundle(
            design.requirement, emptyList(), design.proposal, design.agents, design.guides,
        )) + ("version.json" to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
            "workflowId" to workflowId,
            "workflowVersionId" to version.id,
            "versionNo" to version.versionNo,
            "graphHash" to version.graphHash,
        )) + "\n")).also(::requireCompletePackage)
    }

    @Transactional(readOnly = true)
    fun exportTFrameXFlow(ownerId: UUID, workflowId: UUID): Map<String, Any?> {
        val context = workflowContext(ownerId, workflowId)
        val design = storedDesign(context.conversation.id)
        val version = currentVersion(context.workflow)
        val definition = tframexCompiler.compilePlan(
            context.workflow.name,
            requireNotNull(design.proposal.graphPlan) { "Workflow graph plan is required" },
            design.agents,
            emptyMap(),
        )
        return linkedMapOf(
            "format" to "agentown-tframex-flow/v1",
            "tframexCommit" to TFRAMEX_COMMIT,
            "workflowId" to workflowId,
            "workflowVersionId" to version.id,
            "graphHash" to version.graphHash,
            "designBundle" to mapper.convertValue(
                MetaAgentDesignBundle(design.requirement, emptyList(), design.proposal, design.agents, design.guides),
                mapType(),
            ),
            "runtimeDefinition" to definition,
        )
    }

    @Transactional
    fun importTFrameXFlow(ownerId: UUID, workflowId: UUID, imported: TFrameXFlowImport, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        if (context.workflow.status == WorkflowStatus.STOPPED) throw ConflictException("WORKFLOW_STOPPED", "중지된 에이전트에는 Flow를 가져올 수 없습니다.")
        messages.findByConversationIdAndIdempotencyKey(context.conversation.id, idempotencyKey)?.let {
            return snapshot(ownerId, context.conversation.id)
        }
        val current = currentVersion(context.workflow)
        if (current.id != imported.baseVersionId || current.graphHash != imported.expectedGraphHash) {
            throw ConflictException("WORKFLOW_VERSION_CONFLICT", "캔버스가 최신 버전이 아닙니다. 새 버전을 불러와 다시 가져오세요.")
        }
        if (imported.tframexCommit != TFRAMEX_COMMIT) {
            throw BadRequestException("TFRAMEX_PIN_MISMATCH", "고정된 TFrameX Runtime 버전과 일치하지 않습니다.")
        }
        val bundle = runCatching { mapper.convertValue(imported.designBundle, MetaAgentDesignBundle::class.java) }
            .getOrElse { throw BadRequestException("INVALID_TFRAMEX_FLOW_IMPORT", "Agentown 설계 번들이 유효하지 않습니다.") }
        if (bundle.clarificationQuestions.isNotEmpty()) {
            throw BadRequestException("INVALID_TFRAMEX_FLOW_IMPORT", "미확정 질문이 남은 Flow는 가져올 수 없습니다.")
        }
        val graph = graphTranslator.translate(workflowId, bundle.proposal)
        requireValidDesign(graph, bundle.requirement, bundle.proposal, bundle.agentDefinitions, bundle.requirement.objective)
        val input = runCatching {
            val encoded = imported.runtimeDefinition["input"]?.toString() ?: "{}"
            mapper.readValue(encoded, mapType())
        }.getOrElse { throw BadRequestException("INVALID_TFRAMEX_FLOW_IMPORT", "TFrameX 입력 정의가 JSON 객체가 아닙니다.") }
        val expected = tframexCompiler.compilePlan(
            bundle.proposal.name,
            requireNotNull(bundle.proposal.graphPlan),
            bundle.agentDefinitions,
            input,
        )
        if (mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(expected) != mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(imported.runtimeDefinition)) {
            throw BadRequestException("TFRAMEX_FLOW_DEFINITION_MISMATCH", "설계 번들과 TFrameX 실행 정의가 일치하지 않습니다.")
        }
        restoreDesignSnapshot(context.conversation.id, mapOf(
            "requirement" to mapper.convertValue(bundle.requirement, mapType()),
            "proposal" to mapper.convertValue(bundle.proposal, mapType()),
            "agents" to mapper.convertValue(bundle.agentDefinitions, listMapType()),
            "guides" to mapper.convertValue(bundle.guideDefinitions, listMapType()),
        ))
        val saved = saveVersion(context.workflow, graph, "TFrameX Flow Import", approved = false)
        context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
        messages.save(BuilderMessage(
            conversationId = context.conversation.id,
            role = "ASSISTANT",
            content = "검증된 TFrameX Flow를 새 버전 ${saved.versionNo}으로 가져왔습니다.",
            workflowVersionId = saved.id,
            idempotencyKey = idempotencyKey,
        ))
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional
    fun stop(ownerId: UUID, workflowId: UUID, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        approvals.findByWorkspaceIdAndIdempotencyKey(context.workspace.id, idempotencyKey)?.let {
            if (it.workflowId != workflowId || it.approvalType != ApprovalType.STOP) {
                throw ConflictException("IDEMPOTENCY_KEY_REUSED", "다른 워크플로우 또는 승인 작업에서 사용한 Idempotency-Key입니다.")
            }
            return snapshot(ownerId, context.conversation.id)
        }
        if (context.workflow.status == WorkflowStatus.STOPPED) return snapshot(ownerId, context.conversation.id)
        approvals.save(BuilderApproval(workspaceId = context.workspace.id, workflowId = workflowId, approvalType = ApprovalType.STOP, idempotencyKey = idempotencyKey, status = ApprovalStatus.APPROVED, decidedBy = ownerId, decidedAt = Instant.now()))
        context.workflow.status = WorkflowStatus.STOPPED
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "자동화를 중지했습니다. Version과 실행 로그는 기록으로 보존됩니다.", workflowVersionId = context.workflow.currentVersionId))
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional(readOnly = true)
    fun activeAutomationTeams(ownerId: UUID): List<AutomationTeamView> {
        val workspace = workspaces.findByOwnerId(ownerId) ?: return emptyList()
        val active = workflows.findAllByWorkspaceIdAndStatusOrderByUpdatedAtDesc(workspace.id, WorkflowStatus.ACTIVE)
        val versionNumbers = active.flatMap { versions.findAllByWorkflowIdOrderByVersionNoDesc(it.id) }.associate { it.id to it.versionNo }
        return teamDeployments.list(ownerId, workspace.id, versionNumbers, active.associate { it.id to it.name })
    }

    private fun compileGraph(workflowId: UUID, proposal: AutomationProposal): WorkflowGraph {
        return graphTranslator.translate(workflowId, proposal)
    }

    private fun saveVersion(workflow: BuilderWorkflow, graph: WorkflowGraph, summary: String, approved: Boolean, templateOverride: BuilderWorkflowVersion? = null): BuilderWorkflowVersion {
        val previous = workflow.currentVersionId
        val nextNo = versions.findAllByWorkflowIdOrderByVersionNoDesc(workflow.id).firstOrNull()?.versionNo?.plus(1) ?: 1
        val pinned = if (templateOverride == null) {
            val design = storedDesign(workflow.conversationId)
            templateCatalog.pin(MetaAgentDesignBundle(design.requirement, emptyList(), design.proposal, design.agents, design.guides))
        } else null
        val templateVersionId = templateOverride?.templateVersionId ?: pinned?.id
            ?: throw ConflictException("OUTPUT_TEMPLATE_VERSION_REQUIRED", "승인된 출력 템플릿 버전이 필요합니다.")
        val executionContract = templateOverride?.executionContractJson ?: requireNotNull(pinned).executionContract
        val entity = versions.save(BuilderWorkflowVersion(
            workflowId = workflow.id, versionNo = nextNo, parentVersionId = previous,
            templateVersionId = templateVersionId, executionContractJson = executionContract,
            graphJson = mapper.convertValue(graph, mapType()), graphHash = validator.hash(graph),
            designSnapshotJson = designSnapshot(workflow.conversationId),
            changeSummary = summary, approved = approved,
        ))
        workflow.currentVersionId = entity.id
        return entity
    }

    private fun currentVersion(workflow: BuilderWorkflow) = workflow.currentVersionId?.let { versions.findByIdAndWorkflowId(it, workflow.id) } ?: throw ConflictException("WORKFLOW_NOT_COMPILED", "아직 컴파일된 워크플로우가 없습니다.")
    private data class StoredDesign(
        val requirement: AutomationRequirement,
        val proposal: AutomationProposal,
        val agents: List<AgentDefinition>,
        val guides: List<GuideDefinition>,
    )
    private fun storedDesign(conversationId: UUID): StoredDesign {
        val proposalEntity = proposals.findByConversationId(conversationId)
            ?: throw ConflictException("WORKFLOW_PROPOSAL_NOT_FOUND", "자동화 설계안을 찾을 수 없습니다.")
        val normalizedProposal = normalizeProposal(mapper.convertValue(proposalEntity.proposalJson, AutomationProposal::class.java))
        return StoredDesign(
            storedRequirement(conversationId),
            normalizedProposal,
            mapper.convertValue(proposalEntity.agentDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java)),
            mapper.convertValue(proposalEntity.guideDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, GuideDefinition::class.java)),
        )
    }
    private fun designSnapshot(conversationId: UUID): Map<String, Any?> {
        val design = storedDesign(conversationId)
        return mapOf(
            "requirement" to mapper.convertValue(design.requirement, mapType()),
            "proposal" to mapper.convertValue(design.proposal, mapType()),
            "agents" to mapper.convertValue(design.agents, listMapType()),
            "guides" to mapper.convertValue(design.guides, listMapType()),
        )
    }
    private fun restoreDesignSnapshot(conversationId: UUID, snapshot: Map<String, Any?>) {
        if (snapshot.isEmpty()) return
        val requirement = mapper.convertValue(snapshot["requirement"], AutomationRequirement::class.java)
        val proposal = mapper.convertValue(snapshot["proposal"], AutomationProposal::class.java)
        val agents: List<AgentDefinition> = mapper.convertValue(snapshot["agents"], mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java))
        val guides: List<GuideDefinition> = mapper.convertValue(snapshot["guides"], mapper.typeFactory.constructCollectionType(List::class.java, GuideDefinition::class.java))
        val requirementEntity = requirements.findByConversationId(conversationId)
            ?: throw ConflictException("WORKFLOW_REQUIREMENT_NOT_FOUND", "에이전트 요구사항을 찾을 수 없습니다.")
        val proposalEntity = proposals.findByConversationId(conversationId)
            ?: throw ConflictException("WORKFLOW_PROPOSAL_NOT_FOUND", "에이전트 설계안을 찾을 수 없습니다.")
        requirementEntity.structuredJson = mapper.convertValue(requirement, mapType()) + mapOf("clarificationQuestions" to emptyList<Any>())
        proposalEntity.proposalJson = mapper.convertValue(proposal, mapType())
        proposalEntity.agentDefinitionsJson = mapper.convertValue(agents, listMapType())
        proposalEntity.guideDefinitionsJson = mapper.convertValue(guides, listMapType())
        workflows.findByConversationId(conversationId)?.name = proposal.name
    }

    private fun normalizeProposal(proposal: AutomationProposal) = proposal.copy(
        graphPlan = proposal.graphPlan?.let(WorkflowGraphPlanNormalizer::normalize),
    )
    private fun requireValidDesign(
        graph: WorkflowGraph,
        requirement: AutomationRequirement,
        proposal: AutomationProposal,
        agents: List<AgentDefinition>,
        sourceInstruction: String? = null,
    ) {
        val validation = validator.validate(graph, requirement, proposal, agents, sourceInstruction)
        if (validation.valid) return
        val semanticMismatch = validation.issues.any { it.code.startsWith("MEANING_") }
        throw BadRequestException(
            if (semanticMismatch) "WORKFLOW_REQUIREMENT_MISMATCH" else "WORKFLOW_VALIDATION_FAILED",
            validation.issues.joinToString(" ") { it.message },
        )
    }
    private fun requireCompletePackage(files: Map<String, String>) {
        val required = setOf(
            "agent.yaml", "workflow.yaml", "prompts/system.md", "prompts/reviewer.md",
            "schemas/input.schema.json", "schemas/output.schema.json", "tools/tools.yaml", "mcp.json",
            "examples/sample-input.json", "runtime-targets.json", "runners/python/runner.py", ".env.example", "README.md",
            "AGENTS.md", "CODEX.md", "workflow.json", "design-bundle.json", "manifest.json",
        )
        val missing = required.filter { files[it].isNullOrBlank() }
        if (missing.isNotEmpty()) throw BadRequestException("HARNESS_PACKAGE_INVALID", "Agent Package 필수 파일이 없습니다: ${missing.joinToString()}")
        listOf("schemas/input.schema.json", "schemas/output.schema.json", "mcp.json", "workflow.json", "design-bundle.json", "manifest.json")
            .forEach { path -> runCatching { mapper.readTree(files[path]) }.getOrElse { throw BadRequestException("HARNESS_PACKAGE_INVALID", "$path JSON이 유효하지 않습니다.") } }
        if (files.keys.none { it.startsWith("agents/") } || files.keys.none { it.startsWith("guides/") }) {
            throw BadRequestException("HARNESS_PACKAGE_INVALID", "하네스 패키지 필수 파일이 없습니다: ${missing.joinToString()}")
        }
    }
    private fun storedRequirement(conversationId: UUID): AutomationRequirement {
        val entity = requirements.findByConversationId(conversationId)
            ?: throw ConflictException("WORKFLOW_REQUIREMENT_NOT_FOUND", "자동화 요구사항을 찾을 수 없습니다.")
        return mapper.convertValue(entity.structuredJson.filterKeys { it != "clarificationQuestions" }, AutomationRequirement::class.java)
    }
    private fun graph(version: BuilderWorkflowVersion): WorkflowGraph = mapper.convertValue(version.graphJson, WorkflowGraph::class.java)
    private fun cumulativeInstruction(conversationId: UUID) = messages.findAllByConversationIdOrderByCreatedAt(conversationId)
        .filter { it.role == "USER" }
        .map { it.content }
        .joinToString("\n추가 답변: ")
    private fun isRejectedDraft(context: OwnedContext): Boolean {
        val workflow = context.workflow
        if (workflow.status != WorkflowStatus.DRAFT || workflow.currentVersionId != null || workflow.approvedVersionId != null) return false
        if (requirements.findByConversationId(context.conversation.id) == null || proposals.findByConversationId(context.conversation.id) == null) return false
        return messages.findAllByConversationIdOrderByCreatedAt(context.conversation.id)
            .lastOrNull { it.role == "ASSISTANT" }
            ?.content == DESIGN_REJECTED_MESSAGE
    }
    private fun isSlackToEmailPatch(value: String) =
        listOf("이메일", "메일").any(value::contains) &&
            listOf("Slack", "슬랙").any { value.contains(it, true) } &&
            listOf("바꿔", "변경", "대신", "하지 말고").any(value::contains) &&
            listOf("제거", "삭제").none(value::contains)
    private fun isGraphDeletionPatch(value: String) =
        listOf("제거", "삭제").any(value::contains) &&
            listOf("Slack", "슬랙", "노드", "승인", "담당자", "이메일", "메일", "전송", "답변", "회신").any { value.contains(it, true) }
    private fun isHumanApprovalAdditionPatch(value: String): Boolean {
        if (listOf("제거", "삭제").any(value::contains)) return false
        val approval = listOf("승인", "담당자", "확인", "검토").any(value::contains)
        val delivery = listOf("Slack", "슬랙", "전송", "답변", "회신").any { value.contains(it, true) }
        val approvalGate = value.contains("추가") || listOf(
            "승인 후", "승인한 경우", "승인하고", "확인 후", "확인하고", "검토 후", "검토하고", "경우에만", "전 담당자", "전 승인",
        ).any(value::contains)
        return approval && delivery && approvalGate
    }
    private fun isPatchInstruction(value: String): Boolean {
        val changeVerb = listOf("추가", "제거", "삭제", "바꿔", "변경", "대신", "하지 말고").any(value::contains)
        val graphTarget = listOf("승인", "담당자", "Slack", "슬랙", "이메일", "메일", "전송", "답변", "노드", "실행 시간", "요약", "단계").any { value.contains(it, true) }
        return changeVerb && graphTarget
    }
    private fun isCsvSummaryPatch(value: String) = value.contains("요약") && listOf("변경", "중요", "사람이 이해").any(value::contains)
    private fun isOutputTemplatePatch(value: String) = listOf("숫자를 더", "너무 길", "짧게", "관심 종목", "호재", "악재").any(value::contains)
    private fun requireIdempotency(key: String) { if (key.isBlank() || key.length > 120) throw BadRequestException("IDEMPOTENCY_KEY_REQUIRED", "유효한 Idempotency-Key가 필요합니다.") }
    private fun mask(value: Map<String, Any?>): Map<String, Any?> = value.mapValues { (key, item) -> if (key.contains("token", true) || key.contains("secret", true) || key.contains("password", true)) "***" else item }

    private data class OwnedContext(val ownerId: UUID, val workspace: BuilderWorkspace, val conversation: BuilderConversation, val workflow: BuilderWorkflow) {
        fun pipeline(jobId: UUID? = null) = PipelineContext(UUID.randomUUID(), ownerId, workspace.id, conversation.id, workflow.id, jobId)
    }
    private fun context(ownerId: UUID, conversationId: UUID): OwnedContext {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val conversation = conversations.findByIdAndWorkspaceId(conversationId, workspace.id) ?: throw NotFoundException("BUILDER_CONVERSATION_NOT_FOUND", "대화를 찾을 수 없습니다.")
        val workflow = workflows.findByConversationId(conversationId) ?: throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
        return OwnedContext(ownerId, workspace, conversation, workflow)
    }
    private fun workflowContext(ownerId: UUID, workflowId: UUID): OwnedContext {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val workflow = workflows.findByIdAndWorkspaceId(workflowId, workspace.id) ?: throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
        val conversation = conversations.findByIdAndWorkspaceId(workflow.conversationId, workspace.id) ?: throw NotFoundException("BUILDER_CONVERSATION_NOT_FOUND", "대화를 찾을 수 없습니다.")
        return OwnedContext(ownerId, workspace, conversation, workflow)
    }

    private fun transition(workflow: BuilderWorkflow, next: WorkflowStatus) {
        val allowed = transitions[workflow.status].orEmpty()
        if (next !in allowed) throw ConflictException("INVALID_WORKFLOW_STATE_TRANSITION", "${workflow.status}에서 $next 상태로 전환할 수 없습니다.")
        workflow.status = next
    }

    private fun runView(run: BuilderRun): RunView {
        val steps = stepRuns.findAllByRunIdOrderBySequenceNo(run.id).map { StepRunView(it.nodeId, it.nodeType, it.sequenceNo, it.status, it.inputJson, it.outputJson, it.errorMessage) }
        return RunView(run.id, run.status, run.runMode, run.currentNodeId, run.templateVersionId, run.outputJson, run.requirementMatched, steps, approvals.findByRunIdAndStatus(run.id, ApprovalStatus.PENDING)?.id, run.attemptCount, run.failureCode, run.failureMessage)
    }

    private fun mapType() = object : TypeReference<Map<String, Any?>>() {}
    private fun listMapType() = object : TypeReference<List<Map<String, Any?>>>() {}

    companion object {
        private const val DESIGN_REJECTED_MESSAGE = "설계가 반려되었습니다. 수정할 내용을 자연어로 알려 주세요."
        private const val UNSUPPORTED_GRAPH_PATCH_MESSAGE = "요청한 변경은 아직 지원하지 않습니다. 현재 가능한 수정은 출력 템플릿 조정, Slack 전송을 이메일로 변경, Slack 답변 전 담당자 승인 추가입니다. 기존 자동화는 변경되지 않았습니다."
        private val transitions = mapOf(
            WorkflowStatus.DRAFT to setOf(WorkflowStatus.NEEDS_CLARIFICATION, WorkflowStatus.PROPOSAL_READY),
            WorkflowStatus.NEEDS_CLARIFICATION to setOf(WorkflowStatus.PROPOSAL_READY),
            WorkflowStatus.PROPOSAL_READY to setOf(WorkflowStatus.WAITING_DESIGN_APPROVAL),
            WorkflowStatus.WAITING_DESIGN_APPROVAL to setOf(WorkflowStatus.APPROVED, WorkflowStatus.DRAFT),
            WorkflowStatus.APPROVED to setOf(WorkflowStatus.COMPILING),
            WorkflowStatus.COMPILING to setOf(WorkflowStatus.VALIDATING, WorkflowStatus.FAILED),
            WorkflowStatus.VALIDATING to setOf(WorkflowStatus.READY_TO_SIMULATE, WorkflowStatus.FAILED),
            WorkflowStatus.READY_TO_SIMULATE to setOf(WorkflowStatus.SIMULATING),
            WorkflowStatus.SIMULATING to setOf(WorkflowStatus.SIMULATION_FAILED, WorkflowStatus.READY_TO_ACTIVATE),
            WorkflowStatus.SIMULATION_FAILED to setOf(WorkflowStatus.READY_TO_SIMULATE),
            WorkflowStatus.READY_TO_ACTIVATE to setOf(WorkflowStatus.SIMULATING, WorkflowStatus.ACTIVE),
            WorkflowStatus.ACTIVE to emptySet(), WorkflowStatus.FAILED to setOf(WorkflowStatus.DRAFT),
            WorkflowStatus.STOPPED to emptySet(),
        )
    }
}

internal fun agentDevelopmentPrompt(instruction: String) = """
    다음 요청은 업무 자동화 배치가 아니라 사용자가 대화로 사용할 AI 에이전트 개발 요청입니다.
    사용자가 별도로 지정하지 않았다면 실행 트리거는 '사용자가 채팅에서 요청할 때', 입력은 '현재 대화와 사용자 메시지',
    출력은 '대화 화면의 에이전트 응답', 외부 서비스 연동은 '없음'으로 가정하세요.
    완성된 에이전트 설계는 사용자가 검토하고 승인한 뒤 테스트하며, 승인 전 외부 작업은 수행하지 않습니다.
    이 기본값들은 차단 질문으로 되묻지 말고 assumptions에 기록하세요. 요청 의미에 필요한 에이전트, 역할, 도구, 스킬,
    메모리, 협업 순서와 검증 시나리오를 설계하되 고정 예시 흐름이나 사용자가 말하지 않은 외부 커넥터를 추가하지 마세요.

    사용자 요청:
    ${instruction.trim()}
""".trimIndent()
