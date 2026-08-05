package com.agentvillage.harness.application

import com.agentvillage.agent.application.AgentDescriptor
import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.common.domain.Visibility
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
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
    @Transactional fun create(ownerId: UUID, name: String, description: String?) =
        harnesses.save(Harness(ownerId = ownerId, name = name.trim(), description = description?.trim()))
    @Transactional(readOnly = true) fun list(ownerId: UUID) = harnesses.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)
    @Transactional(readOnly = true) override fun requireOwnedView(id: UUID, ownerId: UUID): HarnessView {
        val harness = harnesses.findByIdAndOwnerId(id, ownerId) ?: throw NotFoundException("HARNESS_NOT_FOUND", "하네스를 찾을 수 없습니다.")
        return HarnessView(harness, steps.findAllByHarnessIdOrderBySequenceNo(id), edges.findAllByHarnessId(id), versions.findFirstByHarnessIdOrderByCreatedAtDesc(id))
    }
    @Transactional fun update(id: UUID, ownerId: UUID, name: String, description: String?, visibility: Visibility): Harness {
        val h = requireOwnedView(id, ownerId).harness
        h.name = name.trim(); h.description = description?.trim(); h.visibility = visibility
        return h
    }
    @Transactional fun delete(id: UUID, ownerId: UUID) = harnesses.delete(requireOwnedView(id, ownerId).harness)

    @Transactional
    fun connect(id: UUID, ownerId: UUID, agentIds: List<UUID>, approvalAfterLast: Boolean): HarnessView {
        if (agentIds.isEmpty() || agentIds.size > 5) throw BadRequestException("HARNESS_AGENT_LIMIT", "에이전트는 1개 이상 5개 이하만 연결할 수 있습니다.")
        if (agentIds.distinct().size != agentIds.size) throw BadRequestException("HARNESS_DUPLICATE_AGENT", "같은 에이전트를 중복 연결할 수 없습니다.")
        requireOwnedView(id, ownerId)
        agentIds.forEach { agents.requireOwned(it, ownerId) }
        edges.deleteAllByHarnessId(id); steps.deleteAllByHarnessId(id); steps.flush()
        val saved = steps.saveAll(agentIds.mapIndexed { i, agentId -> HarnessStep(
            harnessId = id, agentId = agentId, stepKey = "step-${i + 1}", stepType = HarnessStepType.LLM,
            sequenceNo = i + 1, maxRetries = 2, requiresApproval = approvalAfterLast && i == agentIds.lastIndex,
            inputMapping = if (i == 0) mapOf("input" to "$.input") else mapOf("input" to "$.previous.output"),
        ) })
        edges.saveAll(saved.zipWithNext().map { (a, b) -> HarnessEdge(harnessId = id, sourceStepId = a.id, targetStepId = b.id) })
        return requireOwnedView(id, ownerId)
    }

    @Transactional(readOnly = true)
    fun validate(id: UUID, ownerId: UUID): ValidationResult {
        val view = requireOwnedView(id, ownerId); val errors = mutableListOf<String>()
        if (view.steps.isEmpty()) errors += "시작 단계가 없습니다."
        if (view.steps.size > 5) errors += "최대 에이전트 수를 초과했습니다."
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
        val version = latestPublished(id)
        val cloned = harnesses.save(Harness(ownerId = ownerId, name = "${version.snapshotJson["name"]} 복제본", description = "스냅샷에서 복제됨"))
        @Suppress("UNCHECKED_CAST") val agentMaps = version.snapshotJson["agents"] as? List<Map<String, Any>> ?: emptyList()
        val clonedAgentIds = agentMaps.map { agents.cloneFrom(descriptor(it), ownerId) }
        connect(cloned.id, ownerId, clonedAgentIds, false)
        return cloned
    }

    private fun snapshot(view: HarnessView, descriptors: Map<UUID, AgentDescriptor?>): Map<String, Any> = mapOf(
        "name" to view.harness.name, "description" to (view.harness.description ?: ""),
        "agents" to descriptors.values.filterNotNull().map { mapOf(
            "name" to it.name, "role" to it.role, "script" to it.script, "guide" to (it.guide ?: ""),
            "provider" to it.provider.name, "recommendedModel" to it.model, "temperature" to it.temperature,
            "maxOutputTokens" to it.maxOutputTokens, "timeoutSeconds" to it.timeoutSeconds,
            "providerOptions" to it.providerOptions,
        ) },
        "steps" to view.steps.map { mapOf("id" to it.stepKey, "type" to it.stepType.name, "sequence" to it.sequenceNo, "maxRetries" to it.maxRetries) },
        "edges" to view.edges.map { mapOf("from" to it.sourceStepId.toString(), "to" to (it.targetStepId?.toString() ?: "finish")) },
    )

    private fun descriptor(m: Map<String, Any>) = AgentDescriptor(
        UUID.randomUUID(), m["name"].toString(), m["role"].toString(), m["script"].toString(), m["guide"]?.toString(),
        com.agentvillage.llmcredential.domain.LlmProvider.valueOf(m["provider"].toString()), m["recommendedModel"].toString(),
        java.math.BigDecimal(m["temperature"].toString()), (m["maxOutputTokens"] as Number).toInt(),
        (m["timeoutSeconds"] as Number).toInt(), (m["providerOptions"] as? Map<String, Any>) ?: emptyMap(), null, null,
    )
}
