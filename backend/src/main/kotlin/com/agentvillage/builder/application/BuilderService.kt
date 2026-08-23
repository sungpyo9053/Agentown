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
data class WorkflowVersionView(val id: UUID, val versionNo: Int, val graphHash: String, val changeSummary: String, val approved: Boolean, val createdAt: Instant)
data class StepRunView(val nodeId: String, val nodeType: String, val sequenceNo: Int, val status: BuilderStepStatus, val input: Map<String, Any?>, val output: Map<String, Any?>?, val errorMessage: String?)
data class RunView(val id: UUID, val status: BuilderRunStatus, val currentNodeId: String?, val output: Map<String, Any?>?, val requirementMatched: Boolean?, val steps: List<StepRunView>, val pendingApprovalId: UUID?)
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
    private val validator: WorkflowGraphValidator,
    private val catalog: WorkflowNodeCatalog,
    private val mapper: ObjectMapper,
) {
    @Transactional
    fun createConversation(ownerId: UUID, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val workspace = workspaces.findByOwnerId(ownerId) ?: workspaces.save(BuilderWorkspace(ownerId = ownerId))
        conversations.findByWorkspaceIdAndIdempotencyKey(workspace.id, idempotencyKey)?.let { return snapshot(ownerId, it.id) }
        val workflowId = UUID.randomUUID()
        val conversation = conversations.save(BuilderConversation(workspaceId = workspace.id, workflowId = workflowId, idempotencyKey = idempotencyKey))
        workflows.save(BuilderWorkflow(id = workflowId, workspaceId = workspace.id, conversationId = conversation.id, name = "새 업무 자동화"))
        return snapshot(ownerId, conversation.id)
    }

    @Transactional
    fun sendMessage(ownerId: UUID, conversationId: UUID, instruction: String, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = context(ownerId, conversationId)
        messages.findByConversationIdAndIdempotencyKey(conversationId, idempotencyKey)?.let { return snapshot(ownerId, conversationId) }
        val workflow = context.workflow
        val message = messages.save(BuilderMessage(conversationId = conversationId, role = "USER", content = instruction.trim(), workflowVersionId = workflow.currentVersionId, idempotencyKey = idempotencyKey))
        if (workflow.currentVersionId != null && isPatchInstruction(instruction)) {
            applyNaturalPatch(context, instruction, message.id)
            return snapshot(ownerId, conversationId)
        }
        if (requirements.findByConversationId(conversationId) == null) {
            analyzeAndDesign(context, instruction)
        } else if (workflow.status == WorkflowStatus.NEEDS_CLARIFICATION) {
            design(context, originalInstruction(conversationId) + "\n추가 답변: " + instruction)
        } else {
            throw ConflictException("BUILDER_MESSAGE_NOT_APPLICABLE", "현재 단계에서는 수정 요청이나 승인 작업을 사용해 주세요.")
        }
        return snapshot(ownerId, conversationId)
    }

    private fun analyzeAndDesign(context: OwnedContext, instruction: String) {
        val (requirement, questions) = pipeline.analyze(context.pipeline, instruction)
        require(requirement.objective.isNotBlank() && requirement.steps.isNotEmpty())
        val map = mapper.convertValue(requirement, object : TypeReference<Map<String, Any?>>() {}).toMutableMap()
        map["clarificationQuestions"] = mapper.convertValue(questions, object : TypeReference<List<Map<String, Any?>>>() {})
        requirements.save(BuilderRequirementEntity(conversationId = context.conversation.id, structuredJson = map))
        if (questions.isNotEmpty()) {
            transition(context.workflow, WorkflowStatus.NEEDS_CLARIFICATION)
            messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = questions.joinToString("\n") { it.question }))
        } else design(context, instruction)
    }

    private fun design(context: OwnedContext, instruction: String) {
        val (proposal, agents, guides) = pipeline.design(context.pipeline, instruction)
        proposals.findByConversationId(context.conversation.id)?.let {
            it.proposalJson = mapper.convertValue(proposal, mapType())
            it.agentDefinitionsJson = mapper.convertValue(agents, listMapType())
            it.guideDefinitionsJson = mapper.convertValue(guides, listMapType())
        } ?: proposals.save(BuilderProposalEntity(conversationId = context.conversation.id, proposalJson = mapper.convertValue(proposal, mapType()), agentDefinitionsJson = mapper.convertValue(agents, listMapType()), guideDefinitionsJson = mapper.convertValue(guides, listMapType())))
        context.workflow.name = proposal.name
        if (context.workflow.status == WorkflowStatus.NEEDS_CLARIFICATION) transition(context.workflow, WorkflowStatus.PROPOSAL_READY) else transition(context.workflow, WorkflowStatus.PROPOSAL_READY)
        transition(context.workflow, WorkflowStatus.WAITING_DESIGN_APPROVAL)
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "자동화 설계안이 준비되었습니다. 기능·에이전트·가이드를 확인하고 설계를 승인해 주세요."))
    }

    @Transactional
    fun decideDesign(ownerId: UUID, workflowId: UUID, approve: Boolean, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        approvals.findByWorkspaceIdAndIdempotencyKey(context.workspace.id, idempotencyKey)?.let { return snapshot(ownerId, context.conversation.id) }
        if (context.workflow.status != WorkflowStatus.WAITING_DESIGN_APPROVAL) throw ConflictException("INVALID_WORKFLOW_STATE", "설계 승인 대기 상태에서만 처리할 수 있습니다.")
        val approval = BuilderApproval(workspaceId = context.workspace.id, workflowId = workflowId, approvalType = ApprovalType.DESIGN, idempotencyKey = idempotencyKey, status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED, decidedBy = ownerId, decidedAt = Instant.now())
        approvals.save(approval)
        if (!approve) {
            transition(context.workflow, WorkflowStatus.DRAFT)
            messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "설계가 반려되었습니다. 수정할 내용을 자연어로 알려 주세요."))
            return snapshot(ownerId, context.conversation.id)
        }
        transition(context.workflow, WorkflowStatus.APPROVED)
        transition(context.workflow, WorkflowStatus.COMPILING)
        val graph = compileGraph(workflowId)
        pipeline.record(context.pipeline, "compile_workflow", 3, graph.nodes.size)
        transition(context.workflow, WorkflowStatus.VALIDATING)
        val validation = validator.validate(graph)
        pipeline.record(context.pipeline, "validate_workflow", graph.nodes.size, validation.issues.size)
        if (!validation.valid) { transition(context.workflow, WorkflowStatus.FAILED); throw BadRequestException("WORKFLOW_VALIDATION_FAILED", validation.issues.joinToString(" ") { it.message }) }
        val version = saveVersion(context.workflow, graph, "최초 승인 설계 컴파일", approved = true)
        context.workflow.approvedVersionId = version.id
        transition(context.workflow, WorkflowStatus.READY_TO_SIMULATE)
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "설계 승인과 서버 검증이 완료되었습니다. 캔버스와 샘플 시뮬레이션을 사용할 수 있습니다.", workflowVersionId = version.id))
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional
    fun applyPatch(ownerId: UUID, workflowId: UUID, instruction: String, baseVersionId: UUID, expectedHash: String, idempotencyKey: String): BuilderSnapshot {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        messages.findByConversationIdAndIdempotencyKey(context.conversation.id, idempotencyKey)?.let { return snapshot(ownerId, context.conversation.id) }
        val current = currentVersion(context.workflow)
        if (current.id != baseVersionId || current.graphHash != expectedHash) throw ConflictException("WORKFLOW_VERSION_CONFLICT", "캔버스가 최신 버전이 아닙니다. 새 버전을 불러와 다시 적용해 주세요.")
        val message = messages.save(BuilderMessage(conversationId = context.conversation.id, role = "USER", content = instruction, workflowVersionId = current.id, idempotencyKey = idempotencyKey))
        applyNaturalPatch(context, instruction, message.id)
        return snapshot(ownerId, context.conversation.id)
    }

    private fun applyNaturalPatch(context: OwnedContext, instruction: String, sourceMessageId: UUID) {
        val current = currentVersion(context.workflow)
        val graph = graph(current)
        if (!isPatchInstruction(instruction)) throw BadRequestException("UNSUPPORTED_GRAPH_PATCH", "MVP에서는 'Slack 답변 전 담당자 승인 추가' 수정만 지원합니다.")
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
        pipeline.record(context.pipeline, "compile_workflow", patch.operations.size, patched.nodes.size)
        pipeline.record(context.pipeline, "validate_workflow", patched.nodes.size, 0)
        context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "Graph Patch를 검증해 새 버전 ${version.versionNo}로 저장했습니다: ${patch.summary}", workflowVersionId = version.id))
    }

    private fun applyPatch(graph: WorkflowGraph, patch: GraphPatch): WorkflowGraph {
        val nodes = graph.nodes.toMutableList(); val edges = graph.edges.toMutableList()
        patch.operations.forEach { operation -> when (operation) {
            is AddNode -> nodes += operation.node
            is RemoveNode -> { nodes.removeIf { it.id == operation.nodeId }; edges.removeIf { it.source == operation.nodeId || it.target == operation.nodeId } }
            is UpdateNodeConfig -> { val index = nodes.indexOfFirst { it.id == operation.nodeId }; if (index < 0) throw BadRequestException("PATCH_NODE_NOT_FOUND", operation.nodeId); nodes[index] = nodes[index].copy(config = nodes[index].config + operation.config) }
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
        messages.findByConversationIdAndIdempotencyKey(context.conversation.id, idempotencyKey)?.let { return snapshot(ownerId, context.conversation.id) }
        val target = versions.findByIdAndWorkflowId(versionId, workflowId) ?: throw NotFoundException("WORKFLOW_VERSION_NOT_FOUND", "버전을 찾을 수 없습니다.")
        val restored = saveVersion(context.workflow, graph(target), "버전 ${target.versionNo} 복원", approved = false)
        context.workflow.status = WorkflowStatus.READY_TO_SIMULATE
        messages.save(BuilderMessage(conversationId = context.conversation.id, role = "ASSISTANT", content = "버전 ${target.versionNo}을 새 버전 ${restored.versionNo}으로 복원했습니다.", workflowVersionId = restored.id, idempotencyKey = idempotencyKey))
        return snapshot(ownerId, context.conversation.id)
    }

    @Transactional
    fun startSimulation(ownerId: UUID, workflowId: UUID, input: Map<String, Any?>, idempotencyKey: String): RunView {
        requireIdempotency(idempotencyKey)
        val context = workflowContext(ownerId, workflowId)
        runs.findByWorkspaceIdAndIdempotencyKey(context.workspace.id, idempotencyKey)?.let { return runView(it) }
        if (context.workflow.status !in setOf(WorkflowStatus.READY_TO_SIMULATE, WorkflowStatus.READY_TO_ACTIVATE)) throw ConflictException("INVALID_WORKFLOW_STATE", "시뮬레이션 준비 상태가 아닙니다.")
        val version = currentVersion(context.workflow)
        val validation = validator.validate(graph(version)); if (!validation.valid) throw BadRequestException("WORKFLOW_VALIDATION_FAILED", "현재 그래프가 유효하지 않습니다.")
        context.workflow.status = WorkflowStatus.SIMULATING
        val run = runs.save(BuilderRun(workspaceId = context.workspace.id, workflowId = workflowId, workflowVersionId = version.id, inputJson = mask(input), idempotencyKey = idempotencyKey))
        pipeline.record(context.pipeline, "simulate_workflow", input.size, graph(version).nodes.size)
        executeFrom(context, run, graph(version), graph(version).entryNodeId, input)
        return runView(run)
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
        approvals.findByWorkspaceIdAndIdempotencyKey(workspace.id, idempotencyKey)?.let { return runView(run) }
        if (run.status != BuilderRunStatus.WAITING_APPROVAL) throw ConflictException("RUN_NOT_WAITING_APPROVAL", "승인 대기 실행이 아닙니다.")
        val approval = approvals.findByRunIdAndStatus(runId, ApprovalStatus.PENDING) ?: throw NotFoundException("APPROVAL_NOT_FOUND", "승인 요청을 찾을 수 없습니다.")
        approval.idempotencyKey = idempotencyKey; approval.status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED; approval.decidedBy = ownerId; approval.decidedAt = Instant.now()
        val waiting = stepRuns.findAllByRunIdOrderBySequenceNo(runId).last { it.status == BuilderStepStatus.WAITING_APPROVAL }
        if (!approve) { waiting.status = BuilderStepStatus.FAILED; waiting.errorMessage = "사용자가 실행을 거절했습니다."; run.status = BuilderRunStatus.FAILED; workflowContext(ownerId, run.workflowId).workflow.status = WorkflowStatus.SIMULATION_FAILED; return runView(run) }
        waiting.status = BuilderStepStatus.SUCCEEDED; waiting.outputJson = waiting.inputJson + ("approved" to true)
        val context = workflowContext(ownerId, run.workflowId); val graph = graph(versions.findById(run.workflowVersionId).orElseThrow())
        val next = graph.edges.firstOrNull { it.source == waiting.nodeId }?.target ?: finishRun(context, run, waiting.outputJson.orEmpty())
        if (next is String) executeFrom(context, run, graph, next, waiting.outputJson.orEmpty())
        return runView(run)
    }

    private fun executeFrom(context: OwnedContext, run: BuilderRun, graph: WorkflowGraph, startNodeId: String, initialInput: Map<String, Any?>) {
        var nodeId: String? = startNodeId; var value = initialInput; var sequence = stepRuns.findAllByRunIdOrderBySequenceNo(run.id).size
        while (nodeId != null) {
            val node = graph.nodes.first { it.id == nodeId }; val contract = catalog.require(node.nodeType)
            contract.validateInput(value).takeIf { it.isNotEmpty() }?.let { throw BadRequestException("INVALID_NODE_INPUT", it.joinToString()) }
            val step = stepRuns.save(BuilderStepRun(runId = run.id, nodeId = node.id, nodeType = node.nodeType, sequenceNo = ++sequence, status = BuilderStepStatus.RUNNING, inputJson = mask(value)))
            run.currentNodeId = node.id
            val result = contract.simulate(node.config, value)
            if (result.pauses) {
                step.status = BuilderStepStatus.WAITING_APPROVAL; run.status = BuilderRunStatus.WAITING_APPROVAL
                approvals.save(BuilderApproval(workspaceId = context.workspace.id, workflowId = run.workflowId, runId = run.id, approvalType = ApprovalType.EXECUTION, idempotencyKey = "run:${run.id}:node:${node.id}"))
                return
            }
            step.status = BuilderStepStatus.SUCCEEDED; step.outputJson = mask(result.output); value = result.output
            nodeId = graph.edges.firstOrNull { it.source == node.id }?.target
        }
        finishRun(context, run, value)
    }

    private fun finishRun(context: OwnedContext, run: BuilderRun, output: Map<String, Any?>): String? {
        run.status = BuilderRunStatus.SUCCEEDED; run.currentNodeId = null; run.outputJson = mask(output); run.requirementMatched = output["externalCallPerformed"] == false
        context.workflow.status = WorkflowStatus.READY_TO_ACTIVATE
        pipeline.record(context.pipeline, "review_simulation", output.size, 1)
        return null
    }

    @Transactional(readOnly = true)
    fun snapshot(ownerId: UUID, conversationId: UUID): BuilderSnapshot {
        val context = context(ownerId, conversationId)
        val requirementEntity = requirements.findByConversationId(conversationId)
        val requirement = requirementEntity?.let { mapper.convertValue(it.structuredJson.filterKeys { key -> key != "clarificationQuestions" }, AutomationRequirement::class.java) }
        val questions: List<ClarificationQuestion> = requirementEntity?.structuredJson?.get("clarificationQuestions")?.let { mapper.convertValue(it, mapper.typeFactory.constructCollectionType(List::class.java, ClarificationQuestion::class.java)) } ?: emptyList()
        val proposalEntity = proposals.findByConversationId(conversationId)
        val proposal = proposalEntity?.let { mapper.convertValue(it.proposalJson, AutomationProposal::class.java) }
        val agents: List<AgentDefinition> = proposalEntity?.let { mapper.convertValue(it.agentDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java)) } ?: emptyList()
        val guides: List<GuideDefinition> = proposalEntity?.let { mapper.convertValue(it.guideDefinitionsJson, mapper.typeFactory.constructCollectionType(List::class.java, GuideDefinition::class.java)) } ?: emptyList()
        val version = context.workflow.currentVersionId?.let { versions.findById(it).orElse(null) }
        val graph = version?.let(::graph)
        return BuilderSnapshot(context.workspace.id, conversationId, context.workflow.id, context.workflow.status, requirement, questions, proposal, agents, agents.map { it.toMarkdown() }, guides, guides.map { it.toMarkdown() }, graph, graph?.let(validator::validate), version?.id, context.workflow.approvedVersionId, messages.findAllByConversationIdOrderByCreatedAt(conversationId).map { BuilderMessageView(it.id, it.role, it.content, it.workflowVersionId, it.createdAt) }, versions.findAllByWorkflowIdOrderByVersionNoDesc(context.workflow.id).map { WorkflowVersionView(it.id, it.versionNo, it.graphHash, it.changeSummary, it.approved, it.createdAt) })
    }

    @Transactional(readOnly = true)
    fun workflowSnapshot(ownerId: UUID, workflowId: UUID): BuilderSnapshot {
        val context = workflowContext(ownerId, workflowId)
        return snapshot(ownerId, context.conversation.id)
    }

    private fun compileGraph(workflowId: UUID): WorkflowGraph {
        val nodes = listOf(
            WorkflowNode("slack-trigger", NodeType.SLACK_NEW_MESSAGE_MOCK.wireName, "Slack 문의 수신 (Mock)", NodePosition(40.0, 100.0)),
            WorkflowNode("notion-search", NodeType.NOTION_SEARCH_MOCK.wireName, "Notion FAQ 검색 (Mock)", NodePosition(300.0, 100.0), mapOf("database" to "FAQ")),
            WorkflowNode("answer-draft", NodeType.AI_GENERATE.wireName, "AI 답변 초안", NodePosition(560.0, 100.0), mapOf("instruction" to "FAQ 근거로 답변 초안 작성")),
            WorkflowNode("human-approval", NodeType.HUMAN_APPROVAL.wireName, "담당자 승인", NodePosition(820.0, 100.0), mapOf("approver" to "담당자")),
            WorkflowNode("slack-reply", NodeType.SLACK_REPLY_MOCK.wireName, "Slack 스레드 답변 (Mock)", NodePosition(1080.0, 100.0)),
        )
        return WorkflowGraph(workflowId = workflowId, entryNodeId = nodes.first().id, nodes = nodes, edges = nodes.zipWithNext().mapIndexed { index, (source, target) -> WorkflowEdge("edge-${index + 1}", source.id, target.id) })
    }

    private fun saveVersion(workflow: BuilderWorkflow, graph: WorkflowGraph, summary: String, approved: Boolean): BuilderWorkflowVersion {
        val previous = workflow.currentVersionId
        val nextNo = versions.findAllByWorkflowIdOrderByVersionNoDesc(workflow.id).firstOrNull()?.versionNo?.plus(1) ?: 1
        val entity = versions.save(BuilderWorkflowVersion(workflowId = workflow.id, versionNo = nextNo, parentVersionId = previous, graphJson = mapper.convertValue(graph, mapType()), graphHash = validator.hash(graph), changeSummary = summary, approved = approved))
        workflow.currentVersionId = entity.id
        return entity
    }

    private fun currentVersion(workflow: BuilderWorkflow) = workflow.currentVersionId?.let { versions.findByIdAndWorkflowId(it, workflow.id) } ?: throw ConflictException("WORKFLOW_NOT_COMPILED", "아직 컴파일된 워크플로우가 없습니다.")
    private fun graph(version: BuilderWorkflowVersion): WorkflowGraph = mapper.convertValue(version.graphJson, WorkflowGraph::class.java)
    private fun originalInstruction(conversationId: UUID) = messages.findAllByConversationIdOrderByCreatedAt(conversationId).firstOrNull { it.role == "USER" }?.content.orEmpty()
    private fun isPatchInstruction(value: String) =
        (value.contains("승인") || value.contains("담당자") || value.contains("확인")) &&
            (value.contains("Slack", true) || value.contains("슬랙") || value.contains("전송") || value.contains("답변")) &&
            (value.contains("추가") || value.contains("전") || value.contains("바꿔") || value.contains("경우"))
    private fun requireIdempotency(key: String) { if (key.isBlank() || key.length > 120) throw BadRequestException("IDEMPOTENCY_KEY_REQUIRED", "유효한 Idempotency-Key가 필요합니다.") }
    private fun mask(value: Map<String, Any?>): Map<String, Any?> = value.mapValues { (key, item) -> if (key.contains("token", true) || key.contains("secret", true) || key.contains("password", true)) "***" else item }

    private data class OwnedContext(val workspace: BuilderWorkspace, val conversation: BuilderConversation, val workflow: BuilderWorkflow) {
        val pipeline get() = PipelineContext(UUID.randomUUID(), workspace.id, conversation.id, workflow.id)
    }
    private fun context(ownerId: UUID, conversationId: UUID): OwnedContext {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val conversation = conversations.findByIdAndWorkspaceId(conversationId, workspace.id) ?: throw NotFoundException("BUILDER_CONVERSATION_NOT_FOUND", "대화를 찾을 수 없습니다.")
        val workflow = workflows.findByConversationId(conversationId) ?: throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
        return OwnedContext(workspace, conversation, workflow)
    }
    private fun workflowContext(ownerId: UUID, workflowId: UUID): OwnedContext {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val workflow = workflows.findByIdAndWorkspaceId(workflowId, workspace.id) ?: throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
        val conversation = conversations.findByIdAndWorkspaceId(workflow.conversationId, workspace.id) ?: throw NotFoundException("BUILDER_CONVERSATION_NOT_FOUND", "대화를 찾을 수 없습니다.")
        return OwnedContext(workspace, conversation, workflow)
    }

    private fun transition(workflow: BuilderWorkflow, next: WorkflowStatus) {
        val allowed = transitions[workflow.status].orEmpty()
        if (next !in allowed) throw ConflictException("INVALID_WORKFLOW_STATE_TRANSITION", "${workflow.status}에서 $next 상태로 전환할 수 없습니다.")
        workflow.status = next
    }

    private fun runView(run: BuilderRun): RunView {
        val steps = stepRuns.findAllByRunIdOrderBySequenceNo(run.id).map { StepRunView(it.nodeId, it.nodeType, it.sequenceNo, it.status, it.inputJson, it.outputJson, it.errorMessage) }
        return RunView(run.id, run.status, run.currentNodeId, run.outputJson, run.requirementMatched, steps, approvals.findByRunIdAndStatus(run.id, ApprovalStatus.PENDING)?.id)
    }

    private fun mapType() = object : TypeReference<Map<String, Any?>>() {}
    private fun listMapType() = object : TypeReference<List<Map<String, Any?>>>() {}

    companion object {
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
        )
    }
}
