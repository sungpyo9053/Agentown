package com.agentvillage.builder

import com.agentvillage.builder.application.MetaAgentModel
import com.agentvillage.builder.application.MetaAgentAuditService
import com.agentvillage.builder.application.BuilderJobProgressService
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.builder.application.StructuredMetaAgentPipeline
import com.agentvillage.builder.application.DeterministicMockMetaAgentModel
import com.agentvillage.builder.domain.*
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
    fun `generated list input defaults normalize to runtime map`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val plan = WorkflowGraphPlan(
            entryNodeId = "agent",
            nodes = listOf(WorkflowNodePlan(
                "agent", "ai.generate", "Agent",
                mapOf("agentKey" to "worker", "instruction" to "work", "inputDefaults" to listOf(
                    mapOf("field" to "region", "value" to "east"),
                    mapOf("field" to "attempts", "value" to 2),
                )),
            )),
            edges = emptyList(),
        )

        val normalized = pipeline.normalizeGeneratedInputDefaults(plan)

        assertThat(normalized.nodes.single().config["inputDefaults"])
            .isEqualTo(mapOf("region" to "east", "attempts" to 2))
    }

    @Test
    fun `generated field metadata is canonicalized before semantic validation`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )

        val fields = pipeline.canonicalFields(listOf(
            FieldDefinition("title", "string", true, "title", minItems = 0, maxItems = 1, itemType = "string", itemSchema = emptyList()),
            FieldDefinition("items", "array", true, "items", itemType = "object", itemSchema = emptyList()),
        ))

        assertThat(fields[0]).isEqualTo(FieldDefinition("title", "string", true, "title"))
        assertThat(fields[1].itemSchema).isNull()
    }

    @Test
    fun `final output schema follows terminal graph agent instead of agent list order`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        fun agent(key: String, output: String) = AgentDefinition(
            key, key, key, emptyList(), listOf(FieldDefinition(output, "string", true, output)),
            listOf("work"), listOf("invent"), listOf("input"),
        )
        val reporter = agent("reporter", "finalReport")
        val worker = agent("worker", "intermediate")
        val bundle = MetaAgentDesignBundle(
            requirement = AutomationRequirement("work", "manual", emptyList(), listOf("report"), listOf("work"), emptyList(), emptyList(), false),
            clarificationQuestions = emptyList(),
            proposal = AutomationProposal(
                "work", "work", listOf("work"), emptyList(), emptyList(), "stop",
                graphPlan = WorkflowGraphPlan(
                    "start",
                    listOf(
                        WorkflowNodePlan("start", "manual.trigger", "Start"),
                        WorkflowNodePlan("work", "ai.generate", "Work", mapOf("agentKey" to "worker")),
                        WorkflowNodePlan("report", "ai.generate", "Report", mapOf("agentKey" to "reporter")),
                    ),
                    listOf(WorkflowEdgePlan("a", "start", "work"), WorkflowEdgePlan("b", "work", "report")),
                ),
            ),
            agentDefinitions = listOf(reporter, worker),
            guideDefinitions = emptyList(),
        )

        assertThat(pipeline.terminalAgentOutputSchema(bundle).map { it.name }).containsExactly("finalReport")

        val withTerminalTool = bundle.copy(proposal = bundle.proposal.copy(graphPlan = WorkflowGraphPlan(
            "start",
            bundle.proposal.graphPlan!!.nodes + WorkflowNodePlan("render", "template.render", "Render", mapOf("rendererKey" to "plain-text.v1")),
            bundle.proposal.graphPlan!!.edges + WorkflowEdgePlan("c", "report", "render"),
        )))
        assertThat(pipeline.terminalAgentOutputSchema(withTerminalTool)).isEmpty()
    }

    @Test
    fun `agent review wording does not invent a runtime human approval`() {
        val mapper = jacksonObjectMapper()
        val model = DeterministicMockMetaAgentModel(mapper)
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val result = pipeline.generateDesign(
            context, "세 입력을 각각 검토 후 통합해줘", StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )

        assertThat(result.proposal.graphPlan!!.nodes).noneMatch { it.nodeType == "human.approval" }
        assertThat(result.requirement.humanApprovalRequired).isFalse()
        assertThat(result.proposal.approvalPoints).isEmpty()
    }

    @Test
    fun `zero AI graph explains deterministic execution without an AI call`() {
        val mapper = jacksonObjectMapper()
        val model = DeterministicMockMetaAgentModel(mapper)
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val result = pipeline.generateDesign(context, "CSV 두 파일을 비교해서 변경된 행을 찾아줘")

        assertThat(result.proposal.graphPlan!!.nodes).noneMatch { it.nodeType in setOf("ai.generate", "ai.classify") }
        assertThat(result.proposal.economics?.estimatedAiCallsPerRun).isZero()
        assertThat(result.proposal.economics!!.separationRationale.single())
            .contains("AI를 호출하지 않습니다", "CSV 두 파일 입력", "CSV 행 결정적 비교", "일반 코드와 규칙")
    }

    @Test
    fun `bounded AI graph names AI steps and distinguishes non AI work`() {
        val mapper = jacksonObjectMapper()
        val model = DeterministicMockMetaAgentModel(mapper)
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val result = pipeline.generateDesign(
            context,
            "매일 오전 8시에 네이버 경제·주식 뉴스를 수집해 시장 영향 보고서를 만들고 담당자 승인 후 Slack #market-report 채널로 전송해줘.",
        )

        val aiNodes = result.proposal.graphPlan!!.nodes.filter { it.nodeType in setOf("ai.generate", "ai.classify") }
        assertThat(aiNodes.map { it.label }).containsExactly("시장 영향 보고서 작성")
        assertThat(result.proposal.economics?.estimatedAiCallsPerRun).isEqualTo(aiNodes.size)
        assertThat(result.proposal.economics!!.separationRationale.single())
            .contains("‘시장 영향 보고서 작성’", "단계에만", "실행당 1회", "일반 코드와 규칙", "사람이 확인하고 승인", "연결 도구")
    }

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
    fun `detailed writing uses one structured writer unless independent review is requested`() {
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

        assertThat(result.agentDefinitions.map { it.key }).containsExactly("content-writer")
        assertThat(result.proposal.graphPlan!!.nodes.filter { it.nodeType == "ai.generate" }.map { it.config["agentKey"] })
            .containsExactly("content-writer")
        assertThat(result.proposal.graphPlan!!.nodes.last().nodeType).isEqualTo("human.approval")
        assertThat(result.proposal.economics?.estimatedAiCallsPerRun).isEqualTo(1)
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
    fun `scheduled news report selects built in template and one report agent`() {
        val mapper = jacksonObjectMapper()
        val deterministic = DeterministicMockMetaAgentModel(mapper)
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(deterministic, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val result = pipeline.generateDesign(
            context,
            "매일 오전 8시에 네이버 경제·주식 뉴스를 수집해 시장 영향 보고서를 만들고 담당자 승인 후 Slack #market-report 채널로 전송해줘.",
        )

        assertThat(result.clarificationQuestions).isEmpty()
        assertThat(result.agentDefinitions.map { it.key }).containsExactly("market-news-reporter")
        assertThat(result.proposal.templateSelection?.templateKey).isEqualTo("daily-market-news-report")
        assertThat(result.proposal.economics?.estimatedAiCallsPerRun).isEqualTo(1)
        assertThat(result.proposal.graphPlan!!.nodes.map { it.nodeType }).containsExactly(
            "schedule.trigger", "news.search.mock", "data.deduplicate", "ai.generate", "template.render", "human.approval", "slack.send.mock", "workflow.end",
        )
        assertThat(result.proposal.resourcePlan?.simulationReady).isTrue()
        assertThat(result.proposal.resourcePlan?.productionReady).isFalse()
        assertThat(result.proposal.resourcePlan?.bindings?.map { it.resourceKey })
            .contains("connector.news.mock", "connector.slack.mock", "platform.structured-ai")
    }
}
