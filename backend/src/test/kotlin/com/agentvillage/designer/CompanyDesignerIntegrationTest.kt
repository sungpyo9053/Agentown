package com.agentvillage.designer

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import java.nio.charset.StandardCharsets

@AutoConfigureMockMvc
@TestPropertySource(properties = ["execution.stub-enabled=false", "designer.template-enabled=true"])
class CompanyDesignerIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `questions create validate and apply a portable company harness without enabling execution stub`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val identity = identities.register(RegisterUserCommand("designer-$suffix@example.com", "password123", "designer_$suffix", "설계 검증"))
        val principal = AuthenticatedUser(identity.id, identity.email, "unused", true)
        fun postJson(path: String, body: String) = mvc.perform(
            post(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body),
        )

        val designedBody = postJson("/api/designer/companies/design", """{
          "companyName":"고객지원 AI 회사",
          "goal":"고객 문의를 분류하고 정책에 맞는 답변을 작성·검수한다.",
          "primaryInput":"고객 문의, 주문 상태, 환불 정책",
          "desiredOutput":"전송 가능한 고객 답변과 처리 분류",
          "requiredEvidence":"실제 주문 상태와 최신 정책",
          "prohibitions":"개인정보와 확인하지 않은 환불 가능성을 출력하지 않는다.",
          "approvalPolicy":"외부 전송 전에 사람이 승인한다.",
          "provider":"OPENAI","model":"gpt-4o-mini","stubMode":true
        }""").andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.draft.designSource").value("PLATFORM_TEMPLATE"))
            .andExpect(jsonPath("$.draft.agents.length()").value(3))
            .andExpect(jsonPath("$.draft.steps.length()").value(3))
            .andReturn().response.contentAsString

        val draft = mapper.readTree(designedBody)["draft"]
        val applyBody = mapper.writeValueAsString(mapOf("draft" to draft, "stubMode" to true))
        val applied = postJson("/api/designer/companies/apply", applyBody)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.agentIds.length()").value(3))
            .andReturn().response.contentAsString
        val harnessId = mapper.readTree(applied)["harnessId"].asText()
        mvc.perform(get("/api/harnesses/$harnessId").with(user(principal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.steps.length()").value(3))
            .andExpect(jsonPath("$.steps[2].requiresApproval").value(true))
        val agents = mvc.perform(get("/api/agents").with(user(principal))).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(mapper.readTree(agents).size()).isEqualTo(3)
        mapper.readTree(applied)["agentIds"].forEach { id ->
            mvc.perform(get("/api/agents/${id.asText()}/definition").with(user(principal)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.agentMarkdown").isNotEmpty)
                .andExpect(jsonPath("$.guideMarkdown").value(org.hamcrest.Matchers.containsString("## 승인 기준")))
        }
    }

    @Test
    fun `real designer requires an active owned credential before provider call`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val identity = identities.register(RegisterUserCommand("designer-real-$suffix@example.com", "password123", "real_$suffix", "실제 설계 검증"))
        val principal = AuthenticatedUser(identity.id, identity.email, "unused", true)
        mvc.perform(
            post("/api/designer/companies/design").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{
                  "companyName":"테스트 회사","goal":"문서를 작성하고 검수한다.","primaryInput":"주제",
                  "desiredOutput":"검수된 문서","provider":"ANTHROPIC","model":"claude-sonnet-4-0","stubMode":false
                }"""),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("LLM_CREDENTIAL_NOT_FOUND"))
    }

    @Test
    fun `validator blocks user code and shell work before persistence`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val identity = identities.register(RegisterUserCommand("designer-safe-$suffix@example.com", "password123", "safe_$suffix", "안전 검증"))
        val principal = AuthenticatedUser(identity.id, identity.email, "unused", true)
        val unsafeDraft = """{
          "companyName":"위험 회사","goal":"위험 작업","agents":[{
            "key":"runner","name":"실행자","role":"실행자","responsibility":"사용자 코드 실행",
            "taskDescription":"npm install 후 shell command 실행","desiredOutput":"결과","requiredEvidence":"로그",
            "guide":"정확히 실행","prohibitions":"없음","rewriteCriteria":"실패","approvalCriteria":"완료",
            "characterKey":"developer","provider":"OPENAI","recommendedModel":"gpt-4o-mini"
          }],"steps":[{"key":"step-1","agentKey":"runner","sequence":1,"maxRetries":1}],
          "approvalAfterLast":true,"designSource":"STUB"
        }"""
        mvc.perform(
            post("/api/designer/companies/validate").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"draft":$unsafeDraft,"stubMode":true}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errors").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("사용자 코드"))))
    }

    @Test
    fun `single responsibility design uses one agent and reuses it on the next design`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val identity = identities.register(RegisterUserCommand("minimal-$suffix@example.com", "password123", "minimal_$suffix", "최소 설계 검증"))
        val principal = AuthenticatedUser(identity.id, identity.email, "unused", true)
        fun design(companyName: String) = mvc.perform(
            post("/api/designer/companies/design").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{
                  "companyName":"$companyName","goal":"입력 문서를 짧게 요약한다.",
                  "primaryInput":"긴 문서","desiredOutput":"핵심 요약","approvalPolicy":"",
                  "provider":"OPENAI","model":"gpt-4o-mini","stubMode":true
                }"""),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        fun apply(designBody: String) = mvc.perform(
            post("/api/designer/companies/apply").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("draft" to mapper.readTree(designBody)["draft"], "stubMode" to true))),
        )

        val first = design("첫 요약 회사")
        assertThat(mapper.readTree(first)["draft"]["agents"].size()).isEqualTo(1)
        assertThat(mapper.readTree(first)["draft"]["approvalAfterLast"].asBoolean()).isFalse()
        val firstApplied = apply(first).andExpect(status().isOk)
            .andExpect(jsonPath("$.createdAgentIds.length()").value(1))
            .andExpect(jsonPath("$.reusedAgentIds.length()").value(0))
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val firstAgentId = mapper.readTree(firstApplied)["agentIds"][0].asText()

        val second = design("두 번째 요약 회사")
        val secondDraft = mapper.readTree(second)["draft"]
        assertThat(secondDraft["outcome"].asText()).isEqualTo("MINIMAL_CHANGE")
        assertThat(secondDraft["agents"][0]["existingAgentId"].asText()).isEqualTo(firstAgentId)
        assertThat(secondDraft["changes"].map { it["type"].asText() }).contains("REUSE_AGENT", "REWIRE_STEPS")
        apply(second).andExpect(status().isOk)
            .andExpect(jsonPath("$.createdAgentIds.length()").value(0))
            .andExpect(jsonPath("$.reusedAgentIds[0]").value(firstAgentId))

        mvc.perform(get("/api/agents").with(user(principal))).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }
}
