package com.agentvillage.harness.application

import com.agentvillage.agent.application.AgentDescriptor
import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.common.domain.Visibility
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.common.exception.ForbiddenException
import com.agentvillage.harness.domain.*
import com.agentvillage.harness.infrastructure.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class HarnessView(val harness: Harness, val steps: List<HarnessStep>, val edges: List<HarnessEdge>, val latestVersion: HarnessVersion?)
data class ValidationResult(val valid: Boolean, val errors: List<String>)

interface HarnessDirectory {
    fun requireOwnedView(id: UUID, ownerId: UUID): HarnessView
    fun latestPublished(id: UUID): HarnessVersion
}

@Service
class HarnessService(
    private val harnesses: HarnessRepository,
    private val steps: HarnessStepRepository,
    private val edges: HarnessEdgeRepository,
    private val versions: HarnessVersionRepository,
    private val agents: AgentDirectory,
) : HarnessDirectory {
    @Transactional fun create(ownerId: UUID, name: String, description: String?, resultFormat: HarnessResultFormat = HarnessResultFormat.AUTO) =
        harnesses.save(Harness(ownerId = ownerId, name = name.trim(), description = description?.trim(), resultFormat = resultFormat))
    @Transactional(readOnly = true) fun list(ownerId: UUID) = harnesses.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)
    @Transactional(readOnly = true) fun listPublic(ownerId: UUID) = harnesses.findAllByOwnerIdAndVisibilityInOrderByCreatedAtDesc(ownerId, listOf(Visibility.PUBLIC, Visibility.MARKET))
    @Transactional(readOnly = true) override fun requireOwnedView(id: UUID, ownerId: UUID): HarnessView {
        val harness = harnesses.findByIdAndOwnerId(id, ownerId) ?: throw NotFoundException("HARNESS_NOT_FOUND", "하네스를 찾을 수 없습니다.")
        return HarnessView(harness, steps.findAllByHarnessIdOrderBySequenceNo(id), edges.findAllByHarnessId(id), versions.findFirstByHarnessIdOrderByCreatedAtDesc(id))
    }
    @Transactional fun update(id: UUID, ownerId: UUID, name: String, description: String?, visibility: Visibility, resultFormat: HarnessResultFormat): Harness {
        val h = requireOwnedView(id, ownerId).harness
        h.name = name.trim(); h.description = description?.trim(); h.visibility = visibility; h.resultFormat = resultFormat
        return h
    }
    @Transactional fun delete(id: UUID, ownerId: UUID) = harnesses.delete(requireOwnedView(id, ownerId).harness)

    @Transactional
    fun connect(
        id: UUID,
        ownerId: UUID,
        agentIds: List<UUID>,
        approvalAfterLast: Boolean,
        approvalBeforeLast: Boolean = false,
    ): HarnessView {
        if (agentIds.isEmpty() || agentIds.size > 5) throw BadRequestException("HARNESS_AGENT_LIMIT", "에이전트는 1개 이상 5개 이하만 연결할 수 있습니다.")
        if (agentIds.distinct().size != agentIds.size) throw BadRequestException("HARNESS_DUPLICATE_AGENT", "같은 에이전트를 중복 연결할 수 없습니다.")
        if (approvalBeforeLast && agentIds.size < 2) throw BadRequestException("HARNESS_APPROVAL_POSITION", "중간 승인은 에이전트가 2명 이상일 때 사용할 수 있습니다.")
        if (approvalAfterLast && approvalBeforeLast) throw BadRequestException("HARNESS_APPROVAL_POSITION", "승인 위치는 하나만 선택할 수 있습니다.")
        requireOwnedView(id, ownerId)
        agentIds.forEach { agents.requireOwned(it, ownerId) }
        edges.deleteAllByHarnessId(id); steps.deleteAllByHarnessId(id); steps.flush()
        val commands = buildList {
            agentIds.forEachIndexed { i, agentId ->
                if (approvalBeforeLast && i == agentIds.lastIndex) add(null)
                add(agentId)
            }
        }
        val saved = steps.saveAll(commands.mapIndexed { sequence, agentId ->
            val isApproval = agentId == null
            HarnessStep(
                harnessId = id,
                agentId = agentId,
                stepKey = if (isApproval) "approval-${sequence + 1}" else "step-${sequence + 1}",
                stepType = if (isApproval) HarnessStepType.APPROVAL else HarnessStepType.LLM,
                sequenceNo = sequence + 1,
                maxRetries = if (isApproval) 0 else 2,
                requiresApproval = approvalAfterLast && sequence == commands.lastIndex,
                inputMapping = if (sequence == 0) mapOf("input" to "$.input") else mapOf("input" to "$.previous.output"),
            )
        })
        edges.saveAll(saved.zipWithNext().map { (a, b) -> HarnessEdge(harnessId = id, sourceStepId = a.id, targetStepId = b.id) })
        val harness = requireOwnedView(id, ownerId).harness
        if (harness.resultStepKey == null || saved.none { it.stepKey == harness.resultStepKey }) {
            harness.resultStepKey = saved.lastOrNull { it.stepType != HarnessStepType.APPROVAL }?.stepKey
        }
        return requireOwnedView(id, ownerId)
    }

    @Transactional
    fun configureResult(id: UUID, ownerId: UUID, format: HarnessResultFormat, resultAgentId: UUID? = null): Harness {
        val view = requireOwnedView(id, ownerId)
        val resultStep = resultAgentId?.let { agentId -> view.steps.firstOrNull { it.agentId == agentId } }
            ?: view.steps.lastOrNull { it.stepType != HarnessStepType.APPROVAL }
            ?: throw BadRequestException("HARNESS_RESULT_STEP_NOT_FOUND", "결과를 만들 실행 단계를 선택해 주세요.")
        view.harness.resultFormat = format
        view.harness.resultStepKey = resultStep.stepKey
        return view.harness
    }

    @Transactional(readOnly = true)
    fun validate(id: UUID, ownerId: UUID): ValidationResult {
        val view = requireOwnedView(id, ownerId); val errors = mutableListOf<String>()
        if (view.steps.isEmpty()) errors += "시작 단계가 없습니다."
        if (view.steps.count { it.stepType == HarnessStepType.LLM } > 5) errors += "최대 에이전트 수를 초과했습니다."
        if (view.steps.map { it.sequenceNo } != (1..view.steps.size).toList()) errors += "단계 순서가 연속적이지 않습니다."
        if (view.steps.any { it.maxRetries !in 0..3 }) errors += "재시도 횟수가 제한을 벗어났습니다."
        val ids = view.steps.map { it.id }.toSet()
        if (view.edges.any { it.sourceStepId !in ids || it.targetStepId !in ids }) errors += "존재하지 않는 단계가 연결되어 있습니다."
        view.steps.mapNotNull { it.agentId }.forEach { runCatching { agents.requireOwned(it, ownerId) }.onFailure { errors += "존재하지 않는 에이전트가 연결되어 있습니다: $it" } }
        return ValidationResult(errors.isEmpty(), errors.distinct())
    }

    @Transactional
    fun publish(id: UUID, ownerId: UUID): HarnessVersion {
        val result = validate(id, ownerId)
        if (!result.valid) throw BadRequestException("HARNESS_INVALID", result.errors.joinToString(" "))
        val view = requireOwnedView(id, ownerId)
        val descriptors = view.steps.associate { it.id to it.agentId?.let { aid -> agents.describeOwned(aid, ownerId) } }
        val snapshot = snapshot(view, descriptors)
        val next = (versions.findFirstByHarnessIdOrderByCreatedAtDesc(id)?.version?.substringAfterLast('.')?.toIntOrNull() ?: -1) + 1
        val version = versions.save(HarnessVersion(harnessId = id, version = "1.0.$next", snapshotJson = snapshot))
        view.harness.status = HarnessStatus.PUBLISHED
        return version
    }

    @Transactional(readOnly = true)
    override fun latestPublished(id: UUID): HarnessVersion = versions.findFirstByHarnessIdOrderByCreatedAtDesc(id)
        ?: throw NotFoundException("HARNESS_VERSION_NOT_FOUND", "발행된 하네스 버전이 없습니다.")

    @Transactional(readOnly = true)
    fun latestPublishedId(versionId: UUID): UUID = versions.findById(versionId).orElseThrow {
        NotFoundException("HARNESS_VERSION_NOT_FOUND", "발행된 하네스 버전이 없습니다.")
    }.harnessId

    @Transactional
    fun clone(id: UUID, ownerId: UUID): Harness {
        val source = harnesses.findById(id).orElseThrow { NotFoundException("HARNESS_NOT_FOUND", "하네스를 찾을 수 없습니다.") }
        if (source.ownerId != ownerId && source.visibility !in setOf(Visibility.PUBLIC, Visibility.MARKET)) {
            throw ForbiddenException("HARNESS_PRIVATE", "복제할 수 있도록 공개되지 않은 하네스입니다.")
        }
        val version = latestPublished(id)
        val cloned = harnesses.save(Harness(ownerId = ownerId, name = "${version.snapshotJson["name"]} 복제본", description = "스냅샷에서 복제됨"))
        @Suppress("UNCHECKED_CAST") val agentMaps = version.snapshotJson["agents"] as? List<Map<String, Any>> ?: emptyList()
        val clonedAgentIds = agentMaps.map { agents.cloneFrom(descriptor(it), ownerId) }
        @Suppress("UNCHECKED_CAST") val stepMaps = version.snapshotJson["steps"] as? List<Map<String, Any>> ?: emptyList()
        val approvalBeforeLast = stepMaps.any { it["type"]?.toString() == HarnessStepType.APPROVAL.name }
        val approvalAfterLast = stepMaps.any { it["requiresApproval"] == true }
        connect(cloned.id, ownerId, clonedAgentIds, approvalAfterLast, approvalBeforeLast)
        @Suppress("UNCHECKED_CAST") val result = version.snapshotJson["result"] as? Map<String, Any>
        val resultFormat = result?.get("format")?.toString()?.let { runCatching { HarnessResultFormat.valueOf(it) }.getOrNull() }
            ?: HarnessResultFormat.AUTO
        cloned.resultFormat = resultFormat
        cloned.resultStepKey = result?.get("stepKey")?.toString()?.takeIf { key -> requireOwnedView(cloned.id, ownerId).steps.any { it.stepKey == key } }
            ?: requireOwnedView(cloned.id, ownerId).steps.lastOrNull { it.stepType != HarnessStepType.APPROVAL }?.stepKey
        return cloned
    }

    @Transactional
    fun publishToMarket(id: UUID, ownerId: UUID): HarnessVersion {
        val view = requireOwnedView(id, ownerId)
        val version = view.latestVersion ?: throw NotFoundException("HARNESS_VERSION_NOT_FOUND", "발행된 하네스 버전이 없습니다.")
        view.harness.visibility = Visibility.MARKET
        return version
    }

    @Transactional(readOnly = true)
    fun downloadableVersion(id: UUID, requesterId: UUID): HarnessVersion {
        val harness = harnesses.findById(id).orElseThrow { NotFoundException("HARNESS_NOT_FOUND", "하네스를 찾을 수 없습니다.") }
        if (harness.ownerId != requesterId && harness.visibility !in setOf(Visibility.PUBLIC, Visibility.MARKET)) {
            throw ForbiddenException("HARNESS_PRIVATE", "내려받을 수 있도록 공개되지 않은 하네스입니다.")
        }
        return latestPublished(id)
    }

    private fun snapshot(view: HarnessView, descriptors: Map<UUID, AgentDescriptor?>): Map<String, Any> = mapOf(
        "name" to view.harness.name, "description" to (view.harness.description ?: ""),
        "result" to mapOf("format" to view.harness.resultFormat.name, "stepKey" to (view.harness.resultStepKey ?: "")),
        "agents" to descriptors.values.filterNotNull().map { mapOf(
            "name" to it.name, "role" to it.role, "script" to it.script, "guide" to (it.guide ?: ""),
            "provider" to it.provider.name, "recommendedModel" to it.model, "temperature" to it.temperature,
            "maxOutputTokens" to it.maxOutputTokens, "timeoutSeconds" to it.timeoutSeconds,
            "providerOptions" to it.providerOptions,
        ) },
        "steps" to view.steps.map { mapOf(
            "id" to it.stepKey,
            "type" to it.stepType.name,
            "sequence" to it.sequenceNo,
            "maxRetries" to it.maxRetries,
            "requiresApproval" to it.requiresApproval,
        ) },
        "edges" to view.edges.map { mapOf("from" to it.sourceStepId.toString(), "to" to (it.targetStepId?.toString() ?: "finish")) },
    )

    private fun descriptor(m: Map<String, Any>) = AgentDescriptor(
        UUID.randomUUID(), m["name"].toString(), m["role"].toString(), m["script"].toString(), m["guide"]?.toString(),
        com.agentvillage.llmcredential.domain.LlmProvider.valueOf(m["provider"].toString()), m["recommendedModel"].toString(),
        java.math.BigDecimal(m["temperature"].toString()), (m["maxOutputTokens"] as Number).toInt(),
        (m["timeoutSeconds"] as Number).toInt(), (m["providerOptions"] as? Map<String, Any>) ?: emptyMap(), null, null,
    )
}
