package com.agentvillage.release

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.common.domain.UserRole
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(properties = ["release.agent-token=integration-release-token"])
class ReleaseControlIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `only release owner admin can list and other workspace is isolated`() {
        val owner = principal("admin@reviewdr.kr")
        val other = principal("admin@reviewdr.kr")
        val release = seed(owner.userId)
        seed(other.userId)
        mvc.perform(get("/api/admin/releases").with(user(owner))).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(release.toString()))
        mvc.perform(get("/api/admin/releases/$release").with(user(other))).andExpect(status().isNotFound)
        mvc.perform(get("/api/admin/releases").with(user(principal("another-admin@reviewdr.kr")))).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("RELEASE_ADMIN_REQUIRED"))
    }

    @Test fun `approval is SHA bound idempotent and scheduled time is stored as UTC instant`() {
        val owner = principal("admin@reviewdr.kr")
        val release = seed(owner.userId)
        mvc.perform(post("/api/admin/releases/$release/approve").with(user(owner)).with(csrf()).header("Idempotency-Key", "approve-once").contentType(MediaType.APPLICATION_JSON).content("""{"commitSha":"${SHA}","environment":"PRODUCTION","scheduledAt":"2026-08-27T15:00:00Z"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("SCHEDULED")).andExpect(jsonPath("$.approvalPreflightHash").value(PREFLIGHT))
        mvc.perform(post("/api/admin/releases/$release/approve").with(user(owner)).with(csrf()).header("Idempotency-Key", "approve-once").contentType(MediaType.APPLICATION_JSON).content("""{"commitSha":"${SHA}","environment":"PRODUCTION"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.scheduledAt").value("2026-08-27T15:00:00Z"))
        mvc.perform(post("/api/admin/releases/$release/approve").with(user(owner)).with(csrf()).header("Idempotency-Key", "different-key").contentType(MediaType.APPLICATION_JSON).content("""{"commitSha":"${SHA}","environment":"PRODUCTION"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("RELEASE_ALREADY_APPROVED"))
        assertThatCount("select count(*) from release_events where release_id=? and result='APPROVED'", release, 1)
    }

    @Test fun `SHA mismatch cancellation hold discard and operating revision mismatch are safe`() {
        val owner = principal("admin@reviewdr.kr")
        val release = seed(owner.userId)
        mvc.perform(post("/api/admin/releases/$release/approve").with(user(owner)).with(csrf()).header("Idempotency-Key", "bad-sha").contentType(MediaType.APPLICATION_JSON).content("""{"commitSha":"${"f".repeat(40)}","environment":"PRODUCTION"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("RELEASE_SHA_MISMATCH"))
        mvc.perform(post("/api/admin/releases/$release/hold").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"reason":"검토 필요"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("HELD"))
        jdbc.update("update releases set status='VERIFYING' where id=?", release)
        mvc.perform(post("/api/admin/releases/$release/verification").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"approvedSha":"$SHA","observedSha":"${"e".repeat(40)}","healthPassed":true,"readinessPassed":true,"apiSmokePassed":true,"journeyE2ePassed":true,"migrationPassed":true,"errorRateNormal":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("ROLLBACK_REQUIRED")).andExpect(jsonPath("$.actualDeployedSha").value("e".repeat(40)))
    }

    @Test fun `release agent publishes and reads the same control plane candidate`() {
        seedReleaseOwner()
        val payload = """{"releaseKey":"release-agent-sync","purpose":"Release 동기화","userSummary":"승인 후보를 같은 저장소에서 확인합니다.","currentSha":"${"d".repeat(40)}","candidateSha":"$SHA","includedTaskCount":1,"riskLevel":"MEDIUM","hasMigration":false,"stagingStatus":"PASSED","preflightHash":"$PREFLIGHT","detail":{"environmentContract":{"configured":true}}}"""
        mvc.perform(post("/api/internal/releases/candidates").header("X-Release-Agent-Token", "integration-release-token").contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk).andExpect(jsonPath("$.releaseKey").value("release-agent-sync")).andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED"))
        mvc.perform(get("/api/internal/releases/release-agent-sync").header("X-Release-Agent-Token", "integration-release-token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.candidateSha").value(SHA)).andExpect(jsonPath("$.preflightHash").value(PREFLIGHT))
        val corrected = payload.replace("Release 동기화", "운영 배포 동기화").replace("승인 후보를 같은 저장소에서 확인합니다.", "운영자가 검증된 변경 내용을 한국어로 확인합니다.")
        mvc.perform(post("/api/internal/releases/candidates").header("X-Release-Agent-Token", "integration-release-token").contentType(MediaType.APPLICATION_JSON).content(corrected))
            .andExpect(status().isOk).andExpect(jsonPath("$.purpose").value("운영 배포 동기화")).andExpect(jsonPath("$.userSummary").value("운영자가 검증된 변경 내용을 한국어로 확인합니다."))

        val changed = corrected.replace(SHA, "c".repeat(40)).replace(PREFLIGHT, "e".repeat(64))
        mvc.perform(post("/api/internal/releases/candidates").header("X-Release-Agent-Token", "integration-release-token").contentType(MediaType.APPLICATION_JSON).content(changed))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("APPROVAL_REQUIRED")).andExpect(jsonPath("$.candidateSha").value("c".repeat(40)))
    }

    @Test fun `release agent rejects English only user facing metadata`() {
        seedReleaseOwner()
        val payload = """{"releaseKey":"release-english","purpose":"Release control","userSummary":"All verification checks passed.","candidateSha":"$SHA","includedTaskCount":1,"riskLevel":"MEDIUM","hasMigration":false,"stagingStatus":"PASSED","preflightHash":"$PREFLIGHT","detail":{"environmentContract":{"configured":true}}}"""
        mvc.perform(post("/api/internal/releases/candidates").header("X-Release-Agent-Token", "integration-release-token").contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RELEASE_KOREAN_TEXT_REQUIRED"))
    }

    private fun principal(email: String) = AuthenticatedUser(UUID.randomUUID(), email, "", true, UserRole.ADMIN)
    private fun seed(ownerId: UUID): UUID {
        val workspace = UUID.randomUUID(); val release = UUID.randomUUID()
        jdbc.update("insert into users(id,email,password_hash,handle,status,role,created_at,updated_at) values (?,?,?,?,'ACTIVE','ADMIN',now(),now())", ownerId, "${ownerId}@test.invalid", "hash", "r${ownerId.toString().replace("-", "").take(20)}")
        jdbc.update("insert into builder_workspaces(id,owner_id,name,created_at,updated_at) values (?,?,?,now(),now())", workspace, ownerId, "release-test")
        jdbc.update("""insert into releases(id,workspace_id,release_key,purpose,user_summary,status,risk_level,current_sha,candidate_sha,included_task_count,has_migration,staging_status,preflight_hash,detail_json,created_at,updated_at) values (?,?,?,?,?,'APPROVAL_REQUIRED','MEDIUM',?,?,1,false,'PASSED',?,cast(? as jsonb),now(),now())""", release, workspace, "release-${release.toString().take(8)}", "배포 통제 검증", "관리자가 변경을 검토하고 승인할 수 있습니다.", "d".repeat(40), SHA, PREFLIGHT, """{"environmentContract":{"configured":true},"verificationCommands":[],"evidencePaths":["runs/test/verification-report.json"],"screenshotPaths":["/mock/release.png"]}""")
        return release
    }
    private fun seedReleaseOwner() {
        if (jdbc.queryForObject("select count(*) from users where email='admin@reviewdr.kr'", Long::class.java)!! > 0) return
        val ownerId = UUID.randomUUID(); val workspace = UUID.randomUUID()
        jdbc.update("insert into users(id,email,password_hash,handle,status,role,created_at,updated_at) values (?,'admin@reviewdr.kr',?,?,'ACTIVE','ADMIN',now(),now())", ownerId, "hash", "release_owner")
        jdbc.update("insert into profiles(user_id,display_name,visibility,created_at,updated_at) values (?,?,'PRIVATE',now(),now())", ownerId, "운영자")
        jdbc.update("insert into builder_workspaces(id,owner_id,name,created_at,updated_at) values (?,?,?,now(),now())", workspace, ownerId, "release-owner")
    }
    private fun assertThatCount(sql: String, id: UUID, expected: Long) { org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(sql, Long::class.java, id)).isEqualTo(expected) }
    companion object { const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; const val PREFLIGHT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" }
}
