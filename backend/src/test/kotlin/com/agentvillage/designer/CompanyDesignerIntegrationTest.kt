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
import java.util.UUID

@AutoConfigureMockMvc
class CompanyDesignerIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `questions create validate and apply a portable company harness with stub designer`() {
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
            .andExpect(jsonPath("$.draft.designSource").value("STUB"))
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
            .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("사용자 코드")))
    }
}
