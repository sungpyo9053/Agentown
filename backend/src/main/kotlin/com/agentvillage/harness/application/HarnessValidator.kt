package com.agentvillage.harness.application

import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.harness.domain.HarnessStepType
import org.springframework.stereotype.Component
import java.util.UUID

enum class ValidationCheckStatus { PASSED, FAILED }
enum class HarnessValidationOutcome { VALIDATED, VALIDATION_FAILED }

data class ValidationCheck(
    val code: String,
    val status: ValidationCheckStatus,
    val message: String,
)

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String>,
    val checks: List<ValidationCheck> = emptyList(),
    val outcome: HarnessValidationOutcome = if (valid) HarnessValidationOutcome.VALIDATED else HarnessValidationOutcome.VALIDATION_FAILED,
)

/**
 * Independent deterministic gate for LLM-generated harness proposals.
 * The designer may propose a structure, but only this component can approve it for publication.
 */
@Component
class HarnessValidator(private val agents: AgentDirectory) {
    fun validate(view: HarnessView, ownerId: UUID): ValidationResult {
        val checks = mutableListOf<ValidationCheck>()
        fun check(code: String, passed: Boolean, failure: String) {
            checks += ValidationCheck(code, if (passed) ValidationCheckStatus.PASSED else ValidationCheckStatus.FAILED, failure)
        }

        val ordered = view.steps.sortedBy { it.sequenceNo }
        check("START_STEP", ordered.isNotEmpty(), "시작 단계가 없습니다.")
        check("AGENT_LIMIT", ordered.count { it.stepType == HarnessStepType.LLM } in 1..5, "LLM 구성원은 1명 이상 5명 이하여야 합니다.")
        check("SEQUENCE_CONTIGUOUS", ordered.map { it.sequenceNo } == (1..ordered.size).toList(), "단계 순서가 1부터 연속적이지 않습니다.")
        check("STEP_KEY_UNIQUE", ordered.map { it.stepKey }.distinct().size == ordered.size, "단계 키가 중복되었습니다.")
        check("RETRY_BOUNDED", ordered.all { it.maxRetries in 0..3 }, "재시도 횟수가 제한을 벗어났습니다.")
        check("TIMEOUT_BOUNDED", ordered.all { it.timeoutSeconds in 1..900 }, "단계 타임아웃은 1~900초여야 합니다.")
        check("LLM_AGENT_PRESENT", ordered.filter { it.stepType == HarnessStepType.LLM }.all { it.agentId != null }, "LLM 단계에 담당 구성원이 없습니다.")
        check("APPROVAL_AGENT_ABSENT", ordered.filter { it.stepType == HarnessStepType.APPROVAL }.all { it.agentId == null }, "승인 단계는 AI 구성원을 직접 실행할 수 없습니다.")

        val approvalPoints = ordered.count { it.stepType == HarnessStepType.APPROVAL } + ordered.count { it.requiresApproval }
        check("APPROVAL_BOUNDED", approvalPoints <= 1, "MVP 하네스는 사용자 승인 지점을 하나만 가질 수 있습니다.")
        check("INPUT_MAPPING", ordered.drop(1).all { it.inputMapping.isNotEmpty() }, "첫 단계 이후에는 이전 결과를 받는 입력 매핑이 필요합니다.")

        val ids = ordered.map { it.id }.toSet()
        check("EDGE_REFERENCES", view.edges.all { it.sourceStepId in ids && (it.targetStepId == null || it.targetStepId in ids) }, "존재하지 않는 단계가 연결되어 있습니다.")
        val actualEdges = view.edges.associateBy { it.sourceStepId }
        val sequential = ordered.zipWithNext().all { (source, target) -> actualEdges[source.id]?.targetStepId == target.id }
        check("SEQUENTIAL_FLOW", ordered.size <= 1 || (view.edges.size == ordered.size - 1 && sequential), "MVP에서는 모든 단계가 하나의 순차 흐름으로 연결되어야 합니다.")
        check("END_STEP", ordered.isNotEmpty() && actualEdges[ordered.last().id] == null, "명확한 종료 단계가 필요합니다.")

        val resultStep = view.harness.resultStepKey?.let { key -> ordered.firstOrNull { it.stepKey == key } }
        check("RESULT_CONTRACT", resultStep != null && resultStep.stepType != HarnessStepType.APPROVAL, "최종 결과를 만드는 AI 단계를 지정해야 합니다.")

        val ownedAgents = ordered.mapNotNull { it.agentId }.all { agentId ->
            runCatching { agents.requireOwned(agentId, ownerId) }.isSuccess
        }
        check("AGENT_OWNERSHIP", ownedAgents, "존재하지 않거나 다른 사용자의 구성원이 연결되어 있습니다.")

        val errors = checks.filter { it.status == ValidationCheckStatus.FAILED }.map { it.message }.distinct()
        return ValidationResult(errors.isEmpty(), errors, checks)
    }

    companion object {
        const val VERSION = "harness-validator-1"
    }
}
