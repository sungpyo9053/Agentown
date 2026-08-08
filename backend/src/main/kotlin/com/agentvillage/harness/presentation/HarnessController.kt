package com.agentvillage.harness.presentation

import com.agentvillage.common.domain.Visibility
import com.agentvillage.harness.application.HarnessService
import com.agentvillage.harness.domain.HarnessResultFormat
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SaveHarnessRequest(@field:NotBlank @field:Size(max = 100) val name: String,
                              @field:Size(max = 1000) val description: String? = null,
                              val visibility: Visibility = Visibility.PRIVATE,
                              val resultFormat: HarnessResultFormat = HarnessResultFormat.AUTO)
data class ConfigureHarnessResultRequest(val resultFormat: HarnessResultFormat, val resultAgentId: UUID? = null)
data class ConnectHarnessRequest(
    val agentIds: List<UUID>,
    val approvalAfterLast: Boolean = false,
    val approvalBeforeLast: Boolean = false,
)

@RestController @RequestMapping("/api/harnesses")
class HarnessController(private val service: HarnessService, private val mapper: ObjectMapper) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal p: AuthenticatedUser, @Valid @RequestBody r: SaveHarnessRequest) = service.create(p.userId, r.name, r.description, r.resultFormat)
    @GetMapping fun list(@AuthenticationPrincipal p: AuthenticatedUser) = service.list(p.userId)
    @GetMapping("/{id}") fun get(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.requireOwnedView(id, p.userId)
    @PatchMapping("/{id}") fun update(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID, @Valid @RequestBody r: SaveHarnessRequest) = service.update(id, p.userId, r.name, r.description, r.visibility, r.resultFormat)
    @PatchMapping("/{id}/result") fun configureResult(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID, @RequestBody r: ConfigureHarnessResultRequest) =
        service.configureResult(id, p.userId, r.resultFormat, r.resultAgentId)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.delete(id, p.userId)
    @PostMapping("/{id}/connect") fun connect(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID, @RequestBody r: ConnectHarnessRequest) =
        service.connect(id, p.userId, r.agentIds, r.approvalAfterLast, r.approvalBeforeLast)
    @PostMapping("/{id}/validate") fun validate(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.validate(id, p.userId)
    @PostMapping("/{id}/publish") fun publish(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.publish(id, p.userId)
    @PostMapping("/{id}/clone") fun clone(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.clone(id, p.userId)

    @GetMapping("/{id}/download", produces = ["application/zip"])
    fun download(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val version = service.downloadableVersion(id, p.userId)
        val bytes = ByteArrayOutputStream().use { out -> ZipOutputStream(out).use { zip ->
            fun entry(path: String, content: String) { zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry() }
            val root = "${p.username}-ai-company".safeFileName()
            val name = version.snapshotJson["name"].toString()
            @Suppress("UNCHECKED_CAST") val agents = version.snapshotJson["agents"] as? List<Map<String, Any>> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val steps = version.snapshotJson["steps"] as? List<Map<String, Any>> ?: emptyList()
            val orchestration = orchestrationMarkdown(name, agents, steps)
            entry("$root/README.md", """# $root

Agentown AI 회사 표준 선언형 패키지입니다. `AGENTS.md`와 `CLAUDE.md`는 같은 오케스트레이션 규칙을 제공하며, 자격증명과 실행 결과는 포함되지 않습니다.

## 로컬 실행

- Codex CLI: 이 폴더에서 `codex` 또는 `codex exec \"AGENTS.md의 하네스를 실행해줘\"`
- Claude Code: 이 폴더에서 `claude` 실행 후 `CLAUDE.md의 하네스를 실행해줘`

CLI 설치와 각 공급자 인증은 사용자 로컬 환경에서 별도로 완료해야 합니다. Agentown은 사용자 코드, Shell 또는 임의 패키지를 패키지에 포함하지 않습니다.
""".trimIndent())
            entry("$root/AGENTS.md", orchestration)
            entry("$root/CLAUDE.md", orchestration)
            entry("$root/harness.md", "# $name\n\n${version.snapshotJson["description"]}\n\n## 실행 원칙\n\n- agents/의 역할 정의와 guides/의 정책을 적용합니다.\n- 사용자 코드, Shell, 임의 패키지를 실행하지 않습니다.\n- API 키와 개인 결과물을 패키지에 포함하지 않습니다.\n")
            entry("$root/harness.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(version.snapshotJson))
            agents.forEachIndexed { index, agent ->
                val slug = (agent["name"]?.toString()?.safeFileName()?.lowercase()?.takeIf(String::isNotBlank) ?: "agent-${index + 1}")
                entry("$root/agents/$slug.md", agentMarkdown(agent))
                entry("$root/guides/$slug-guide.md", guideMarkdown(agent))
                entry("$root/schemas/$slug-input.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(defaultSchema("${agent["name"]} 입력")))
                entry("$root/schemas/$slug-output.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(defaultSchema("${agent["name"]} 출력")))
            }
            entry("$root/metadata.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
                "format" to "agentown-ai-company-package", "formatVersion" to "1.0", "harnessVersion" to version.version,
                "credentialIncluded" to false, "executionResultIncluded" to false,
            )))
        }; out.toByteArray() }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"harness-${version.id}.zip\"").body(bytes)
    }

    private fun orchestrationMarkdown(name: String, agents: List<Map<String, Any>>, steps: List<Map<String, Any>>) = buildString {
        appendLine("# $name 오케스트레이션")
        appendLine()
        appendLine("이 파일은 Agentown이 생성한 선언형 오케스트레이션 SSOT입니다.")
        appendLine()
        appendLine("## 실행 순서")
        steps.forEach { appendLine("- ${it["sequence"]}. ${it["type"]} (${it["id"]})") }
        appendLine()
        appendLine("## 구성원")
        agents.forEach { appendLine("- ${it["name"]}: ${it["role"]} · ${it["provider"]}/${it["recommendedModel"]}") }
        appendLine()
        appendLine("## 안전 규칙")
        appendLine("- guides/ 정책을 모든 단계에 적용합니다.")
        appendLine("- 자격증명은 실행 사용자가 별도로 연결합니다.")
        appendLine("- 사용자 승인 단계 이전에는 외부 게시·전송을 수행하지 않습니다.")
        appendLine("- 사용자 코드와 Shell 명령을 실행하지 않습니다.")
    }

    private fun agentMarkdown(agent: Map<String, Any>) = """
        # ${agent["name"]}

        ## 역할
        ${agent["role"]}

        ## 책임과 작업 순서
        ${agent["script"]}

        ## 모델 권장값
        - Provider: ${agent["provider"]}
        - Model: ${agent["recommendedModel"]}
        - 최대 출력 토큰: ${agent["maxOutputTokens"]}
        - Timeout: ${agent["timeoutSeconds"]}초

        ## 완료 조건
        - output schema와 다음 단계 입력 계약을 충족합니다.

        ## 전달 규칙
        - 결과를 다음 오케스트레이션 단계에 전달합니다.
    """.trimIndent() + "\n"

    private fun guideMarkdown(agent: Map<String, Any>) = """
        # ${agent["name"]} Guide

        ${agent["guide"] ?: "별도 가이드가 없습니다. 사실성, 안전성, 출력 계약을 준수합니다."}

        ## 공통 금지사항
        - 확인되지 않은 내용을 사실처럼 단정하지 않습니다.
        - 자격증명과 개인정보를 출력하지 않습니다.
        - 승인 없이 위험한 외부 작업을 수행하지 않습니다.
    """.trimIndent() + "\n"

    private fun defaultSchema(title: String) = mapOf(
        "\$schema" to "https://json-schema.org/draft/2020-12/schema",
        "title" to title,
        "type" to "object",
        "additionalProperties" to true,
    )

    private fun String.safeFileName() = replace(Regex("[^a-zA-Z0-9가-힣_-]"), "-").trim('-').ifBlank { "agentown-village" }
}

@RestController
@RequestMapping("/api/public/users")
class PublicHarnessController(private val service: HarnessService, private val users: com.agentvillage.identity.application.UserDirectory) {
    @GetMapping("/{handle}/harnesses")
    fun list(@PathVariable handle: String) = users.findByHandle(handle)?.let { service.listPublic(it.id) } ?: emptyList<com.agentvillage.harness.domain.Harness>()
}
