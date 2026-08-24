package com.agentvillage.builder

import com.agentvillage.builder.application.MetaAgentModel
import com.agentvillage.builder.application.MetaAgentAuditService
import com.agentvillage.builder.application.BuilderJobProgressService
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.builder.application.StructuredMetaAgentPipeline
import com.agentvillage.builder.application.DeterministicMockMetaAgentModel
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class MetaAgentPipelineSafetyTest {
    @Test
    fun `clarification result does not require premature agent definitions`() {
        val mapper = jacksonObjectMapper()
        val deterministic = DeterministicMockMetaAgentModel(mapper)
        val model = mock<MetaAgentModel>()
        val runs = mock<MetaAgentRunRepository>()
        whenever(model.executorName).thenReturn("clarification-test")
        whenever(model.modelName).thenReturn("mock")
        whenever(model.generate(any(), any(), any())).thenAnswer { invocation ->
            val raw = deterministic.generate(invocation.arguments[0] as PipelineContext, invocation.arguments[1] as String, invocation.arguments[2] as Map<String, Any?>)
            mapper.readTree(raw).also { root ->
                (root as com.fasterxml.jackson.databind.node.ObjectNode).putArray("agentDefinitions")
                root.putArray("guideDefinitions")
            }.toString()
        }
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val result = pipeline.generateDesign(context, "글을 자동으로 쓰고싶어요")

        assertThat(result.clarificationQuestions).hasSize(4)
        assertThat(result.agentDefinitions).isEmpty()
    }

    @Test
    fun `detailed result preserves model designed agents instead of forcing FAQ roles`() {
        val mapper = jacksonObjectMapper()
        val deterministic = DeterministicMockMetaAgentModel(mapper)
        val model = mock<MetaAgentModel>()
        val runs = mock<MetaAgentRunRepository>()
        whenever(model.executorName).thenReturn("normalization-test")
        whenever(model.modelName).thenReturn("mock")
        whenever(model.generate(any(), any(), any())).thenAnswer { invocation ->
            val raw = deterministic.generate(invocation.arguments[0] as PipelineContext, invocation.arguments[1] as String, invocation.arguments[2] as Map<String, Any?>)
            mapper.readTree(raw).also { root ->
                val agents = (root as com.fasterxml.jackson.databind.node.ObjectNode).putArray("agentDefinitions")
                agents.add(mapper.readTree(raw)["agentDefinitions"][0].deepCopy<com.fasterxml.jackson.databind.JsonNode>().also { node ->
                    (node as com.fasterxml.jackson.databind.node.ObjectNode).put("key", "classifier").put("name", "문의 분류 에이전트").put("role", "문의 의도를 분류한다")
                })
            }.toString()
        }
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val result = pipeline.generateDesign(context, "Slack 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 Slack 스레드로 전송한다")

        assertThat(result.clarificationQuestions).isEmpty()
        assertThat(result.agentDefinitions.map { it.key }).containsExactly("classifier")
        assertThat(result.agentDefinitions.single().role).isEqualTo("문의 의도를 분류한다")
    }

    @Test
    fun `detailed writing result is normalized to the four employee standard team`() {
        val mapper = jacksonObjectMapper()
        val deterministic = DeterministicMockMetaAgentModel(mapper)
        val model = mock<MetaAgentModel>()
        val runs = mock<MetaAgentRunRepository>()
        whenever(model.executorName).thenReturn("real-output-shape-test")
        whenever(model.modelName).thenReturn("mock")
        whenever(model.generate(any(), any(), any())).thenAnswer { invocation ->
            val raw = deterministic.generate(invocation.arguments[0] as PipelineContext, invocation.arguments[1] as String, invocation.arguments[2] as Map<String, Any?>)
            mapper.readTree(raw).also { root ->
                val agents = (root as com.fasterxml.jackson.databind.node.ObjectNode).putArray("agentDefinitions")
                agents.add(mapper.readTree(raw)["agentDefinitions"][2])
                val plan = root["proposal"]["graphPlan"] as com.fasterxml.jackson.databind.node.ObjectNode
                val nodes = plan.putArray("nodes")
                nodes.add(mapper.readTree(raw)["proposal"]["graphPlan"]["nodes"][0])
                nodes.add(mapper.readTree(raw)["proposal"]["graphPlan"]["nodes"][4])
                nodes.add(mapper.readTree(raw)["proposal"]["graphPlan"]["nodes"][5])
                val edges = plan.putArray("edges")
                edges.addObject().put("id", "model-edge-1").put("source", "manual").put("target", "fact-edit").put("condition", "success")
                edges.addObject().put("id", "model-edge-2").put("source", "fact-edit").put("target", "approval").put("condition", "success")
            }.toString()
        }
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val result = pipeline.generateDesign(
            context,
            "글쓰기 자동화를 수동으로 시작하고 사용자가 제공한 원문으로 초안을 작성해 담당자 승인 후 화면에 표시한다.",
        )

        assertThat(result.agentDefinitions.map { it.key })
            .containsExactly("source-analyst", "content-planner", "draft-writer", "fact-editor")
        assertThat(result.proposal.graphPlan!!.nodes.filter { it.nodeType == "ai.generate" }.map { it.config["agentKey"] })
            .containsExactly("source-analyst", "content-planner", "draft-writer", "fact-editor")
        assertThat(result.proposal.graphPlan!!.nodes.last().nodeType).isEqualTo("human.approval")
    }

    @Test
    fun `invalid model json is rejected before becoming a domain object`() {
        val model = mock<MetaAgentModel>()
        val runs = mock<MetaAgentRunRepository>()
        whenever(model.executorName).thenReturn("invalid-test")
        whenever(model.modelName).thenReturn("mock")
        whenever(model.generate(any(), any(), any())).thenReturn("this is not json")
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, jacksonObjectMapper(), MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        assertThatThrownBy { pipeline.generateDesign(context, "Slack 문의를 Notion FAQ로 처리") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("승인된 스키마")
    }

    @Test
    fun `scheduled Naver news request is rejected instead of becoming Slack FAQ mock`() {
        val mapper = jacksonObjectMapper()
        val deterministic = DeterministicMockMetaAgentModel(mapper)
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(deterministic, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        assertThatThrownBy {
            pipeline.generateDesign(
                context,
                "내 슬랙으로 매일 8시에 주식 경제 보고서를 보내줘. 네이버 경제뉴스를 수집하고 담당자 승인 후 로컬 저장해줘.",
            )
        }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("정기 예약 실행")
            .hasMessageContaining("외부 뉴스 수집")
            .hasMessageContaining("로컬 파일 저장")
            .hasMessageContaining("요청을 거절하거나 다른 자동화로 바꾸지 않았으며")
    }
}
