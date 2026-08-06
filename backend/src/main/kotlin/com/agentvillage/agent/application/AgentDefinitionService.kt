package com.agentvillage.agent.application

import com.agentvillage.agent.domain.AgentDefinition
import com.agentvillage.agent.infrastructure.AgentDefinitionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class GenerateDefinitionCommand(
    val taskDescription: String,
    val desiredOutput: String,
    val prohibitions: String,
    val inputSchema: Map<String, Any>?,
    val outputSchema: Map<String, Any>?,
    val requiredEvidence: String = "",
    val outputStyle: String = "",
    val rewriteCriteria: String = "",
    val approvalCriteria: String = "",
)

@Service
class AgentDefinitionService(
    private val agentService: AgentService,
    private val definitions: AgentDefinitionRepository,
) {
    @Transactional
    fun generate(agentId: UUID, ownerId: UUID, command: GenerateDefinitionCommand): AgentDefinition {
        val agent = agentService.getOwned(agentId, ownerId)
        val input = command.inputSchema ?: schema("input", "작업에 필요한 사용자 입력")
        val output = command.outputSchema ?: schema("result", command.desiredOutput)
        val prohibitions = command.prohibitions.trim().ifEmpty { "확인되지 않은 사실을 단정하거나 비밀정보를 출력하지 않는다." }
        val task = command.taskDescription.trim().ifEmpty { agent.script }
        val desired = command.desiredOutput.trim().ifEmpty { "${agent.role} 역할에 맞는 검증 가능한 결과물" }
        val evidence = command.requiredEvidence.trim().ifEmpty {
            "결과에 사용한 근거와 직접 확인한 범위를 구분하고, 확인하지 못한 내용은 미검증으로 표시한다."
        }
        val outputStyle = command.outputStyle.trim().ifEmpty { "독자가 바로 사용할 수 있도록 제목과 항목을 나눈 구조화된 문서" }
        val rewriteCriteria = command.rewriteCriteria.trim().ifEmpty {
            "필수 입력 누락, 근거 없는 주장, 출력 형식 불일치 또는 사용자의 질문에 답하지 못한 경우"
        }
        val approvalCriteria = command.approvalCriteria.trim().ifEmpty {
            "요청한 결과가 모두 포함되고, 근거와 미검증 범위가 구분되며, 출력 스키마를 만족한 경우"
        }
        val agentMd = """# ${agent.name}

## 역할
${agent.role}

## 책임
$task

## 작업 순서
1. 입력을 확인하고 부족한 정보를 표시한다.
2. ${agent.script}
3. 출력 형식과 완료 조건을 검증한다.

## 입력
`input-schema.json`을 따른다.

## 출력
$desired

## 완료 조건
요청된 결과가 출력 스키마와 품질 기준을 만족한다.

## 실패 조건
필수 입력 누락, 도구 실패, 출력 스키마 불일치 또는 제한 시간 초과.

## 사용 도구
허용된 LLM 및 사용자가 연결한 외부 서비스만 사용한다.

## 전달 정보
검증된 출력과 명시적인 오류만 다음 에이전트에 전달한다.
""".trimIndent()
        val guideMd = """# ${agent.name} 작업 가이드

## 작업 가이드
${agent.guide ?: "입력 의도를 먼저 확인하고 단계별로 결과를 만든다."}

## 품질 기준
- 출력 스키마 준수
- 핵심 주장과 결과의 일관성 확인
- 불확실한 내용은 명시
- 필수 근거: $evidence

## 금지사항
$prohibitions

## 사실 검증 기준
근거 없는 사실을 만들지 않으며 출처가 없으면 추정임을 표시한다.

## 출력 스타일
$outputStyle

## 재작성 기준
$rewriteCriteria

## 승인 기준
$approvalCriteria
""".trimIndent()
        val existing = definitions.findById(agentId).orElse(null)
        val definition = existing?.apply {
            taskDescription = task; desiredOutput = desired; this.prohibitions = prohibitions
            inputSchema = input; outputSchema = output; agentMarkdown = agentMd; guideMarkdown = guideMd
            generatedAt = Instant.now(); updatedAt = Instant.now()
        } ?: AgentDefinition(agentId, task, desired, prohibitions, input, output, agentMd, guideMd)
        return definitions.save(definition)
    }

    @Transactional(readOnly = true)
    fun get(agentId: UUID, ownerId: UUID): AgentDefinition {
        agentService.getOwned(agentId, ownerId)
        return definitions.findById(agentId).orElseThrow()
    }

    private fun schema(name: String, description: String): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(name to mapOf("type" to "string", "description" to description)),
        "required" to listOf(name),
        "additionalProperties" to false,
    )
}
