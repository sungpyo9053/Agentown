package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.*
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.agentvillage.llmcredential.domain.LlmProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val STALE_NOTION_FAILURE_CODE = "NOTION_PAGE_CREATE_AMBIGUOUS"
private const val STALE_NOTION_FAILURE_MESSAGE =
    "Notion 발행 결과를 확인하지 못했습니다. 중복 페이지 생성을 막기 위해 이 실행은 재시도할 수 없습니다. 대상 Notion 페이지에서 생성 여부를 확인해 주세요."

data class ProductionRunRequest(
    val input: Map<String, Any?> = emptyMap(),
    val notionConnectionId: UUID,
    val notionParentPageId: String,
)
data class ProductionRunHistoryEntry(
    val run: RunView,
    val destinationConnectionId: UUID,
)
data class ProductionContentOutput(
    val title: String = "",
    val paragraphs: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val qualityChecks: Map<String, Boolean> = emptyMap(),
)
private fun validateProductionOutput(output: ProductionContentOutput) {
    if (output.title.isBlank() || output.title.length > 200 || output.paragraphs.isEmpty() || output.paragraphs.size > 40 || output.paragraphs.any { it.isBlank() || it.length > 1900 }) {
        throw BadRequestException("PRODUCTION_CONTENT_INVALID", "생성 결과의 제목 또는 본문 구조가 유효하지 않습니다.")
    }
    if (output.evidence.size > 20 || output.evidence.any { it.isBlank() || it.length > 500 }) {
        throw BadRequestException("PRODUCTION_CONTENT_INVALID", "생성 결과의 근거 구조가 유효하지 않습니다.")
    }
    val requiredChecks = setOf("factsSeparatedFromInterpretation", "unsupportedClaimsAbsent", "readyForHumanReview")
    if (output.qualityChecks.keys != requiredChecks || output.qualityChecks.values.any { !it }) {
        throw BadRequestException("PRODUCTION_QUALITY_FAILED", "필수 품질 검사를 모두 통과하지 못한 결과는 승인 요청으로 저장하지 않습니다.")
    }
}
data class ProductionRunRequested(val ownerId: UUID, val runId: UUID)
data class ProductionGenerationContext(
    val ownerId: UUID,
    val runId: UUID,
    val workflowGraph: WorkflowGraph,
    val executionContract: Map<String, Any?>,
    val input: Map<String, Any?>,
    val generationNodeId: String,
    val approvalNodeId: String,
    val writeNodeId: String,
)

interface ProductionContentGenerator {
    fun generate(context: ProductionGenerationContext): ProductionContentOutput
}

@Component
class CodexProductionContentGenerator(
    private val credentials: CredentialDirectory,
    private val runner: CodexCliRunner,
    private val usageLimiter: BuilderUsageLimiter,
    private val mapper: ObjectMapper,
    @Value("\${builder.production.model:gpt-5.6-luna}") private val model: String,
) : ProductionContentGenerator {
    override fun generate(context: ProductionGenerationContext): ProductionContentOutput {
        val prompt = """
            당신은 Agentown의 실제 업무 결과 생성기다. 임의 코드나 외부 작업을 수행하지 말고 제공된 자료만 사용한다.
            업무 요구사항, 승인된 출력 계약, 사용자 입력에 맞는 검토용 결과를 JSON Schema로 반환한다.
            사실과 해석을 구분하고, 입력에 없는 사실을 만들지 않으며, 사람이 승인하기 전의 초안임을 전제로 한다.
            evidence에는 사용한 입력 근거를 짧게 기록한다. 모든 결과 문장은 한국어로 작성한다.

            고정된 Workflow Graph: ${mapper.writeValueAsString(context.workflowGraph)}
            승인된 출력 계약: ${mapper.writeValueAsString(context.executionContract)}
            사용자 입력: ${mapper.writeValueAsString(mask(context.input))}
        """.trimIndent()
        val credential = credentials.findLatestActive(context.ownerId, LlmProvider.OPENAI)
        val raw = when {
            credential != null -> credentials.withDecrypted(credential.id, context.ownerId, LlmProvider.OPENAI) { secret, _ -> runner.executeContent(secret, model, prompt) }
            usageLimiter.isUnlimited(context.ownerId) -> runner.executeContentWithSharedAuth(model, prompt)
            else -> throw BadRequestException("PRODUCTION_OPENAI_CREDENTIAL_REQUIRED", "실제 업무 결과 생성에는 설정에서 검증한 OpenAI API 키가 필요합니다.")
        }
        return runCatching { mapper.readValue(raw, ProductionContentOutput::class.java) }
            .getOrElse { throw BadRequestException("PRODUCTION_CONTENT_INVALID", "실제 업무 결과가 승인된 구조를 충족하지 못했습니다.") }
            .also(::validateProductionOutput)
    }

    private fun mask(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to if (Regex("(?i).*(token|secret|password|api.?key).*").matches(key.toString())) "***" else mask(item) }
        is List<*> -> value.map(::mask)
        is String -> value.take(20_000)
        else -> value
    }
}

@Service
class BuilderProductionExecutionService(
    private val workspaces: BuilderWorkspaceRepository,
    private val workflows: BuilderWorkflowRepository,
    private val versions: BuilderWorkflowVersionRepository,
    private val runs: BuilderRunRepository,
    private val steps: BuilderStepRunRepository,
    private val approvals: BuilderApprovalRepository,
    private val notion: BuilderNotionExecutionPort,
    private val publisher: ApplicationEventPublisher,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    @Value("\${builder.production.reconciliation.stale-after-seconds:300}") private val publishingStaleAfterSeconds: Long,
    @Value("\${builder.production.reconciliation.batch-size:20}") private val publishingReconciliationBatchSize: Int,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun reconcileStalePublishing(): Int {
        val staleBefore = clock.instant().minus(Duration.ofSeconds(publishingStaleAfterSeconds.coerceAtLeast(1)))
        val batchSize = publishingReconciliationBatchSize.coerceIn(1, 100)
        return runs.findStaleIds(
            staleBefore,
            batchSize,
        ).count { runId -> transactions.execute { reconcileStalePublishing(runId, staleBefore) } == true }
    }

    private fun reconcileStalePublishing(runId: UUID, staleBefore: Instant): Boolean {
        val run = runs.findForUpdate(runId) ?: return false
        if (run.runMode != BuilderRunMode.PRODUCTION || run.status != BuilderRunStatus.PUBLISHING || !run.updatedAt.isBefore(staleBefore)) return false
        val requestId = run.externalWriteRequestId ?: return false
        val writeStep = steps.findAllByRunIdOrderBySequenceNo(run.id).lastOrNull {
            it.nodeType == NodeType.NOTION_CREATE_PAGE.wireName && it.status == BuilderStepStatus.RUNNING
        } ?: return false
        if (!notion.reconcileStalePublishing(run.workspaceId, requestId, staleBefore)) return false
        writeStep.status = BuilderStepStatus.FAILED
        writeStep.errorMessage = STALE_NOTION_FAILURE_MESSAGE
        run.status = BuilderRunStatus.AMBIGUOUS
        run.failureCode = STALE_NOTION_FAILURE_CODE
        run.failureMessage = STALE_NOTION_FAILURE_MESSAGE
        run.currentNodeId = null
        return true
    }

    @Transactional
    fun start(ownerId: UUID, workflowId: UUID, request: ProductionRunRequest, idempotencyKey: String): RunView {
        requireIdempotency(idempotencyKey)
        if (request.input.isEmpty() || mapper.writeValueAsBytes(request.input).size > 100_000) throw BadRequestException("PRODUCTION_INPUT_INVALID", "실제 실행 입력은 비어 있지 않아야 하며 100KB 이하여야 합니다.")
        if (!request.notionParentPageId.trim().matches(Regex("[A-Za-z0-9-]{20,120}"))) throw BadRequestException("NOTION_PARENT_REQUIRED", "유효한 Notion 상위 페이지 ID가 필요합니다.")
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        runs.findByWorkspaceIdAndIdempotencyKey(workspace.id, idempotencyKey)?.let {
            if (it.runMode != BuilderRunMode.PRODUCTION || it.workflowId != workflowId) {
                throw ConflictException("PRODUCTION_START_KEY_CONFLICT", "다른 실행에서 이미 사용한 시작 키입니다.")
            }
            return view(it)
        }
        val workflow = workflows.findByIdAndWorkspaceId(workflowId, workspace.id) ?: throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
        if (workflow.status != WorkflowStatus.ACTIVE) throw ConflictException("PRODUCTION_WORKFLOW_NOT_ACTIVE", "회사에 배치된 ACTIVE Workflow만 실제 실행할 수 있습니다.")
        val version = workflow.currentVersionId?.let { versions.findByIdAndWorkflowId(it, workflow.id) }
            ?: throw ConflictException("PRODUCTION_VERSION_REQUIRED", "실행할 Workflow Version이 없습니다.")
        if (version.templateVersionId == null) throw ConflictException("PRODUCTION_TEMPLATE_VERSION_REQUIRED", "승인된 Template Version이 고정된 Workflow만 실제 실행할 수 있습니다.")
        productionNodes(version)
        notion.requireWritableConnection(ownerId, request.notionConnectionId)
        val run = runs.save(BuilderRun(
            workspaceId = workspace.id, workflowId = workflow.id, workflowVersionId = version.id, templateVersionId = version.templateVersionId,
            runMode = BuilderRunMode.PRODUCTION, status = BuilderRunStatus.QUEUED, inputJson = mask(request.input), idempotencyKey = idempotencyKey,
            destinationJson = mapOf("provider" to "NOTION", "connectionId" to request.notionConnectionId.toString(), "parentPageId" to request.notionParentPageId.trim()),
        ))
        publisher.publishEvent(ProductionRunRequested(ownerId, run.id))
        return view(run)
    }

    @Transactional
    fun claim(ownerId: UUID, runId: UUID): ProductionGenerationContext? {
        val workspace = workspaces.findByOwnerId(ownerId) ?: return null
        val current = runs.findByIdAndWorkspaceId(runId, workspace.id) ?: return null
        if (current.runMode != BuilderRunMode.PRODUCTION || runs.claim(runId, BuilderRunStatus.QUEUED, BuilderRunStatus.GENERATING) != 1) return null
        val run = runs.findByIdAndWorkspaceId(runId, workspace.id) ?: return null
        val workflow = workflows.findByIdAndWorkspaceId(run.workflowId, workspace.id) ?: return null
        val version = versions.findByIdAndWorkflowId(run.workflowVersionId, workflow.id) ?: return null
        val nodeIds = productionNodes(version)
        val graph = mapper.convertValue(version.graphJson, WorkflowGraph::class.java)
        run.currentNodeId = nodeIds.generation
        steps.save(BuilderStepRun(runId = run.id, nodeId = nodeIds.generation, nodeType = NodeType.AI_GENERATE.wireName, sequenceNo = nextSequence(run.id), status = BuilderStepStatus.RUNNING, inputJson = run.inputJson))
        return ProductionGenerationContext(ownerId, run.id, graph, version.executionContractJson, run.inputJson, nodeIds.generation, nodeIds.approval, nodeIds.write)
    }

    @Transactional
    fun generated(context: ProductionGenerationContext, output: ProductionContentOutput) {
        val workspace = workspaces.findByOwnerId(context.ownerId) ?: return
        val run = runs.findByIdAndWorkspaceId(context.runId, workspace.id) ?: return
        if (run.status != BuilderRunStatus.GENERATING) return
        validateProductionOutput(output)
        val generationStep = steps.findAllByRunIdOrderBySequenceNo(run.id).last { it.nodeId == context.generationNodeId && it.status == BuilderStepStatus.RUNNING }
        val preview = mapOf("title" to output.title, "paragraphs" to output.paragraphs, "evidence" to output.evidence, "qualityChecks" to output.qualityChecks)
        generationStep.status = BuilderStepStatus.SUCCEEDED
        generationStep.outputJson = preview
        val destination = requireNotNull(run.destinationJson)
        val connectionId = UUID.fromString(destination.getValue("connectionId").toString())
        val pageRequestId = notion.previewPage(context.ownerId, connectionId, "production:${run.id}:preview:${run.attemptCount}", destination.getValue("parentPageId").toString(), output.title, output.paragraphs)
        run.externalWriteRequestId = pageRequestId
        run.outputJson = preview
        run.currentNodeId = context.approvalNodeId
        run.status = BuilderRunStatus.WAITING_APPROVAL
        steps.save(BuilderStepRun(runId = run.id, nodeId = context.approvalNodeId, nodeType = NodeType.HUMAN_APPROVAL.wireName, sequenceNo = nextSequence(run.id), status = BuilderStepStatus.WAITING_APPROVAL, inputJson = preview))
        approvals.save(BuilderApproval(workspaceId = workspace.id, workflowId = run.workflowId, runId = run.id, approvalType = ApprovalType.EXECUTION, idempotencyKey = "production:${run.id}:approval:${run.attemptCount}"))
    }

    @Transactional
    fun failed(ownerId: UUID, runId: UUID, code: String, message: String) {
        val workspace = workspaces.findByOwnerId(ownerId) ?: return
        val run = runs.findByIdAndWorkspaceId(runId, workspace.id) ?: return
        if (run.status !in setOf(BuilderRunStatus.GENERATING, BuilderRunStatus.PUBLISHING)) return
        steps.findAllByRunIdOrderBySequenceNo(run.id).lastOrNull { it.status == BuilderStepStatus.RUNNING }?.apply { status = BuilderStepStatus.FAILED; errorMessage = message.take(1000) }
        run.status = BuilderRunStatus.FAILED; run.failureCode = code; run.failureMessage = message.take(500); run.currentNodeId = null
    }

    fun decide(ownerId: UUID, runId: UUID, approve: Boolean, idempotencyKey: String): RunView {
        requireIdempotency(idempotencyKey)
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val claim = transactions.execute { claimDecision(ownerId, workspace.id, runId, approve, idempotencyKey) }
            ?: throw IllegalStateException("실행 승인 의도를 저장하지 못했습니다.")
        claim.immediate?.let { return it }
        val result = notion.approvePage(ownerId, requireNotNull(claim.writeRequestId), requireNotNull(claim.publishKey))
        return transactions.execute { completeDecision(workspace.id, runId, result) }
            ?: throw IllegalStateException("실행 발행 결과를 저장하지 못했습니다.")
    }

    private data class ProductionDecisionClaim(
        val immediate: RunView? = null,
        val writeRequestId: UUID? = null,
        val publishKey: String? = null,
    )

    private fun claimDecision(ownerId: UUID, workspaceId: UUID, runId: UUID, approve: Boolean, idempotencyKey: String): ProductionDecisionClaim {
        val existing = runs.findByIdAndWorkspaceId(runId, workspaceId)
            ?: throw NotFoundException("RUN_NOT_FOUND", "실행을 찾을 수 없습니다.")
        if (existing.runMode != BuilderRunMode.PRODUCTION) throw ConflictException("PRODUCTION_RUN_REQUIRED", "실제 실행이 아닙니다.")
        approvals.findByWorkspaceIdAndIdempotencyKey(workspaceId, idempotencyKey)?.let { prior ->
            if (prior.runId != runId) throw ConflictException("PRODUCTION_APPROVAL_KEY_CONFLICT", "다른 실행에서 이미 사용한 승인 키입니다.")
        }
        if (existing.status != BuilderRunStatus.WAITING_APPROVAL) {
            if (existing.status in setOf(BuilderRunStatus.PUBLISHING, BuilderRunStatus.SUCCEEDED, BuilderRunStatus.FAILED, BuilderRunStatus.AMBIGUOUS)) {
                return ProductionDecisionClaim(immediate = view(existing))
            }
            throw ConflictException("RUN_NOT_WAITING_APPROVAL", "승인 대기 실행이 아닙니다.")
        }
        val next = if (approve) BuilderRunStatus.PUBLISHING else BuilderRunStatus.FAILED
        if (runs.transition(runId, BuilderRunStatus.WAITING_APPROVAL, next) != 1) {
            val resolved = runs.findByIdAndWorkspaceId(runId, workspaceId)!!
            return ProductionDecisionClaim(immediate = view(resolved))
        }
        val run = runs.findByIdAndWorkspaceId(runId, workspaceId)!!
        val version = versions.findByIdAndWorkflowId(run.workflowVersionId, run.workflowId)
            ?: throw ConflictException("PRODUCTION_VERSION_REQUIRED", "실행한 Workflow Version을 찾을 수 없습니다.")
        val nodeIds = productionNodes(version)
        val approval = approvals.findByRunIdAndStatus(runId, ApprovalStatus.PENDING)
            ?: throw NotFoundException("APPROVAL_NOT_FOUND", "승인 요청을 찾을 수 없습니다.")
        approval.idempotencyKey = idempotencyKey
        approval.status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
        approval.decidedBy = ownerId
        approval.decidedAt = java.time.Instant.now()
        val humanStep = steps.findAllByRunIdOrderBySequenceNo(run.id).last { it.status == BuilderStepStatus.WAITING_APPROVAL }
        if (!approve) {
            humanStep.status = BuilderStepStatus.FAILED
            humanStep.errorMessage = "사용자가 실제 발행을 거절했습니다."
            run.failureCode = "PRODUCTION_REJECTED"
            run.failureMessage = "사용자가 실제 발행을 거절했습니다."
            run.currentNodeId = null
            return ProductionDecisionClaim(immediate = view(run))
        }
        humanStep.status = BuilderStepStatus.SUCCEEDED
        humanStep.outputJson = humanStep.inputJson + ("approved" to true)
        val writeId = run.externalWriteRequestId
            ?: throw ConflictException("NOTION_PREVIEW_REQUIRED", "승인할 Notion 미리보기가 없습니다.")
        run.currentNodeId = nodeIds.write
        steps.save(BuilderStepRun(
            runId = run.id, nodeId = nodeIds.write, nodeType = NodeType.NOTION_CREATE_PAGE.wireName,
            sequenceNo = nextSequence(run.id), status = BuilderStepStatus.RUNNING,
            inputJson = mapOf("writeRequestId" to writeId.toString()),
        ))
        runs.flush()
        return ProductionDecisionClaim(writeRequestId = writeId, publishKey = "production:${run.id}:publish:${run.attemptCount}")
    }

    private fun completeDecision(workspaceId: UUID, runId: UUID, result: ExternalWriteResult): RunView {
        val run = runs.findByIdAndWorkspaceId(runId, workspaceId)
            ?: throw NotFoundException("RUN_NOT_FOUND", "실행을 찾을 수 없습니다.")
        if (run.status != BuilderRunStatus.PUBLISHING) return view(run)
        val writeStep = steps.findAllByRunIdOrderBySequenceNo(run.id).last {
            it.nodeType == NodeType.NOTION_CREATE_PAGE.wireName && it.status == BuilderStepStatus.RUNNING
        }
        if (result.succeeded) {
            writeStep.status = BuilderStepStatus.SUCCEEDED
            writeStep.outputJson = mapOf("pageId" to result.externalId, "url" to result.externalUrl)
            run.status = BuilderRunStatus.SUCCEEDED
            run.requirementMatched = true
            run.outputJson = run.outputJson.orEmpty() + mapOf("notionPageId" to result.externalId, "notionUrl" to result.externalUrl)
        } else {
            writeStep.status = BuilderStepStatus.FAILED
            writeStep.errorMessage = result.failureMessage
            run.status = if (result.ambiguous) BuilderRunStatus.AMBIGUOUS else BuilderRunStatus.FAILED
            run.failureCode = result.failureCode
            run.failureMessage = result.failureMessage
        }
        run.currentNodeId = null
        return view(run)
    }

    @Transactional
    fun retry(ownerId: UUID, runId: UUID, idempotencyKey: String): RunView {
        requireIdempotency(idempotencyKey)
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val run = runs.findByIdAndWorkspaceId(runId, workspace.id) ?: throw NotFoundException("RUN_NOT_FOUND", "실행을 찾을 수 없습니다.")
        if (run.runMode != BuilderRunMode.PRODUCTION || run.status != BuilderRunStatus.FAILED) throw ConflictException("PRODUCTION_RETRY_NOT_ALLOWED", "실패한 실제 실행만 재시도할 수 있습니다.")
        if (run.failureCode == "NOTION_CONNECTION_EXPIRED") {
            val connectionId = run.destinationJson?.get("connectionId")?.toString()
                ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
                ?: throw ConflictException("NOTION_CONNECTION_EXPIRED", "Notion 연결이 만료되었습니다. 업무 연결에서 다시 연결한 뒤 재시도해 주세요.")
            notion.requireWritableConnection(ownerId, connectionId)
        }
        if (run.attemptCount >= 3) throw ConflictException("PRODUCTION_RETRY_LIMIT", "자동 재시도 한도 3회를 초과했습니다.")
        run.status = BuilderRunStatus.QUEUED; run.failureCode = null; run.failureMessage = null; run.externalWriteRequestId = null
        publisher.publishEvent(ProductionRunRequested(ownerId, run.id))
        return view(run)
    }

    @Transactional(readOnly = true)
    fun get(ownerId: UUID, runId: UUID): RunView {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        return view(runs.findByIdAndWorkspaceId(runId, workspace.id)?.takeIf { it.runMode == BuilderRunMode.PRODUCTION } ?: throw NotFoundException("RUN_NOT_FOUND", "실행을 찾을 수 없습니다."))
    }

    @Transactional(readOnly = true)
    fun history(ownerId: UUID, workflowId: UUID): List<ProductionRunHistoryEntry> {
        val workspace = workspaces.findByOwnerId(ownerId)
            ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        workflows.findByIdAndWorkspaceId(workflowId, workspace.id)
            ?: throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
        return runs.findTop20ByWorkspaceIdAndWorkflowIdAndRunModeOrderByCreatedAtDesc(
            workspace.id,
            workflowId,
            BuilderRunMode.PRODUCTION,
        ).map { run ->
            val connectionId = run.destinationJson?.get("connectionId")?.toString()
                ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
                ?: throw IllegalStateException("Production run ${run.id} has no persisted destination connection")
            ProductionRunHistoryEntry(view(run), connectionId)
        }
    }

    private fun view(run: BuilderRun): RunView = RunView(
        run.id, run.status, run.runMode, run.currentNodeId, run.templateVersionId, run.outputJson, run.requirementMatched,
        steps.findAllByRunIdOrderBySequenceNo(run.id).map { StepRunView(it.nodeId, it.nodeType, it.sequenceNo, it.status, it.inputJson, it.outputJson, it.errorMessage) },
        approvals.findByRunIdAndStatus(run.id, ApprovalStatus.PENDING)?.id, run.attemptCount, run.failureCode, run.failureMessage,
    )
    private fun nextSequence(runId: UUID) = (steps.findAllByRunIdOrderBySequenceNo(runId).maxOfOrNull { it.sequenceNo } ?: 0) + 1
    private data class ProductionNodeIds(val generation: String, val approval: String, val write: String)
    private fun productionNodes(version: BuilderWorkflowVersion): ProductionNodeIds {
        val graph = mapper.convertValue(version.graphJson, WorkflowGraph::class.java)
        val write = graph.nodes.singleOrNull { it.nodeType == NodeType.NOTION_CREATE_PAGE.wireName }
            ?: throw ConflictException("PRODUCTION_NOTION_NODE_REQUIRED", "실제 Notion 실행에는 승인된 Graph의 notion.create_page 노드가 하나 필요합니다.")
        val approval = graph.nodes.filter { it.nodeType == NodeType.HUMAN_APPROVAL.wireName }
            .firstOrNull { candidate -> graph.edges.any { it.source == candidate.id && it.target == write.id } }
            ?: throw ConflictException("PRODUCTION_APPROVAL_NODE_REQUIRED", "notion.create_page 바로 앞에 승인 노드가 필요합니다.")
        val generation = graph.nodes.filter { it.nodeType == NodeType.AI_GENERATE.wireName }
            .lastOrNull { candidate -> pathExists(graph, candidate.id, approval.id) }
            ?: throw ConflictException("PRODUCTION_GENERATION_NODE_REQUIRED", "승인 전 구조화 결과 생성 노드가 필요합니다.")
        return ProductionNodeIds(generation.id, approval.id, write.id)
    }
    private fun pathExists(graph: WorkflowGraph, source: String, target: String): Boolean {
        val queue = ArrayDeque<String>(); val visited = mutableSetOf<String>(); queue.add(source)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current == target) return true
            graph.edges.filter { it.source == current }.forEach { queue.add(it.target) }
        }
        return false
    }
    private fun requireIdempotency(key: String) { if (key.isBlank() || key.length > 120) throw BadRequestException("IDEMPOTENCY_KEY_REQUIRED", "유효한 Idempotency-Key가 필요합니다.") }
    @Suppress("UNCHECKED_CAST")
    private fun mask(value: Map<String, Any?>): Map<String, Any?> = value.mapValues { (key, item) -> if (Regex("(?i).*(token|secret|password|api.?key).*").matches(key)) "***" else maskItem(item) }
    private fun maskItem(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to if (Regex("(?i).*(token|secret|password|api.?key).*").matches(key.toString())) "***" else maskItem(item) }
        is List<*> -> value.map(::maskItem)
        is String -> value.take(20_000)
        else -> value
    }
}

@Component
class BuilderPublishingReconciler(
    private val production: BuilderProductionExecutionService,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun reconcileAtStartup() {
        production.reconcileStalePublishing()
    }

    @Scheduled(fixedDelayString = "\${builder.production.reconciliation.interval-ms:30000}")
    fun reconcileOnSchedule() {
        production.reconcileStalePublishing()
    }
}

@Component
class BuilderProductionExecutionWorker(
    private val service: BuilderProductionExecutionService,
    private val generator: ProductionContentGenerator,
) {
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun execute(event: ProductionRunRequested) {
        try {
            val context = service.claim(event.ownerId, event.runId) ?: return
            service.generated(context, generator.generate(context))
        } catch (error: Exception) {
            val apiError = error as? com.agentvillage.common.exception.ApiException
            val modelError = error as? MetaAgentExecutionException
            service.failed(
                event.ownerId,
                event.runId,
                apiError?.code ?: modelError?.errorCode ?: "PRODUCTION_GENERATION_FAILED",
                apiError?.message ?: modelError?.safeMessage ?: "실제 업무 결과 생성에 실패했습니다. 입력과 생성 권한을 확인한 뒤 재시도하세요.",
            )
        }
    }
}
