package com.agentvillage.execution.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.harness.domain.HarnessResultFormat
import com.agentvillage.harness.domain.HarnessStepType
import com.agentvillage.llmcredential.domain.LlmProvider
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

data class SnapshotAgentConfig(
    val key: String,
    val sourceAgentId: UUID,
    val name: String,
    val role: String,
    val systemPrompt: String?,
    val script: String,
    val guide: String?,
    val provider: LlmProvider,
    val model: String,
    val temperature: BigDecimal,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val providerOptions: Map<String, Any>,
)

data class SnapshotStepConfig(
    val key: String,
    val agentKey: String?,
    val type: HarnessStepType,
    val sequence: Int,
    val maxRetries: Int,
    val timeoutSeconds: Int,
    val requiresApproval: Boolean,
    val inputMapping: Map<String, Any>,
)

data class ImmutableExecutionPlan(
    val name: String,
    val resultFormat: HarnessResultFormat,
    val resultStepKey: String?,
    val agents: Map<String, SnapshotAgentConfig>,
    val steps: List<SnapshotStepConfig>,
)

@Component
class ExecutionSnapshotReader {
    fun read(snapshot: Map<String, Any>): ImmutableExecutionPlan {
        if ((snapshot["formatVersion"] as? Number)?.toInt() != 2) invalid("지원하지 않는 하네스 스냅샷 형식입니다.")
        val validation = snapshot["validation"] as? Map<*, *> ?: invalid("검증 기록이 없는 하네스 버전입니다.")
        if (validation["outcome"]?.toString() != "VALIDATED") invalid("검증 Gate를 통과하지 않은 하네스 버전입니다.")

        val agentMaps = listOfMaps(snapshot["agents"])
        val agents = agentMaps.associate { raw ->
            val key = required(raw, "key")
            key to SnapshotAgentConfig(
                key = key,
                sourceAgentId = uuid(raw, "sourceAgentId"),
                name = required(raw, "name"),
                role = required(raw, "role"),
                systemPrompt = raw["systemPrompt"]?.toString()?.takeIf(String::isNotBlank),
                script = required(raw, "script"),
                guide = raw["guide"]?.toString()?.takeIf(String::isNotBlank),
                provider = enumValue(raw, "provider"),
                model = required(raw, "recommendedModel"),
                temperature = raw["temperature"]?.toString()?.toBigDecimalOrNull() ?: BigDecimal("0.20"),
                maxOutputTokens = number(raw, "maxOutputTokens", 4_096),
                timeoutSeconds = number(raw, "timeoutSeconds", 120),
                providerOptions = stringMap(raw["providerOptions"]),
            )
        }
        if (agents.size != agentMaps.size) invalid("스냅샷 구성원 key가 중복되었습니다.")

        val steps = listOfMaps(snapshot["steps"]).map { raw ->
            SnapshotStepConfig(
                key = required(raw, "id"),
                agentKey = raw["agentKey"]?.toString()?.takeIf(String::isNotBlank),
                type = enumValue(raw, "type"),
                sequence = number(raw, "sequence", 0),
                maxRetries = number(raw, "maxRetries", 0),
                timeoutSeconds = number(raw, "timeoutSeconds", 120),
                requiresApproval = raw["requiresApproval"] == true,
                inputMapping = stringMap(raw["inputMapping"]),
            )
        }.sortedBy { it.sequence }
        if (steps.isEmpty()) invalid("실행 단계가 없는 하네스 버전입니다.")
        steps.filter { it.type == HarnessStepType.LLM }.forEach { step ->
            if (step.agentKey == null || step.agentKey !in agents) invalid("${step.key} 단계의 구성원 정의가 없습니다.")
        }
        val result = snapshot["result"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return ImmutableExecutionPlan(
            name = required(snapshot, "name"),
            resultFormat = runCatching { HarnessResultFormat.valueOf(result["format"]?.toString() ?: "AUTO") }.getOrElse { HarnessResultFormat.AUTO },
            resultStepKey = result["stepKey"]?.toString()?.takeIf(String::isNotBlank),
            agents = agents,
            steps = steps,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun listOfMaps(value: Any?): List<Map<String, Any>> = (value as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    private fun stringMap(value: Any?): Map<String, Any> = value as? Map<String, Any> ?: emptyMap()
    private fun required(map: Map<*, *>, key: String) = map[key]?.toString()?.takeIf(String::isNotBlank) ?: invalid("스냅샷에 $key 값이 없습니다.")
    private fun uuid(map: Map<*, *>, key: String) = runCatching { UUID.fromString(required(map, key)) }.getOrElse { invalid("스냅샷의 $key 값이 올바르지 않습니다.") }
    private fun number(map: Map<*, *>, key: String, default: Int) = (map[key] as? Number)?.toInt() ?: map[key]?.toString()?.toIntOrNull() ?: default
    private inline fun <reified T : Enum<T>> enumValue(map: Map<*, *>, key: String) = runCatching { enumValueOf<T>(required(map, key)) }.getOrElse { invalid("스냅샷의 $key 값이 올바르지 않습니다.") }
    private fun invalid(message: String): Nothing = throw BadRequestException("EXECUTION_SNAPSHOT_INVALID", message)
}
