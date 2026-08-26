package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.*
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class HarnessTemplateView(val templateKey: String, val name: String, val category: String, val status: HarnessTemplateStatus, val activeVersionNo: Int?)
data class HarnessTemplateCatalogStatus(val configured: Boolean, val activeTemplates: List<HarnessTemplateView>, val lastSyncStatus: String?, val lastSyncAt: Instant?, val humanSetupRequired: List<String>)
data class HarnessTemplateSyncResult(val runId: UUID, val status: String, val imported: Int, val rejected: Int, val errors: List<String>)

@Service
class HarnessTemplateCatalogService(
    private val templates: HarnessTemplateRepository,
    private val versions: HarnessTemplateVersionRepository,
    private val syncRuns: HarnessTemplateSyncRunRepository,
    private val notion: NotionTemplateSource,
    private val mapper: ObjectMapper,
    @Value("\${notion.template-library.enabled:false}") private val enabled: Boolean,
    @Value("\${notion.template-library.token:}") private val token: String,
    @Value("\${notion.template-library.data-source-id:}") private val dataSourceId: String,
) {
    @Transactional
    fun preview(bundle: MetaAgentDesignBundle): HarnessTemplateVersion {
        val selection = requireNotNull(bundle.proposal.templateSelection) { "Template selection is required" }
        val contract = requireNotNull(bundle.proposal.executionContract) { "Template execution contract is required" }
        val contractJson: Map<String, Any?> = mapper.convertValue(contract, object : TypeReference<Map<String, Any?>>() {})
        val outputSchema = mapOf("fields" to mapper.convertValue(bundle.proposal.outputSchema, object : TypeReference<List<Map<String, Any?>>>() {}))
        val contentHash = sha256(canonical(listOf(outputSchema, contractJson)))
        val template = templates.findByTemplateKey(selection.templateKey)
        template?.let { existing ->
            versions.findByTemplateIdAndVersionNo(existing.id, selection.version)?.let { version ->
                require(version.contentHash == contentHash) { "A changed output template requires a new version (${version.contentHash.take(8)} != ${contentHash.take(8)})" }
                return version
            }
        }
        require(selection.source in setOf("BUILT_IN", "GENERATED")) { "Template version was not found" }
        val initialState = if (selection.source == "BUILT_IN") HarnessTemplateVersionState.ACTIVE else HarnessTemplateVersionState.PREVIEWED
        val owner = template ?: templates.save(HarnessTemplate(
            templateKey = selection.templateKey,
            name = bundle.proposal.name,
            category = selection.source,
            status = if (initialState == HarnessTemplateVersionState.ACTIVE) HarnessTemplateStatus.ACTIVE else HarnessTemplateStatus.PREVIEWED,
        ))
        val version = versions.save(HarnessTemplateVersion(
            templateId = owner.id,
            versionNo = selection.version,
            state = initialState,
            source = if (selection.source == "GENERATED") HarnessTemplateSource.GENERATED else HarnessTemplateSource.BUILT_IN,
            notionPageId = null,
            contentHash = contentHash,
            intentExamples = listOf(selection.matchReason),
            requiredFacts = bundle.guideDefinitions.flatMap { guide -> guide.fields.filter { it.required }.map { it.key } }.distinct(),
            templateDefinition = mapOf(
                "templateSelection" to mapper.convertValue(selection, object : TypeReference<Map<String, Any?>>() {}),
                "contentSchema" to mapper.convertValue(bundle.proposal.outputSchema, object : TypeReference<List<Map<String, Any?>>>() {}),
                "executionContract" to contractJson,
            ),
            outputSchema = outputSchema,
            acceptanceCases = listOf(mapOf("name" to "built-in-contract", "expected" to "VALID")),
            executionContract = contractJson,
            validation = mapOf("valid" to true, "validatorVersion" to "builder-validator-1"),
        ))
        owner.status = if (initialState == HarnessTemplateVersionState.ACTIVE) HarnessTemplateStatus.ACTIVE else HarnessTemplateStatus.PREVIEWED
        if (initialState == HarnessTemplateVersionState.ACTIVE) owner.activeVersionNo = version.versionNo
        return version
    }

    @Transactional
    fun pin(bundle: MetaAgentDesignBundle): HarnessTemplateVersion {
        val version = preview(bundle)
        if (version.state == HarnessTemplateVersionState.PREVIEWED) version.state = HarnessTemplateVersionState.APPROVED
        require(version.state in setOf(HarnessTemplateVersionState.APPROVED, HarnessTemplateVersionState.ACTIVE)) { "Only APPROVED or ACTIVE template versions can be pinned" }
        val template = templates.findById(version.templateId).orElseThrow()
        if (template.status == HarnessTemplateStatus.PREVIEWED) template.status = HarnessTemplateStatus.APPROVED
        return version
    }

    @Transactional
    fun activate(versionId: UUID) {
        val version = versions.findById(versionId).orElseThrow { BadRequestException("OUTPUT_TEMPLATE_VERSION_NOT_FOUND", "출력 템플릿 버전을 찾을 수 없습니다.") }
        require(version.state in setOf(HarnessTemplateVersionState.APPROVED, HarnessTemplateVersionState.ACTIVE)) { "Only APPROVED templates can be activated" }
        val template = templates.findById(version.templateId).orElseThrow()
        template.activeVersionNo?.let { previousNo ->
            versions.findByTemplateIdAndVersionNo(template.id, previousNo)?.takeIf { it.id != version.id && it.state == HarnessTemplateVersionState.ACTIVE }?.state = HarnessTemplateVersionState.APPROVED
        }
        version.state = HarnessTemplateVersionState.ACTIVE
        template.status = HarnessTemplateStatus.ACTIVE
        template.activeVersionNo = version.versionNo
    }

    @Transactional
    fun deprecate(versionId: UUID) {
        val version = versions.findById(versionId).orElseThrow { BadRequestException("OUTPUT_TEMPLATE_VERSION_NOT_FOUND", "출력 템플릿 버전을 찾을 수 없습니다.") }
        require(version.state == HarnessTemplateVersionState.APPROVED) { "Only a non-active APPROVED template can be deprecated" }
        version.state = HarnessTemplateVersionState.DEPRECATED
    }

    @Transactional
    fun derivePreview(baseVersionId: UUID, instruction: String): HarnessTemplateVersion {
        val base = versions.findById(baseVersionId).orElseThrow { BadRequestException("OUTPUT_TEMPLATE_VERSION_NOT_FOUND", "출력 템플릿 버전을 찾을 수 없습니다.") }
        require(base.state in setOf(HarnessTemplateVersionState.APPROVED, HarnessTemplateVersionState.ACTIVE)) { "Only approved templates can be revised" }
        val latest = versions.findTopByTemplateIdOrderByVersionNoDesc(base.templateId)
        val nextNo = (latest?.versionNo ?: base.versionNo) + 1
        val contract = mapper.convertValue(base.executionContract, TemplateExecutionContract::class.java)
        val rules = contract.qualityRules.toMutableMap().apply {
            if (instruction.contains("숫자")) put("minimumNumericFacts", 3)
            if (instruction.contains("너무 길") || instruction.contains("짧게")) put("maxLength", 800)
            if (instruction.contains("관심 종목")) put("watchlistFirst", true)
            if (instruction.contains("호재") || instruction.contains("악재")) put("sentimentClassification", true)
        }
        require(rules != contract.qualityRules) { "지원되는 출력 템플릿 수정 요청이 아닙니다." }
        val revisedContract = contract.copy(qualityRuleVersion = nextNo.toString(), qualityRules = rules)
        val contractJson: Map<String, Any?> = mapper.convertValue(revisedContract, object : TypeReference<Map<String, Any?>>() {})
        val definition = base.templateDefinition + ("executionContract" to contractJson)
        val contentHash = sha256(canonical(listOf(base.outputSchema, contractJson)))
        versions.findByTemplateIdAndContentHash(base.templateId, contentHash)?.let { return it }
        return versions.save(HarnessTemplateVersion(
            templateId = base.templateId, versionNo = nextNo, state = HarnessTemplateVersionState.PREVIEWED,
            source = HarnessTemplateSource.GENERATED, notionPageId = null, contentHash = contentHash,
            intentExamples = base.intentExamples, requiredFacts = base.requiredFacts,
            templateDefinition = definition, outputSchema = base.outputSchema,
            acceptanceCases = base.acceptanceCases + mapOf("name" to "natural-language-revision-v$nextNo", "instruction" to instruction),
            executionContract = contractJson,
            validation = mapOf("valid" to true, "validatorVersion" to "output-template-validator-1", "derivedFrom" to base.id.toString()),
        ))
    }

    @Transactional(readOnly = true)
    fun status(): HarnessTemplateCatalogStatus {
        val last = syncRuns.findTopByOrderByStartedAtDesc()
        val configured = enabled && token.isNotBlank() && dataSourceId.isNotBlank()
        return HarnessTemplateCatalogStatus(
            configured,
            templates.findAllByStatusOrderByCategoryAscNameAsc(HarnessTemplateStatus.ACTIVE).map { HarnessTemplateView(it.templateKey, it.name, it.category, it.status, it.activeVersionNo) },
            last?.status,
            last?.finishedAt ?: last?.startedAt,
            if (configured) emptyList() else listOf("Notion 내부 연결 생성", "템플릿 데이터 소스 공유", "서버에 NOTION_TEMPLATE_TOKEN과 NOTION_TEMPLATE_DATA_SOURCE_ID 등록"),
        )
    }

    @Transactional
    fun sync(): HarnessTemplateSyncResult {
        if (!(enabled && token.isNotBlank() && dataSourceId.isNotBlank())) throw BadRequestException("NOTION_TEMPLATE_LIBRARY_NOT_CONFIGURED", "Notion 템플릿 라이브러리 설정이 필요합니다.")
        val run = syncRuns.save(HarnessTemplateSyncRun(source = HarnessTemplateSource.NOTION))
        val errors = mutableListOf<String>()
        return try {
            notion.fetchApproved().forEach { record ->
                runCatching { importRecord(record) }.onSuccess { changed -> if (changed) run.importedCount += 1 }
                    .onFailure { error -> run.rejectedCount += 1; errors += "${record.templateKey.ifBlank { record.pageId }}: ${sanitize(error.message.orEmpty())}" }
            }
            run.status = if (errors.isEmpty()) "SUCCEEDED" else "PARTIAL"
            run.failureSummary = errors.joinToString(" | ").take(500).ifBlank { null }
            run.finishedAt = Instant.now()
            HarnessTemplateSyncResult(run.id, run.status, run.importedCount, run.rejectedCount, errors)
        } catch (error: Exception) {
            run.status = "FAILED"; run.failureSummary = sanitize(error.message.orEmpty()); run.finishedAt = Instant.now()
            HarnessTemplateSyncResult(run.id, run.status, 0, 0, listOf(run.failureSummary.orEmpty()))
        }
    }

    private fun importRecord(record: NotionTemplateRecord): Boolean {
        require(record.status == "APPROVED") { "Only APPROVED rows can be imported" }
        require(record.templateKey.matches(Regex("^[a-z][a-z0-9-]{2,99}$"))) { "Invalid template key" }
        require(record.name.isNotBlank() && record.category.isNotBlank()) { "Name and category are required" }
        require(record.declaredVersion > 0) { "Version must be positive" }
        require(record.intentExamples.isNotEmpty() && record.requiredFacts.isNotEmpty()) { "Intent examples and required facts are required" }
        val definition: Map<String, Any?> = mapper.readValue(record.templateDefinitionJson, object : TypeReference<Map<String, Any?>>() {})
        val outputSchema: Map<String, Any?> = mapper.readValue(record.outputSchemaJson, object : TypeReference<Map<String, Any?>>() {})
        val cases: List<Map<String, Any?>> = mapper.readValue(record.acceptanceCasesJson, object : TypeReference<List<Map<String, Any?>>>() {})
        val contract: TemplateExecutionContract = mapper.convertValue(requireNotNull(definition["executionContract"]) { "executionContract is required" }, TemplateExecutionContract::class.java)
        validate(record, definition, contract, outputSchema, cases)
        val contentHash = sha256(listOf(record.templateDefinitionJson, record.outputSchemaJson, record.acceptanceCasesJson, record.intentExamples.joinToString("\n"), record.requiredFacts.joinToString("\n")).joinToString("\u0000"))
        val template = templates.findByTemplateKey(record.templateKey) ?: templates.save(HarnessTemplate(templateKey = record.templateKey, name = record.name, category = record.category))
        versions.findByTemplateIdAndContentHash(template.id, contentHash)?.let { return false }
        val latest = versions.findTopByTemplateIdOrderByVersionNoDesc(template.id)
        require(latest == null || record.declaredVersion > latest.versionNo) { "A changed template requires a higher version" }
        versions.save(HarnessTemplateVersion(
            templateId = template.id, versionNo = record.declaredVersion, state = HarnessTemplateVersionState.APPROVED,
            source = HarnessTemplateSource.NOTION, notionPageId = record.pageId, contentHash = contentHash,
            intentExamples = record.intentExamples, requiredFacts = record.requiredFacts,
            templateDefinition = definition, outputSchema = outputSchema, acceptanceCases = cases,
            executionContract = mapper.convertValue(contract, object : TypeReference<Map<String, Any?>>() {}),
            validation = mapOf("valid" to true, "validatorVersion" to "output-template-validator-1"),
        ))
        template.name = record.name; template.category = record.category; template.status = HarnessTemplateStatus.APPROVED
        return true
    }

    private fun validate(record: NotionTemplateRecord, definition: Map<String, Any?>, contract: TemplateExecutionContract, outputSchema: Map<String, Any?>, cases: List<Map<String, Any?>>) {
        require(definition.keys.all { it in setOf("templateSelection", "contentSchema", "executionContract") }) { "Automation graph fields are forbidden in output templates" }
        require(contract.rendererKey.isNotBlank() && contract.rendererVersion.isNotBlank() && contract.promptVersion.isNotBlank()) { "Renderer and prompt versions are required" }
        require((contract.modelPolicy["maxCallsPerRun"] as? Number)?.toInt() in 0..5) { "modelPolicy.maxCallsPerRun must be between 0 and 5" }
        require(outputSchema["type"] == "object" && outputSchema["properties"] is Map<*, *>) { "Output schema must be an object schema" }
        require(cases.isNotEmpty()) { "Acceptance cases are required" }
        val raw = listOf(record.templateDefinitionJson, record.outputSchemaJson, record.acceptanceCasesJson).joinToString("\n")
        require(!Regex("(?i)(xox[baprs]-[A-Za-z0-9-]{8,}|sk-[A-Za-z0-9_-]{8,}|access[_-]?token\\s*[:=])").containsMatchIn(raw)) { "Secrets are forbidden" }
        require(!Regex("(?i)connection[_-]?id").containsMatchIn(raw)) { "Customer connection IDs are forbidden in templates" }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: Any): String = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value)
    private fun sanitize(value: String) = value.replace(Regex("(?i)(token|secret|password)\\s*[:=]\\s*\\S+"), "$1=***").take(300)
}
