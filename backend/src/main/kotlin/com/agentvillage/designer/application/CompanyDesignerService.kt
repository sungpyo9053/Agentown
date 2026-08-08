package com.agentvillage.designer.application

import com.agentvillage.agent.application.AgentDefinitionService
import com.agentvillage.agent.application.AgentService
import com.agentvillage.agent.application.GenerateDefinitionCommand
import com.agentvillage.agent.application.SaveAgentCommand
import com.agentvillage.common.domain.Visibility
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.execution.application.AiModelGatewayRegistry
import com.agentvillage.execution.application.AiModelRequest
import com.agentvillage.execution.application.DecryptedCredential
import com.agentvillage.harness.application.HarnessService
import com.agentvillage.harness.domain.HarnessResultFormat
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.agentvillage.llmcredential.application.SupportedModelCatalog
import com.agentvillage.llmcredential.domain.LlmProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class CompanyDesignCommand(
    val companyName: String,
    val goal: String,
    val primaryInput: String,
    val desiredOutput: String,
    val outputFormat: HarnessResultFormat = HarnessResultFormat.AUTO,
    val requiredEvidence: String,
    val prohibitions: String,
    val approvalPolicy: String,
    val provider: LlmProvider,
    val model: String,
    val credentialId: UUID?,
    val stubMode: Boolean,
)

data class DesignedAgent(
    val key: String,
    val name: String,
    val role: String,
    val responsibility: String,
    val taskDescription: String,
    val desiredOutput: String,
    val requiredEvidence: String,
    val guide: String,
    val prohibitions: String,
    val rewriteCriteria: String,
    val approvalCriteria: String,
    val characterKey: String = "manager",
    val provider: LlmProvider,
    val recommendedModel: String,
)

data class DesignedStep(
    val key: String,
    val agentKey: String,
    val sequence: Int,
    val maxRetries: Int = 2,
)

data class CompanyDesignDraft(
    val companyName: String,
    val goal: String,
    val agents: List<DesignedAgent>,
    val steps: List<DesignedStep>,
    val approvalAfterLast: Boolean = true,
    val designSource: String = "BYOK",
    val resultAgentKey: String? = null,
    val outputFormat: HarnessResultFormat = HarnessResultFormat.AUTO,
)

data class CompanyDesignResult(val draft: CompanyDesignDraft, val valid: Boolean, val errors: List<String>)
data class AppliedCompanyDesign(val harnessId: UUID, val agentIds: List<UUID>)

@Service
class CompanyDesignerService(
    private val credentials: CredentialDirectory,
    private val models: SupportedModelCatalog,
    private val gateways: AiModelGatewayRegistry,
    private val mapper: ObjectMapper,
    private val agents: AgentService,
    private val definitions: AgentDefinitionService,
    private val harnesses: HarnessService,
    @Value("\${execution.stub-enabled:true}") private val stubEnabled: Boolean,
) {
    fun design(ownerId: UUID, command: CompanyDesignCommand): CompanyDesignResult {
        models.requireSupported(command.provider, command.model)
        val draft = if (command.stubMode) {
            if (!stubEnabled) throw BadRequestException("STUB_DISABLED", "Stub 설계는 이 환경에서 비활성화되어 있습니다.")
            stubDraft(command)
        } else {
            val credentialId = command.credentialId
                ?: throw BadRequestException("LLM_CREDENTIAL_NOT_FOUND", "설계에 사용할 연결 완료 API 키를 선택해 주세요.")
            credentials.requireActive(credentialId, ownerId, command.provider)
            credentials.withDecrypted(credentialId, ownerId, command.provider) { secret, options ->
                val response = gateways.get(command.provider).execute(
                    DecryptedCredential(command.provider, secret, options),
                    AiModelRequest(
                        model = command.model,
                        systemPrompt = designerSystemPrompt(),
                        input = designerInput(command),
                        temperature = BigDecimal("0.20"),
                        maxOutputTokens = 8_000,
                        timeoutSeconds = 120,
                        providerOptions = options,
                    ),
                )
                parseDraft(response.content, command).let { draft ->
                    draft.copy(outputFormat = if (command.outputFormat == HarnessResultFormat.AUTO) draft.outputFormat else command.outputFormat)
                }
            }
        }
        val errors = validate(draft, ownerId, command.credentialId, command.stubMode)
        return CompanyDesignResult(draft, errors.isEmpty(), errors)
    }

    fun validateDraft(ownerId: UUID, draft: CompanyDesignDraft, credentialId: UUID?, stubMode: Boolean): List<String> =
        validate(draft, ownerId, credentialId, stubMode)

    @Transactional
    fun apply(ownerId: UUID, draft: CompanyDesignDraft, credentialId: UUID?, stubMode: Boolean): AppliedCompanyDesign {
        val errors = validate(draft, ownerId, credentialId, stubMode)
        if (errors.isNotEmpty()) throw BadRequestException("COMPANY_DESIGN_INVALID", errors.joinToString(" "))
        val ordered = draft.steps.sortedBy { it.sequence }
        val agentByKey = draft.agents.associateBy { it.key }
        val createdIds = draft.agents.associate { designed ->
            val linkedCredential = if (stubMode) null else credentialId
            val created = agents.create(ownerId, SaveAgentCommand(
                name = designed.name,
                role = designed.role,
                personality = "회사 목표에 맞춰 근거와 완료 조건을 확인하는 AI 구성원",
                characterKey = designed.characterKey.takeIf { it in allowedCharacters } ?: "manager",
                systemPrompt = designed.responsibility,
                script = designed.taskDescription,
                guide = designed.guide,
                modelProvider = designed.provider,
                modelName = designed.recommendedModel,
                credentialId = linkedCredential,
                temperature = BigDecimal("0.20"),
                maxOutputTokens = 4_096,
                timeoutSeconds = 120,
                providerOptions = emptyMap(),
                visibility = Visibility.PRIVATE,
            ))
            definitions.generate(created.id, ownerId, GenerateDefinitionCommand(
                taskDescription = designed.taskDescription,
                desiredOutput = designed.desiredOutput,
                prohibitions = designed.prohibitions,
                inputSchema = null,
                outputSchema = null,
                requiredEvidence = designed.requiredEvidence,
                outputStyle = designed.guide,
                rewriteCriteria = designed.rewriteCriteria,
                approvalCriteria = designed.approvalCriteria,
            ))
            designed.key to created.id
        }
        val harness = harnesses.create(ownerId, draft.companyName, draft.goal)
        harnesses.connect(
            harness.id,
            ownerId,
            ordered.map { createdIds.getValue(agentByKey.getValue(it.agentKey).key) },
            approvalAfterLast = draft.approvalAfterLast,
        )
        val resultAgentId = (draft.resultAgentKey?.let(createdIds::get)
            ?: ordered.asReversed().mapNotNull { createdIds[it.agentKey] }.first())
        harnesses.configureResult(harness.id, ownerId, resolvedFormat(draft.outputFormat, agentByKey.getValue(
            draft.resultAgentKey?.takeIf(agentByKey::containsKey) ?: ordered.last().agentKey
        ).desiredOutput), resultAgentId)
        return AppliedCompanyDesign(harness.id, createdIds.values.toList())
    }

    private fun parseDraft(content: String, command: CompanyDesignCommand): CompanyDesignDraft {
        val json = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { mapper.readValue(json, CompanyDesignDraft::class.java) }
            .getOrElse { throw BadRequestException("LLM_RESPONSE_INVALID", "설계 모델이 유효한 회사 구조를 반환하지 않았습니다.") }
            .copy(designSource = command.provider.name)
    }

    private fun validate(draft: CompanyDesignDraft, ownerId: UUID, credentialId: UUID?, stubMode: Boolean): List<String> {
        val errors = mutableListOf<String>()
        if (draft.companyName.isBlank() || draft.goal.isBlank()) errors += "회사 이름과 목표가 필요합니다."
        if (draft.agents.isEmpty() || draft.agents.size > 5) errors += "구성원은 1명 이상 5명 이하여야 합니다."
        if (draft.agents.map { it.key }.any(String::isBlank) || draft.agents.map { it.key }.distinct().size != draft.agents.size) errors += "구성원 key는 비어 있지 않고 중복되지 않아야 합니다."
        if (draft.agents.any { it.name.isBlank() || it.role.isBlank() || it.taskDescription.isBlank() || it.desiredOutput.isBlank() }) errors += "모든 구성원에게 이름, 역할, 작업과 결과가 필요합니다."
        if (draft.steps.isEmpty() || draft.steps.size != draft.agents.size) errors += "각 구성원은 정확히 한 번 실행 순서에 포함되어야 합니다."
        if (draft.steps.map { it.sequence }.sorted() != (1..draft.steps.size).toList()) errors += "실행 순서는 1부터 연속되어야 합니다."
        if (draft.steps.map { it.agentKey }.toSet() != draft.agents.map { it.key }.toSet()) errors += "실행 단계가 존재하는 구성원을 정확히 참조해야 합니다."
        if (draft.resultAgentKey == null || draft.agents.none { it.key == draft.resultAgentKey }) errors += "최종 결과 담당 구성원이 존재하지 않습니다."
        if (draft.steps.any { it.maxRetries !in 0..3 }) errors += "재시도 횟수는 0~3회여야 합니다."
        if (draft.agents.any { containsDangerousWork(it.taskDescription + " " + it.responsibility) }) errors += "사용자 코드, Shell, 패키지 또는 컨테이너 실행 작업은 허용되지 않습니다."
        draft.agents.forEach { agent ->
            runCatching { models.requireSupported(agent.provider, agent.recommendedModel) }
                .onFailure { errors += "${agent.name}의 추천 모델이 지원 목록에 없습니다." }
        }
        if (!stubMode) {
            if (credentialId == null) errors += "연결 완료된 API 키가 필요합니다."
            else draft.agents.forEach { agent ->
                runCatching { credentials.requireActive(credentialId, ownerId, agent.provider) }
                    .onFailure { errors += "${agent.name}의 ${agent.provider} 연결 완료 API 키가 필요합니다." }
            }
        }
        return errors.distinct()
    }

    private fun containsDangerousWork(text: String): Boolean {
        val normalized = text.lowercase()
        return forbiddenWork.any(normalized::contains)
    }

    private fun stubDraft(command: CompanyDesignCommand): CompanyDesignDraft {
        val templates = when {
            listOf("글", "블로그", "콘텐츠").any(command.goal::contains) -> listOf(
                Triple("researcher", "리서처", "입력 주제에 필요한 근거와 미검증 범위를 조사해 전달한다."),
                Triple("writer", "작가", "검증된 조사 결과로 사용자의 질문에 답하는 결과물을 작성한다."),
                Triple("reviewer", "검수자", "원본 근거와 결과를 대조해 승인 또는 구체적인 수정 요청을 반환한다."),
            )
            listOf("문의", "고객", "상담").any(command.goal::contains) -> listOf(
                Triple("classifier", "문의 분류자", "문의 의도와 긴급도, 필요한 정책을 분류한다."),
                Triple("responder", "응답 작성자", "정책과 고객 상황에 맞는 답변 초안을 작성한다."),
                Triple("reviewer", "품질 검수자", "개인정보, 정책 위반과 누락을 검사해 승인 또는 반려한다."),
            )
            else -> listOf(
                Triple("planner", "업무 설계자", "입력과 목표를 분석해 실행 가능한 작업 계획과 전달 항목을 만든다."),
                Triple("specialist", "실무 담당자", "계획과 근거를 사용해 요청된 결과물을 작성한다."),
                Triple("reviewer", "품질 검수자", "입력, 근거와 결과를 대조해 완료 조건을 검증한다."),
            )
        }
        val resultAgentKey = templates.firstOrNull { (key, role) -> key !in setOf("reviewer", "validator") && listOf("작가", "작성자", "담당자", "specialist", "responder", "writer").any { it in (key + role).lowercase() } }?.first
            ?: templates.dropLast(1).lastOrNull()?.first ?: templates.last().first
        val designed = templates.mapIndexed { index, (key, role, task) -> DesignedAgent(
            key = key,
            name = role,
            role = role,
            responsibility = task,
            taskDescription = "$task 입력: ${command.primaryInput}",
            desiredOutput = when {
                key == resultAgentKey -> command.desiredOutput
                index == templates.lastIndex -> "APPROVED 또는 REJECTED 판정, 근거와 구체적인 수정 지시"
                else -> "다음 구성원이 바로 사용할 수 있는 구조화된 중간 결과"
            },
            requiredEvidence = command.requiredEvidence.ifBlank { "제공된 입력과 직접 확인한 근거를 구분한다." },
            guide = "결론과 근거, 미검증 범위, 다음 전달 항목을 명확히 구분한다.",
            prohibitions = command.prohibitions.ifBlank { "확인하지 않은 사실과 비밀정보를 만들거나 출력하지 않는다." },
            rewriteCriteria = "필수 입력 누락, 근거 없는 주장, 출력 형식 불일치가 있으면 다시 작성한다.",
            approvalCriteria = if (index == templates.lastIndex) command.approvalPolicy.ifBlank { "요청 결과와 근거, 한계가 모두 확인되면 승인한다." } else "다음 단계가 추가 질문 없이 작업할 수 있으면 완료한다.",
            characterKey = listOf("developer", "writer", "reviewer", "designer", "manager")[index % 5],
            provider = command.provider,
            recommendedModel = command.model,
        ) }
        return CompanyDesignDraft(
            companyName = command.companyName,
            goal = command.goal,
            agents = designed,
            steps = designed.mapIndexed { index, agent -> DesignedStep("step-${index + 1}", agent.key, index + 1, if (index == designed.lastIndex) 2 else 1) },
            approvalAfterLast = true,
            designSource = "STUB",
            resultAgentKey = resultAgentKey,
            outputFormat = resolvedFormat(command.outputFormat, command.desiredOutput),
        )
    }

    private fun resolvedFormat(format: HarnessResultFormat, desiredOutput: String): HarnessResultFormat {
        if (format != HarnessResultFormat.AUTO) return format
        val normalized = desiredOutput.lowercase()
        return when {
            "html" in normalized || "웹페이지" in normalized -> HarnessResultFormat.HTML
            "markdown" in normalized || "마크다운" in normalized || Regex("\\bmd\\b").containsMatchIn(normalized) -> HarnessResultFormat.MARKDOWN
            "json" in normalized -> HarnessResultFormat.JSON
            "csv" in normalized -> HarnessResultFormat.CSV
            listOf("pdf", "ppt", "docx", "이미지", "영상", "음성", "zip").any(normalized::contains) -> HarnessResultFormat.EXTERNAL
            else -> HarnessResultFormat.TEXT
        }
    }

    private fun designerSystemPrompt() = """You design safe declarative AI companies for Agentown.
Return one JSON object only, without Markdown fences. Use the exact CompanyDesignDraft field names.
Create 1 to 5 sequential agents. Every agent must have a unique key and exactly one step.
Generalize this operating pattern: root orchestration, agents for responsibilities, guides for reusable quality policy, schemas for handoff contracts, and a final reviewer or approval boundary.
Never propose Python, Node.js, Shell, package installation, Dockerfile, binary execution, or arbitrary user code.
Do not include credentials, API keys, tokens, organization IDs, user inputs, or execution results.
Keep provider and recommendedModel exactly as supplied by the user.
""".trimIndent()

    private fun designerInput(command: CompanyDesignCommand) = """
Company name: ${command.companyName}
Goal: ${command.goal}
Primary input: ${command.primaryInput}
Desired output: ${command.desiredOutput}
Requested output format: ${command.outputFormat}
Required evidence: ${command.requiredEvidence}
Prohibitions: ${command.prohibitions}
Human approval policy: ${command.approvalPolicy}
Provider: ${command.provider}
Recommended model: ${command.model}

Required JSON shape:
{"companyName":"...","goal":"...","agents":[{"key":"...","name":"...","role":"any free-form job role","responsibility":"...","taskDescription":"...","desiredOutput":"...","requiredEvidence":"...","guide":"...","prohibitions":"...","rewriteCriteria":"...","approvalCriteria":"...","characterKey":"writer|reviewer|designer|developer|manager (appearance only, never a role constraint)","provider":"${command.provider}","recommendedModel":"${command.model}"}],"steps":[{"key":"step-1","agentKey":"...","sequence":1,"maxRetries":1}],"approvalAfterLast":true,"designSource":"${command.provider}","resultAgentKey":"writer","outputFormat":"AUTO|TEXT|MARKDOWN|HTML|JSON|CSV|EXTERNAL"}
""".trimIndent()

    companion object {
        private val allowedCharacters = setOf("writer", "reviewer", "designer", "developer", "manager")
        private val forbiddenWork = listOf("npm install", "pip install", "dockerfile", "shell 명령", "shell command", "사용자 코드 실행", "arbitrary code", "binary execution")
    }
}
