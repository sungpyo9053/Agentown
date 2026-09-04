package com.agentvillage.contentops

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@AutoConfigureMockMvc
class ContentDraftIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `managed content falls back safely stays owner scoped and requires edited approval`() {
        val owner = principal("content-owner")
        val stranger = principal("content-stranger")
        val idempotency = "content-${UUID.randomUUID()}"
        val generated = generate(owner, idempotency)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generationSource").value("SAFE_TEMPLATE"))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.bodyMarkdown").value(containsString("[확인 필요")))
            .andReturn().response.contentAsString
        val draftId = mapper.readTree(generated)["id"].asText()

        generate(owner, idempotency)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(draftId))
        mvc.perform(get("/api/content-operations/drafts/$draftId").with(user(stranger)))
            .andExpect(status().isNotFound)

        approve(owner, draftId)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("CONTENT_TEMPLATE_INCOMPLETE"))

        val body = buildString {
            appendLine("## 시공 전 확인한 문제")
            appendLine()
            appendLine("수납 공간이 부족하고 조리대 동선이 겹쳤다는 현장 메모를 바탕으로 공개 가능한 범위만 정리했습니다. ".repeat(6))
            appendLine()
            appendLine("## 선택한 방법과 이유")
            appendLine()
            appendLine("기존 배관 위치와 고객이 제공한 자재표를 대조해 변경 범위를 설명합니다. 확인되지 않은 가격과 기간은 넣지 않았습니다. ".repeat(6))
            appendLine()
            appendLine("## 사진을 보는 순서")
            appendLine()
            appendLine("시공 전 사진, 철거 후 배관 사진, 완공 사진 순으로 보면 변화가 분명합니다. 사진 공개 권한은 승인 과정에서 다시 확인합니다. ".repeat(4))
        }
        mvc.perform(
            patch("/api/content-operations/drafts/$draftId").with(user(owner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf(
                    "title" to "수원 32평 아파트 주방 리모델링 현장 기록",
                    "bodyMarkdown" to body,
                    "seoTitle" to "수원 32평 주방 리모델링",
                    "metaDescription" to "수납과 동선을 개선한 확인 가능한 현장 기록입니다.",
                    "targetKeywords" to listOf("수원 주방 리모델링", "32평 인테리어"),
                ))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.qualityScore").value(org.hamcrest.Matchers.greaterThanOrEqualTo(70)))

        approve(owner, draftId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedAt").isNotEmpty)
    }

    @Test
    fun `personal AI path requires an owned active connection`() {
        val owner = principal("content-byok")
        mvc.perform(
            post("/api/content-operations/drafts/generate").with(user(owner)).with(csrf())
                .header("Idempotency-Key", "content-${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest().replace("\"usePersonalAi\":false", "\"usePersonalAi\":true")),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("CONTENT_AI_CONNECTION_REQUIRED"))
    }

    private fun generate(principal: AuthenticatedUser, idempotency: String) = mvc.perform(
        post("/api/content-operations/drafts/generate").with(user(principal)).with(csrf())
            .header("Idempotency-Key", idempotency)
            .contentType(MediaType.APPLICATION_JSON)
            .content(validRequest()),
    )

    private fun approve(principal: AuthenticatedUser, draftId: String) = mvc.perform(
        post("/api/content-operations/drafts/$draftId/approve").with(user(principal)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"evidenceConfirmed":true,"photoRightsConfirmed":true}"""),
    )

    private fun validRequest() = """{
      "brandName":"공간연구소","topic":"수원 32평 아파트 주방 리모델링",
      "audience":"수원에서 구축 아파트 리모델링을 준비하는 가족","channel":"NAVER",
      "sourceNotes":"수납 공간이 부족하고 조리대 동선이 겹쳤다. 기존 배관 위치를 유지하면서 키큰장을 추가했고 고객이 제공한 자재표 안에서만 설명한다. 현장 확인 내용 외 가격과 기간은 공개하지 않는다.",
      "evidenceNotes":"고객 제공 자재표와 현장 실측 메모","photoReferenceUrl":"https://drive.google.com/example",
      "photoNotes":"1. 시공 전 주방 2. 철거 후 배관 3. 완공 사진","styleNotes":"전문용어를 풀어서 설명하고 과장하지 않는다.",
      "provider":"OPENAI","model":"gpt-5.6-luna","usePersonalAi":false
    }"""

    private fun principal(prefix: String): AuthenticatedUser {
        val suffix = UUID.randomUUID().toString().take(8)
        val identity = identities.register(RegisterUserCommand("$prefix-$suffix@example.com", "password123", "${prefix.replace("-", "_")}_$suffix".take(30), "콘텐츠 검증"))
        return AuthenticatedUser(identity.id, identity.email, "unused", true)
    }
}
