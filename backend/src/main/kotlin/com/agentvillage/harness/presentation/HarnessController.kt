package com.agentvillage.harness.presentation

import com.agentvillage.common.domain.Visibility
import com.agentvillage.harness.application.HarnessService
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
                              val visibility: Visibility = Visibility.PRIVATE)
data class ConnectHarnessRequest(
    val agentIds: List<UUID>,
    val approvalAfterLast: Boolean = false,
    val approvalBeforeLast: Boolean = false,
)

@RestController @RequestMapping("/api/harnesses")
class HarnessController(private val service: HarnessService, private val mapper: ObjectMapper) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal p: AuthenticatedUser, @Valid @RequestBody r: SaveHarnessRequest) = service.create(p.userId, r.name, r.description)
    @GetMapping fun list(@AuthenticationPrincipal p: AuthenticatedUser) = service.list(p.userId)
    @GetMapping("/{id}") fun get(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.requireOwnedView(id, p.userId)
    @PatchMapping("/{id}") fun update(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID, @Valid @RequestBody r: SaveHarnessRequest) = service.update(id, p.userId, r.name, r.description, r.visibility)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.delete(id, p.userId)
    @PostMapping("/{id}/connect") fun connect(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID, @RequestBody r: ConnectHarnessRequest) =
        service.connect(id, p.userId, r.agentIds, r.approvalAfterLast, r.approvalBeforeLast)
    @PostMapping("/{id}/validate") fun validate(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.validate(id, p.userId)
    @PostMapping("/{id}/publish") fun publish(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.publish(id, p.userId)
    @PostMapping("/{id}/clone") fun clone(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.clone(id, p.userId)

    @GetMapping("/{id}/download", produces = ["application/zip"])
    fun download(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val version = service.latestPublished(id)
        val bytes = ByteArrayOutputStream().use { out -> ZipOutputStream(out).use { zip ->
            fun entry(path: String, content: String) { zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry() }
            val root = version.snapshotJson["name"].toString().replace(Regex("[^a-zA-Z0-9가-힣_-]"), "-")
            entry("$root/README.md", "# ${version.snapshotJson["name"]}\n\nAgentown 선언형 하네스 패키지입니다. 자격증명과 실행 결과는 포함되지 않습니다.\n")
            entry("$root/harness.md", "# ${version.snapshotJson["name"]}\n\n${version.snapshotJson["description"]}\n")
            entry("$root/harness.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(version.snapshotJson))
            entry("$root/metadata.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("version" to version.version, "credentialIncluded" to false)))
        }; out.toByteArray() }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"harness-${version.id}.zip\"").body(bytes)
    }
}
