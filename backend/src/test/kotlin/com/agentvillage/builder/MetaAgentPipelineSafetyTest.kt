package com.agentvillage.builder

import com.agentvillage.builder.application.MetaAgentModel
import com.agentvillage.builder.application.MetaAgentAuditService
import com.agentvillage.builder.application.BuilderJobProgressService
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.builder.application.StructuredMetaAgentPipeline
import com.agentvillage.builder.application.DeterministicMockMetaAgentModel
import com.agentvillage.builder.application.AgentDevelopmentApproach
import com.agentvillage.builder.application.AgentDevelopmentProblemDefinition
import com.agentvillage.builder.application.AgentDevelopmentProblemPolicy
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
    private fun pipeline(): StructuredMetaAgentPipeline {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        return StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
    }

    @Test
    fun `missing generated agents and an entry prefix are repaired from graph bindings`() {
        val input = FieldDefinition("idea", "string", true, "idea")
        val plan = WorkflowGraphPlan(
            "input",
            listOf(
                WorkflowNodePlan("trigger", "manual.trigger", "Trigger"),
                WorkflowNodePlan("input", "text.input", "Input"),
                WorkflowNodePlan("analysis", "ai.generate", "Analysis", mapOf("agentKey" to "analyst", "instruction" to "Analyze")),
                WorkflowNodePlan("writer", "ai.generate", "Writer", mapOf("agentKey" to "writer", "instruction" to "Write")),
                WorkflowNodePlan("end", "workflow.end", "End"),
            ),
            listOf(
                WorkflowEdgePlan("pre", "trigger", "input", bindings = listOf(WorkflowFieldBinding("idea", "idea"))),
                WorkflowEdgePlan("a", "input", "analysis", bindings = listOf(WorkflowFieldBinding("idea", "idea"))),
                WorkflowEdgePlan("b", "analysis", "writer", bindings = listOf(WorkflowFieldBinding("analysis", "analysis"))),
                WorkflowEdgePlan("c", "writer", "end", bindings = listOf(WorkflowFieldBinding("document", "document"))),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", listOf("idea"), listOf("document"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan, inputSchema = listOf(input)),
            emptyList(), listOf(GuideDefinition("input", "Input", "Input", listOf(GuideField("idea", "Idea", "text", true, help = "Idea")))),
        )

        val repaired = pipeline().repairGeneratedAgentContracts(bundle)

        assertThat(repaired.proposal.graphPlan!!.nodes.map { it.id }).doesNotContain("trigger")
        assertThat(repaired.agentDefinitions.map { it.key }).containsExactly("analyst", "writer")
        assertThat(repaired.agentDefinitions[0].inputSchema.map { it.name }).containsExactly("idea")
        assertThat(repaired.agentDefinitions[0].outputSchema.map { it.name }).containsExactly("analysis")
        assertThat(repaired.agentDefinitions[1].inputSchema.map { it.name }).containsExactly("analysis")
        assertThat(repaired.agentDefinitions[1].outputSchema.map { it.name }).containsExactly("document")
    }

    @Test
    fun `sixth generated role is folded into a synthesis agent without exceeding package cap`() {
        fun agent(key: String, role: String) = AgentDefinition(
            key, key, role, emptyList(), listOf(FieldDefinition("draft", "string", true, "draft")),
            listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val agents = listOf(
            agent("research", "research"), agent("requirements", "requirements"), agent("constraints", "constraints"),
            agent("aggregate", "analysis aggregate"), agent("review", "review"),
        )
        val plan = WorkflowGraphPlan(
            "aggregate-node",
            listOf(
                WorkflowNodePlan("aggregate-node", "ai.generate", "Aggregate", mapOf("agentKey" to "aggregate")),
                WorkflowNodePlan("review-node", "ai.generate", "Review", mapOf("agentKey" to "review")),
                WorkflowNodePlan("final-node", "ai.generate", "Final writer", mapOf("agentKey" to "missing-writer", "instruction" to "Write final")),
                WorkflowNodePlan("end", "workflow.end", "End"),
            ),
            listOf(
                WorkflowEdgePlan("a", "aggregate-node", "review-node", bindings = listOf(WorkflowFieldBinding("draft", "draft"))),
                WorkflowEdgePlan("b", "review-node", "final-node", bindings = listOf(WorkflowFieldBinding("draft", "reviewResult"))),
                WorkflowEdgePlan("c", "aggregate-node", "final-node", bindings = listOf(WorkflowFieldBinding("draft", "draft"))),
                WorkflowEdgePlan("d", "final-node", "end", bindings = listOf(WorkflowFieldBinding("document", "document"))),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", emptyList(), listOf("document"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan), agents,
            listOf(GuideDefinition("input", "Input", "Input", listOf(GuideField("idea", "Idea", "text", true, help = "Idea")))),
        )

        val repaired = pipeline().repairGeneratedAgentContracts(bundle)

        assertThat(repaired.agentDefinitions).hasSize(5)
        assertThat(repaired.proposal.graphPlan!!.nodes.single { it.id == "final-node" }.config["agentKey"]).isEqualTo("aggregate")
        assertThat(repaired.agentDefinitions.single { it.key == "aggregate" }.outputSchema.map { it.name }).contains("document")
    }

    @Test
    fun `external optional fields make their bound agent inputs optional`() {
        val optional = FieldDefinition("goal", "string", false, "optional goal")
        val worker = AgentDefinition(
            "worker", "Worker", "Work", listOf(FieldDefinition("task", "string", true, "task")),
            listOf(FieldDefinition("result", "string", true, "result")), listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val plan = WorkflowGraphPlan(
            "input",
            listOf(WorkflowNodePlan("input", "text.input", "Input"), WorkflowNodePlan("work", "ai.generate", "Work", mapOf("agentKey" to "worker"))),
            listOf(WorkflowEdgePlan("edge", "input", "work", bindings = listOf(WorkflowFieldBinding("goal", "task")))),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", listOf("goal"), listOf("result"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan, inputSchema = listOf(optional)),
            listOf(worker), emptyList(),
        )

        val normalized = pipeline().normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.agentDefinitions.single().inputSchema.single().required).isFalse()
    }

    @Test
    fun `branch item field bindings are normalized to their workflow collection`() {
        val row = listOf(
            FieldDefinition("branch", "string", true, "branch"),
            FieldDefinition("record", "string", true, "record"),
        )
        val records = FieldDefinition("salesRecords", "array", true, "records", itemType = "object", itemSchema = row)
        val worker = AgentDefinition(
            "worker", "Worker", "Analyze", row, listOf(FieldDefinition("result", "string", true, "result")),
            listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val plan = WorkflowGraphPlan(
            "input",
            listOf(
                WorkflowNodePlan("input", "text.input", "Input"),
                WorkflowNodePlan("route", "condition.branch", "Route", mapOf("expression" to "salesRecords.length > 0")),
                WorkflowNodePlan("work", "ai.generate", "Work", mapOf("agentKey" to "worker")),
            ),
            listOf(
                WorkflowEdgePlan("a", "input", "route", bindings = listOf(WorkflowFieldBinding("salesRecords", "salesRecords"))),
                WorkflowEdgePlan("b", "route", "work", "hasRecords=true", listOf(
                    WorkflowFieldBinding("branch", "branch"), WorkflowFieldBinding("record", "record"),
                )),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", listOf("salesRecords"), listOf("result"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan, inputSchema = listOf(records)),
            listOf(worker), emptyList(),
        )

        val normalized = pipeline().normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.proposal.graphPlan!!.edges.single { it.id == "b" }.bindings)
            .containsExactly(WorkflowFieldBinding("salesRecords", "salesRecords"))
        assertThat(normalized.agentDefinitions.single().inputSchema).containsExactly(records)
    }

    @Test
    fun `final output contract excludes intermediate fields not bound to workflow end`() {
        val review = FieldDefinition("reviewResult", "string", true, "review")
        val handoff = FieldDefinition("handoffTable", "string", true, "handoff")
        val worker = AgentDefinition(
            "worker", "Worker", "Work", emptyList(), listOf(review, handoff),
            listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val plan = WorkflowGraphPlan(
            "work",
            listOf(
                WorkflowNodePlan("work", "ai.generate", "Work", mapOf("agentKey" to "worker")),
                WorkflowNodePlan("end", "workflow.end", "End"),
            ),
            listOf(WorkflowEdgePlan("done", "work", "end", bindings = listOf(WorkflowFieldBinding("handoffTable", "handoffTable")))),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", emptyList(), listOf("handoffTable"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan, outputSchema = listOf(handoff, review)),
            listOf(worker), emptyList(),
        )

        val normalized = pipeline().normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.proposal.outputSchema).containsExactly(handoff)
    }

    @Test
    fun `pass through normalizer keeps its incoming field as the outgoing source`() {
        val reviewed = FieldDefinition("reviewedResults", "array", true, "reviewed", itemType = "string")
        val source = AgentDefinition(
            "source", "Source", "Review", emptyList(), listOf(reviewed),
            listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val aggregate = AgentDefinition(
            "aggregate", "Aggregate", "Aggregate", listOf(reviewed.copy(name = "normalizedResults")),
            listOf(FieldDefinition("result", "string", true, "result")), listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val plan = WorkflowGraphPlan(
            "source",
            listOf(
                WorkflowNodePlan("source", "ai.generate", "Source", mapOf("agentKey" to "source")),
                WorkflowNodePlan("normalize", "data.normalize", "Normalize"),
                WorkflowNodePlan("aggregate", "ai.generate", "Aggregate", mapOf("agentKey" to "aggregate")),
            ),
            listOf(
                WorkflowEdgePlan("a", "source", "normalize", bindings = listOf(WorkflowFieldBinding("reviewedResults", "reviewedResults"))),
                WorkflowEdgePlan("b", "normalize", "aggregate", bindings = listOf(WorkflowFieldBinding("normalizedResults", "normalizedResults"))),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", emptyList(), listOf("result"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan),
            listOf(source, aggregate), emptyList(),
        )

        val normalized = pipeline().normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.proposal.graphPlan!!.edges.single { it.id == "b" }.bindings)
            .containsExactly(WorkflowFieldBinding("reviewedResults", "normalizedResults"))
    }

    @Test
    fun `agent reused for aggregation and finalization accepts the union of inputs`() {
        val aggregate = AgentDefinition(
            "aggregate", "Aggregate", "Aggregate", listOf(
                FieldDefinition("research", "string", true, "research"),
                FieldDefinition("review", "string", false, "review"),
            ), listOf(FieldDefinition("draft", "string", true, "draft")),
            listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val source = AgentDefinition(
            "source", "Source", "Source", emptyList(), listOf(FieldDefinition("research", "string", true, "research")),
            listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val review = AgentDefinition(
            "reviewer", "Reviewer", "Review", listOf(FieldDefinition("draft", "string", true, "draft")),
            listOf(FieldDefinition("review", "string", true, "review")), listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val plan = WorkflowGraphPlan(
            "source-node",
            listOf(
                WorkflowNodePlan("source-node", "ai.generate", "Source", mapOf("agentKey" to "source")),
                WorkflowNodePlan("aggregate-node", "ai.generate", "Aggregate", mapOf("agentKey" to "aggregate")),
                WorkflowNodePlan("review-node", "ai.generate", "Review", mapOf("agentKey" to "reviewer")),
                WorkflowNodePlan("final-node", "ai.generate", "Final", mapOf("agentKey" to "aggregate")),
            ),
            listOf(
                WorkflowEdgePlan("a", "source-node", "aggregate-node", bindings = listOf(WorkflowFieldBinding("research", "research"))),
                WorkflowEdgePlan("b", "aggregate-node", "review-node", bindings = listOf(WorkflowFieldBinding("draft", "draft"))),
                WorkflowEdgePlan("c", "review-node", "final-node", bindings = listOf(WorkflowFieldBinding("review", "review"))),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", emptyList(), listOf("draft"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan),
            listOf(source, aggregate, review), emptyList(),
        )

        val normalized = pipeline().normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.agentDefinitions.single { it.key == "aggregate" }.inputSchema).allMatch { !it.required }
    }

    @Test
    fun `external evidence passed through an undeclared agent output is redirected from workflow input`() {
        val interviews = FieldDefinition("interviews", "array", true, "interviews", itemType = "string")
        val analyst = AgentDefinition(
            "analyst", "Analyst", "Analyze", listOf(interviews), listOf(FieldDefinition("analysis", "string", true, "analysis")),
            listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val synthesizer = AgentDefinition(
            "synth", "Synth", "Synthesize", listOf(FieldDefinition("analysis", "string", true, "analysis")),
            listOf(FieldDefinition("synthesis", "string", true, "synthesis")), listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val reviewer = AgentDefinition(
            "reviewer", "Reviewer", "Review", listOf(interviews, FieldDefinition("synthesis", "string", true, "synthesis")),
            listOf(FieldDefinition("review", "string", true, "review")), listOf("work"), listOf("do not invent"), listOf("input"),
        )
        val plan = WorkflowGraphPlan(
            "input",
            listOf(
                WorkflowNodePlan("input", "text.input", "Input"),
                WorkflowNodePlan("analysis", "ai.generate", "Analysis", mapOf("agentKey" to "analyst")),
                WorkflowNodePlan("synth", "ai.generate", "Synth", mapOf("agentKey" to "synth")),
                WorkflowNodePlan("review", "ai.generate", "Review", mapOf("agentKey" to "reviewer")),
            ),
            listOf(
                WorkflowEdgePlan("a", "input", "analysis", bindings = listOf(WorkflowFieldBinding("interviews", "interviews"))),
                WorkflowEdgePlan("b", "analysis", "synth", bindings = listOf(WorkflowFieldBinding("analysis", "analysis"))),
                WorkflowEdgePlan("c", "synth", "review", bindings = listOf(
                    WorkflowFieldBinding("synthesis", "synthesis"), WorkflowFieldBinding("interviews", "interviews"),
                )),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", listOf("interviews"), listOf("review"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan, inputSchema = listOf(interviews)),
            listOf(analyst, synthesizer, reviewer), emptyList(),
        )

        val normalized = pipeline().normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.proposal.graphPlan!!.edges.single { it.id == "c" }.bindings.map { it.sourceField }).containsExactly("synthesis")
        val redirected = normalized.proposal.graphPlan!!.edges.single { it.id == "c-external-interviews" }
        assertThat(redirected.source).isEqualTo("input")
        assertThat(redirected.target).isEqualTo("review")
        assertThat(redirected.bindings).containsExactly(WorkflowFieldBinding("interviews", "interviews"))
    }

    @Test
    fun `problem clarification stops after two card rounds while preserving the ten question ceiling`() {
        assertThat(AgentDevelopmentProblemPolicy.remainingQuestions(0, 0)).isEqualTo(10)
        assertThat(AgentDevelopmentProblemPolicy.remainingQuestions(3, 1)).isEqualTo(7)
        assertThat(AgentDevelopmentProblemPolicy.remainingQuestions(6, 2)).isZero()
    }

    @Test
    fun `problem definition canonicalizes redundant UI flags without changing routing`() {
        val raw = AgentDevelopmentProblemDefinition(
            readyForDesign = false,
            recommendedApproach = AgentDevelopmentApproach.AGENT_TEAM,
            problemStatement = "반복 콘텐츠 제작",
            targetUser = "팀",
            desiredOutcome = "검수된 콘텐츠",
            scope = "분석부터 검수까지",
            constraints = emptyList(),
            assumptions = emptyList(),
            rationale = "분리된 전문 역할이 필요함",
            suggestedPrompt = "",
            clarificationQuestions = listOf(ClarificationQuestion("extra", "extra", "불필요한 질문")),
        )

        val canonical = AgentDevelopmentProblemPolicy.canonicalize(raw, 9)

        assertThat(canonical.readyForDesign).isTrue()
        assertThat(canonical.clarificationQuestions).isEmpty()
        assertThat(canonical.recommendedApproach).isEqualTo(AgentDevelopmentApproach.AGENT_TEAM)
    }

    @Test
    fun `user provided input does not become an unresolved connector`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val material = FieldDefinition("materials", "array", true, "materials", itemType = "string")
        val analyses = FieldDefinition("materialAnalyses", "array", true, "analyses", itemType = "string")
        val plan = WorkflowGraphPlan(
            "input",
            listOf(
                WorkflowNodePlan("input", NodeType.TEXT_INPUT.wireName, "Input"),
                WorkflowNodePlan("analysis", NodeType.UNRESOLVED_TOOL.wireName, "자료 분석", mapOf("source" to "사용자 입력 자료")),
                WorkflowNodePlan("review", NodeType.AI_GENERATE.wireName, "Review", mapOf("agentKey" to "reviewer")),
            ),
            listOf(
                WorkflowEdgePlan("in", "input", "analysis", bindings = listOf(WorkflowFieldBinding("materials", "materials"))),
                WorkflowEdgePlan("out", "analysis", "review", bindings = listOf(WorkflowFieldBinding("materialAnalyses", "materialAnalyses"))),
            ),
        )
        val reviewer = AgentDefinition(
            "reviewer", "Reviewer", "Review", listOf(analyses), listOf(FieldDefinition("result", "string", true, "result")),
            listOf("review"), listOf("do not invent"), listOf("evidence"),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", listOf("materials"), listOf("result"), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan, inputSchema = listOf(material)),
            listOf(reviewer), emptyList(),
        )

        val normalized = pipeline.replaceDirectInputUnresolvedTools(bundle)

        assertThat(normalized.proposal.graphPlan!!.nodes.single { it.id == "analysis" }.nodeType).isEqualTo(NodeType.AI_GENERATE.wireName)
        assertThat(normalized.agentDefinitions).hasSize(2)
        assertThat(normalized.agentDefinitions.last().outputSchema).containsExactly(analyses)
    }

    @Test
    fun `unresolved tool config gets generic required metadata`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val plan = WorkflowGraphPlan(
            entryNodeId = "lookup",
            nodes = listOf(WorkflowNodePlan(
                "lookup", NodeType.UNRESOLVED_TOOL.wireName, "자료 조사 Connector", mapOf("source" to "provided"),
            )),
            edges = emptyList(),
        )

        val normalized = pipeline.normalizeUnresolvedToolConfig(plan).nodes.single().config

        assertThat(normalized["toolName"]).isEqualTo("자료 조사 Connector")
        assertThat(normalized["connectionStatus"]).isEqualTo("UNRESOLVED")
        assertThat(normalized["reason"]).isEqualTo("현재 서버에 실행 가능한 Connector가 설정되지 않았습니다.")
    }

    @Test
    fun `open object contracts fall back to bounded strings without scenario rules`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )

        val fields = pipeline.closeOpenFields(listOf(
            FieldDefinition("summary", "object", true, "free form summary"),
            FieldDefinition("items", "array", true, "free form items", itemType = "object"),
        ))

        assertThat(fields[0].type).isEqualTo("string")
        assertThat(fields[1].itemType).isEqualTo("string")
    }

    @Test
    fun `agent binding copies the complete upstream object contract through pass-through outputs`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val item = FieldDefinition("content", "string", true, "content")
        val complete = FieldDefinition("extraction", "object", true, "complete", objectSchema = listOf(
            FieldDefinition("decisions", "array", true, "decisions", itemType = "object", itemSchema = listOf(item)),
        ))
        fun agent(key: String, inputs: List<FieldDefinition>, outputs: List<FieldDefinition>) = AgentDefinition(
            key, key, key, inputs, outputs, listOf("work"), listOf("do not invent"), listOf("evidence"),
        )
        val incomplete = FieldDefinition("extraction", "object", true, "incomplete")
        val plan = WorkflowGraphPlan(
            "source-node",
            listOf(
                WorkflowNodePlan("source-node", "ai.generate", "Source", mapOf("agentKey" to "source")),
                WorkflowNodePlan("middle-node", "ai.generate", "Middle", mapOf("agentKey" to "middle")),
                WorkflowNodePlan("target-node", "ai.generate", "Target", mapOf("agentKey" to "target")),
            ),
            listOf(
                WorkflowEdgePlan("first", "source-node", "middle-node", bindings = listOf(WorkflowFieldBinding("extraction", "extraction"))),
                WorkflowEdgePlan("second", "middle-node", "target-node", bindings = listOf(WorkflowFieldBinding("extraction", "extraction"))),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", emptyList(), emptyList(), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan),
            listOf(agent("source", emptyList(), listOf(complete)), agent("middle", listOf(incomplete), listOf(incomplete)), agent("target", listOf(incomplete), emptyList())),
            emptyList(),
        )

        val normalized = pipeline.normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.agentDefinitions[1].inputSchema.single().objectSchema).isNotEmpty
        assertThat(normalized.agentDefinitions[1].outputSchema.single().objectSchema).isNotEmpty
        assertThat(normalized.agentDefinitions[2].inputSchema.single().objectSchema).isNotEmpty
    }

    @Test
    fun `multiple bound producers preserve the union of nested enum values`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        fun result(value: String) = FieldDefinition("results", "array", true, "results", itemType = "object", itemSchema = listOf(
            FieldDefinition("reviewType", "string", true, "type", enumValues = listOf(value)),
        ))
        fun agent(key: String, input: List<FieldDefinition>, output: List<FieldDefinition>) = AgentDefinition(
            key, key, key, input, output, listOf("work"), listOf("do not invent"), listOf("evidence"),
        )
        val combined = result("first").copy(itemSchema = listOf(
            FieldDefinition("reviewType", "string", true, "type", enumValues = listOf("first", "second")),
        ))
        val plan = WorkflowGraphPlan(
            "first-node",
            listOf(
                WorkflowNodePlan("first-node", "ai.generate", "First", mapOf("agentKey" to "first")),
                WorkflowNodePlan("second-node", "ai.generate", "Second", mapOf("agentKey" to "second")),
                WorkflowNodePlan("collect-node", "ai.generate", "Collect", mapOf("agentKey" to "collector")),
            ),
            listOf(
                WorkflowEdgePlan("first-collect", "first-node", "collect-node", bindings = listOf(WorkflowFieldBinding("results", "results"))),
                WorkflowEdgePlan("second-collect", "second-node", "collect-node", bindings = listOf(WorkflowFieldBinding("results", "results"))),
            ),
        )
        val bundle = MetaAgentDesignBundle(
            AutomationRequirement("work", "manual", emptyList(), emptyList(), listOf("work"), emptyList(), emptyList(), false),
            emptyList(), AutomationProposal("work", "work", listOf("work"), emptyList(), emptyList(), "stop", graphPlan = plan),
            listOf(agent("first", emptyList(), listOf(result("first"))), agent("second", emptyList(), listOf(result("second"))), agent("collector", listOf(combined), emptyList())),
            emptyList(),
        )

        val normalized = pipeline.normalizeBoundAgentSchemas(bundle)

        assertThat(normalized.agentDefinitions.last().inputSchema.single().itemSchema!!.single().enumValues)
            .containsExactlyInAnyOrder("first", "second")
    }

    @Test
    fun `single workflow input replaces an invented entry binding`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val plan = WorkflowGraphPlan(
            entryNodeId = "start",
            nodes = listOf(
                WorkflowNodePlan("start", "manual.trigger", "Start"),
                WorkflowNodePlan("input", "text.input", "Input"),
            ),
            edges = listOf(WorkflowEdgePlan("edge", "start", "input", bindings = listOf(WorkflowFieldBinding("message", "message")))),
        )

        val normalized = pipeline.normalizeEntryBindings(
            plan,
            listOf(FieldDefinition("applications", "array", true, "applications", itemType = "object")),
        )

        assertThat(normalized.edges.single().bindings).containsExactly(WorkflowFieldBinding("applications", "applications"))
    }

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
    fun `undeclared generated agent defaults are removed before semantic validation`() {
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val plan = WorkflowGraphPlan(
            entryNodeId = "inspect",
            nodes = listOf(WorkflowNodePlan(
                "inspect", "ai.generate", "Inspect",
                mapOf(
                    "agentKey" to "inspector",
                    "instruction" to "inspect",
                    "inputDefaults" to mapOf("severity" to "HIGH", "inspectionSlot" to 1),
                ),
            )),
            edges = emptyList(),
        )
        val inspector = AgentDefinition(
            "inspector", "Inspector", "Inspect equipment",
            listOf(FieldDefinition("severity", "string", true, "severity")),
            listOf(FieldDefinition("decision", "string", true, "decision")),
            listOf("inspect"), listOf("do not invent"), listOf("input"),
        )

        val normalized = pipeline.removeUndeclaredAgentInputDefaults(plan, listOf(inspector))

        assertThat(normalized.nodes.single().config["inputDefaults"])
            .isEqualTo(mapOf("severity" to "HIGH"))
    }

    @Test
    fun `duplicate branch edges to the same target merge without losing bindings`() {
        val plan = WorkflowGraphPlan(
            entryNodeId = "route",
            nodes = listOf(
                WorkflowNodePlan("route", "condition.branch", "Route", mapOf("expression" to "qualityPassed")),
                WorkflowNodePlan("report", "workflow.end", "Report"),
            ),
            edges = listOf(
                WorkflowEdgePlan("success-a", "route", "report", "qualityPassed=true", listOf(WorkflowFieldBinding("first", "first"))),
                WorkflowEdgePlan("success-b", "route", "report", "qualityPassed=true", listOf(WorkflowFieldBinding("second", "second"))),
            ),
        )

        val normalized = com.agentvillage.builder.application.WorkflowGraphPlanNormalizer.normalize(plan)

        assertThat(normalized.edges).hasSize(1)
        assertThat(normalized.edges.single().bindings).containsExactlyInAnyOrder(
            WorkflowFieldBinding("first", "first"), WorkflowFieldBinding("second", "second"),
        )
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
