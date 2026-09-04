package com.agentvillage.builder

import com.agentvillage.builder.application.WorkflowGraphValidator
import com.agentvillage.builder.application.WorkflowNodeCatalog
import com.agentvillage.builder.domain.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkflowGraphValidatorTest {
    private val validator = WorkflowGraphValidator(WorkflowNodeCatalog(), jacksonObjectMapper())

    private fun graph(approval: Boolean = true): WorkflowGraph {
        val nodes = mutableListOf(
            WorkflowNode("trigger", "slack.new_message.mock", "trigger", NodePosition(0.0, 0.0)),
            WorkflowNode("search", "notion.search.mock", "search", NodePosition(1.0, 0.0), mapOf("database" to "FAQ")),
            WorkflowNode("generate", "ai.generate", "generate", NodePosition(2.0, 0.0), mapOf("instruction" to "answer")),
        )
        if (approval) nodes += WorkflowNode("approval", "human.approval", "approval", NodePosition(3.0, 0.0), mapOf("approver" to "owner"))
        nodes += WorkflowNode("reply", "slack.reply.mock", "reply", NodePosition(4.0, 0.0))
        return WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "trigger", nodes = nodes, edges = nodes.zipWithNext().mapIndexed { index, pair -> WorkflowEdge("e$index", pair.first.id, pair.second.id) })
    }

    @Test fun `valid workflow graph is accepted`() = assertThat(validator.validate(graph()).valid).isTrue()

    @Test fun `unknown node is rejected`() {
        val graph = graph().let { it.copy(nodes = it.nodes + WorkflowNode("evil", "code.python", "evil", NodePosition(0.0, 0.0)), edges = it.edges + WorkflowEdge("evil-edge", "reply", "evil")) }
        assertThat(validator.validate(graph).issues.map { it.code }).contains("NODE_TYPE_NOT_ALLOWED")
    }

    @Test fun `edge to missing node is rejected`() {
        val graph = graph().let { it.copy(edges = it.edges + WorkflowEdge("bad", "reply", "missing")) }
        assertThat(validator.validate(graph).issues.map { it.code }).contains("INVALID_EDGE")
    }

    @Test fun `required config is validated`() {
        val graph = graph().let { original -> original.copy(nodes = original.nodes.map { if (it.id == "search") it.copy(config = emptyMap()) else it }) }
        assertThat(validator.validate(graph).issues.map { it.code }).contains("INVALID_NODE_CONFIG")
    }

    @Test fun `every path to external write requires approval`() {
        assertThat(validator.validate(graph(approval = false)).issues.map { it.code }).contains("WRITE_REQUIRES_APPROVAL")
        val bypass = graph().let { it.copy(edges = it.edges + WorkflowEdge("bypass", "generate", "reply")) }
        assertThat(validator.validate(bypass).issues.map { it.code }).contains("WRITE_REQUIRES_APPROVAL")
    }

    @Test fun `FAQ template is rejected when requirement does not request Slack or Notion`() {
        val requirement = AutomationRequirement(
            objective = "사용자가 입력한 문장을 분류한다.",
            trigger = "수동 실행",
            inputs = listOf("분류할 문장"),
            outputs = listOf("분류 결과"),
            steps = listOf("문장 분류"),
            decisions = listOf("카테고리 선택"),
            exceptions = emptyList(),
            humanApprovalRequired = false,
        )
        val proposal = AutomationProposal("문장 분류", "문장을 분류한다.", listOf("문장 분류"), emptyList(), emptyList(), "실패 시 중단")
        val agent = AgentDefinition("classifier", "분류 담당", "문장을 분류한다.", emptyList(), emptyList(), listOf("분류한다"), listOf("추측하지 않는다"), listOf("입력 문장"))

        val result = validator.validate(graph(), requirement, proposal, listOf(agent))

        assertThat(result.valid).isFalse()
        assertThat(result.issues.map { it.code }).contains(
            "MEANING_TRIGGER_MISSING",
            "MEANING_DECISION_MISSING",
            "MEANING_UNREQUESTED_INTEGRATION",
        )
    }

    @Test fun `dynamic manual classification graph matches its requirement`() {
        val requirement = AutomationRequirement(
            objective = "사용자가 입력한 문장을 분류한다.",
            trigger = "수동 실행",
            inputs = listOf("분류할 문장"),
            outputs = listOf("분류 결과"),
            steps = listOf("문장 분류"),
            decisions = listOf("카테고리 선택"),
            exceptions = emptyList(),
            humanApprovalRequired = false,
        )
        val proposal = AutomationProposal("문장 분류", "문장을 분류한다.", listOf("문장 분류"), emptyList(), emptyList(), "실패 시 중단")
        val agent = AgentDefinition("classifier", "분류 담당", "문장을 분류한다.", emptyList(), emptyList(), listOf("분류한다"), listOf("추측하지 않는다"), listOf("입력 문장"))
        val nodes = listOf(
            WorkflowNode("manual", NodeType.MANUAL_TRIGGER.wireName, "수동 시작", NodePosition(0.0, 0.0)),
            WorkflowNode("classify", NodeType.AI_CLASSIFY.wireName, "문장 분류", NodePosition(1.0, 0.0), mapOf("categories" to listOf("문의", "요청"), "agentKey" to "classifier")),
        )
        val dynamic = WorkflowGraph(
            workflowId = UUID.randomUUID(),
            entryNodeId = "manual",
            nodes = nodes,
            edges = listOf(WorkflowEdge("edge", "manual", "classify")),
        )

        assertThat(validator.validate(dynamic, requirement, proposal, listOf(agent)).valid).isTrue()
    }

    @Test fun `structured requirement cannot add Slack FAQ meaning absent from user request`() {
        val requirement = AutomationRequirement(
            objective = "Slack 문의를 Notion FAQ로 답변한다.",
            trigger = "Slack 문의 수신",
            inputs = listOf("고객 문의", "Notion FAQ"),
            outputs = listOf("답변 초안"),
            steps = listOf("FAQ 검색", "답변 작성"),
            decisions = emptyList(),
            exceptions = emptyList(),
            humanApprovalRequired = false,
        )
        val proposal = AutomationProposal("FAQ 답변", "FAQ로 답변한다.", listOf("FAQ 답변"), listOf("Slack Mock", "Notion Mock"), emptyList(), "실패 시 중단")

        val result = validator.validate(graph(approval = false), requirement, proposal, emptyList(), "사용자가 입력한 문장을 분류한다.")

        assertThat(result.issues.map { it.code }).contains("MEANING_REQUIREMENT_ADDED")
        assertThat(result.issues.map { it.message }).anyMatch { it.contains("Slack") }
        assertThat(result.issues.map { it.message }).anyMatch { it.contains("Notion/FAQ") }
    }

    @Test fun `routing an unsupported FAQ answer to assignee review is not an execution approval gate`() {
        val requirement = AutomationRequirement(
            objective = "FAQ 근거로 답하고 없으면 담당자 확인 필요 상태를 반환한다.",
            trigger = "수동 문의 입력",
            inputs = listOf("고객 문의", "FAQ 자료"),
            outputs = listOf("답변 또는 담당자 확인 필요 상태"),
            steps = listOf("FAQ 검색", "근거 확인", "답변 작성"),
            decisions = listOf("FAQ 근거 존재 여부"),
            exceptions = listOf("근거 없음"),
            humanApprovalRequired = false,
        )
        val proposal = AutomationProposal("FAQ 답변", "근거가 없으면 담당자에게 보낸다.", listOf("FAQ 검색"), listOf("FAQ Mock"), emptyList(), "담당자 확인 필요 상태로 종료")
        val nodes = listOf(
            WorkflowNode("manual", NodeType.MANUAL_TRIGGER.wireName, "문의 입력", NodePosition(0.0, 0.0)),
            WorkflowNode("search", NodeType.KNOWLEDGE_SEARCH_MOCK.wireName, "FAQ 검색", NodePosition(1.0, 0.0), mapOf("source" to "FAQ", "queryField" to "customerInquiry", "connectionStatus" to "UNRESOLVED")),
            WorkflowNode("end", NodeType.WORKFLOW_END.wireName, "완료", NodePosition(2.0, 0.0)),
        )
        val graph = WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "manual", nodes = nodes, edges = listOf(WorkflowEdge("e1", "manual", "search"), WorkflowEdge("e2", "search", "end")))

        val result = validator.validate(graph, requirement, proposal, emptyList(), "FAQ를 찾아 고객 질문에 답하고 근거가 없으면 담당자 검토로 보내는 에이전트")

        assertThat(result.issues).noneMatch { it.code == "MEANING_REQUIREMENT_DROPPED" && it.message.contains("사람 승인") }
    }

    @Test fun `internal agent fields cannot be invented as workflow inputs`() {
        val requirement = AutomationRequirement(
            objective = "사용자가 제공한 자료를 분석해 결과를 생성한다.", trigger = "수동 실행",
            inputs = listOf("records"), outputs = listOf("analysis"), steps = listOf("자료 분석"),
            decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "자료 분석", summary = "자료를 분석한다.", capabilities = listOf("자료 분석"),
            integrations = emptyList(), approvalPoints = emptyList(), failurePolicy = "실패 시 중단",
            inputSchema = listOf(FieldDefinition("records", "array", true, "사용자 자료")),
        )
        val agent = AgentDefinition(
            key = "analyst", name = "분석가", role = "자료를 분석한다.",
            inputSchema = listOf(
                FieldDefinition("records", "array", true, "사용자 자료"),
                FieldDefinition("scope", "string", true, "내부 작업 범위"),
            ),
            outputSchema = listOf(FieldDefinition("analysis", "string", true, "분석 결과")),
            behaviorRules = listOf("자료만 사용"), forbiddenRules = listOf("추측 금지"), evidenceRequirements = listOf("입력 자료"),
        )
        val reporter = AgentDefinition(
            key = "reporter", name = "보고자", role = "결과를 정리한다.",
            inputSchema = listOf(FieldDefinition("records", "array", true, "원본 사용자 자료")),
            outputSchema = listOf(FieldDefinition("analysis", "string", true, "최종 결과")),
            behaviorRules = listOf("자료만 사용"), forbiddenRules = listOf("추측 금지"), evidenceRequirements = listOf("입력 자료"),
        )
        val nodes = listOf(
            WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "시작", NodePosition(0.0, 0.0)),
            WorkflowNode(
                "analyze", NodeType.AI_GENERATE.wireName, "분석", NodePosition(1.0, 0.0),
                mapOf("agentKey" to "analyst", "instruction" to "자료 분석", "inputDefaults" to mapOf("scope" to emptyList<String>())),
            ),
            WorkflowNode("report", NodeType.AI_GENERATE.wireName, "정리", NodePosition(2.0, 0.0), mapOf("agentKey" to "reporter", "instruction" to "결과 정리")),
        )
        val invalid = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start", nodes = nodes,
            edges = listOf(
                WorkflowEdge(
                    "edge", "start", "analyze",
                    bindings = mapOf("records" to "records", "scope" to "records", "evidenceIds" to "evidenceIds"),
                ),
                WorkflowEdge("next", "analyze", "report"),
            ),
        )

        val issues = validator.validate(
            invalid, requirement, proposal, listOf(agent, reporter),
            "입력은 records 배열(정확히 3개)이며 자료를 분석해 결과를 생성한다.",
        ).issues
        val codes = issues.map { it.code }

        assertThat(codes).contains(
            "MEANING_AGENT_INPUT_UNBOUND", "MEANING_WORKFLOW_INPUT_UNDECLARED", "MEANING_AGENT_INPUT_UNDECLARED",
            "MEANING_AGENT_DEFAULT_INVALID", "MEANING_BINDING_TYPE_MISMATCH",
            "MEANING_INPUT_CARDINALITY_MISMATCH",
        )
        assertThat(issues).anyMatch { it.code == "MEANING_AGENT_INPUT_UNBOUND" && it.nodeId == "report" && it.message.contains("records") }
    }

    @Test fun `explicit workflow input field list rejects added top level fields`() {
        val requirement = AutomationRequirement(
            objective = "세 지점 자료를 점검한다.", trigger = "수동 실행",
            inputs = listOf("warehouses", "asOfDate", "fixture"), outputs = listOf("summary"),
            steps = listOf("점검"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "지점 점검", summary = "자료 점검", capabilities = listOf("점검"), integrations = emptyList(),
            approvalPoints = emptyList(), failurePolicy = "실패 시 중단",
            inputSchema = listOf(
                FieldDefinition("warehouses", "array", true, "지점", 3, 3, "string"),
                FieldDefinition("asOfDate", "string", true, "기준일"),
                FieldDefinition("fixture", "object", true, "자료"),
                FieldDefinition("evidenceIds", "array", true, "내부 근거", itemType = "string"),
            ),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "시작", NodePosition(0.0, 0.0))),
            edges = emptyList(),
        )

        val issues = validator.validate(
            graph, requirement, proposal, emptyList(),
            "입력은 warehouses 배열(정확히 3개), asOfDate, fixture이고 제공 자료만 사용해.",
        ).issues

        assertThat(issues).anyMatch {
            it.code == "MEANING_EXPLICIT_INPUT_FIELDS_MISMATCH" && it.message.contains("evidenceIds")
        }
    }

    @Test fun `explicit workflow input field list accepts the exact declared set`() {
        val requirement = AutomationRequirement(
            objective = "자료를 점검한다.", trigger = "수동 실행", inputs = listOf("records", "asOfDate"),
            outputs = listOf("summary"), steps = listOf("점검"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "자료 점검", summary = "자료 점검", capabilities = listOf("점검"), integrations = emptyList(),
            approvalPoints = emptyList(), failurePolicy = "실패 시 중단",
            inputSchema = listOf(FieldDefinition("records", "array", true, "자료", itemType = "string"), FieldDefinition("asOfDate", "string", true, "기준일")),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "시작", NodePosition(0.0, 0.0))),
            edges = emptyList(),
        )

        val issues = validator.validate(graph, requirement, proposal, emptyList(), "입력 필드는 records, asOfDate입니다.").issues

        assertThat(issues).noneMatch { it.code == "MEANING_EXPLICIT_INPUT_FIELDS_MISMATCH" }
    }

    @Test fun `explicit primitive array item type rejects an object item contract`() {
        val requirement = AutomationRequirement(
            objective = "세 지점을 점검한다.", trigger = "수동 실행", inputs = listOf("warehouses"), outputs = listOf("summary"),
            steps = listOf("점검"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "점검", summary = "점검", capabilities = listOf("점검"), integrations = emptyList(), approvalPoints = emptyList(),
            failurePolicy = "실패 시 중단", inputSchema = listOf(FieldDefinition(
                "warehouses", "array", true, "창고", itemType = "object",
                itemSchema = listOf(FieldDefinition("warehouse", "string", true, "창고명")),
            )),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "시작", NodePosition(0.0, 0.0))), edges = emptyList(),
        )

        val issues = validator.validate(graph, requirement, proposal, emptyList(), "입력은 warehouses 문자열 배열입니다.").issues

        assertThat(issues).anyMatch { it.code == "MEANING_INPUT_ITEM_TYPE_MISMATCH" && it.message.contains("string") }
    }

    @Test fun `agent count is not mistaken for workflow array cardinality`() {
        val requirement = AutomationRequirement(
            objective = "Agent 정확히 3개가 협업한다.", trigger = "수동 실행", inputs = emptyList(), outputs = listOf("result"),
            steps = listOf("협업"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "협업", summary = "협업", capabilities = listOf("협업"), integrations = emptyList(), approvalPoints = emptyList(),
            failurePolicy = "실패 시 중단", inputSchema = emptyList(),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "시작", NodePosition(0.0, 0.0))), edges = emptyList(),
        )

        val issues = validator.validate(graph, requirement, proposal, emptyList(), "Agent 정확히 3개가 협업해줘.").issues

        assertThat(issues).noneMatch { it.code == "MEANING_INPUT_CARDINALITY_MISMATCH" }
    }

    @Test fun `prose CSV input and output type phrases are not mistaken for explicit fields`() {
        val requirement = AutomationRequirement(
            objective = "CSV를 비교한다.", trigger = "수동 실행", inputs = listOf("CSV 두 파일"), outputs = listOf("evidenceIds string array"),
            steps = listOf("비교"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "CSV 비교", summary = "비교", capabilities = listOf("비교"), integrations = emptyList(), approvalPoints = emptyList(),
            failurePolicy = "실패 시 중단", inputSchema = listOf(
                FieldDefinition("csvA", "string", true, "기준 CSV"), FieldDefinition("csvB", "string", true, "대상 CSV"),
            ),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "시작", NodePosition(0.0, 0.0))), edges = emptyList(),
        )

        val issues = validator.validate(
            graph, requirement, proposal, emptyList(),
            "입력은 CSV 두 파일이며 결과의 evidenceIds string array를 보존해줘.",
        ).issues

        assertThat(issues).noneMatch { it.code in setOf("MEANING_EXPLICIT_INPUT_FIELDS_MISMATCH", "MEANING_INPUT_ITEM_TYPE_MISMATCH") }
    }

    @Test fun `type first English explicit inputs resolve field names`() {
        val requirement = AutomationRequirement(
            objective = "Process input.", trigger = "manual", inputs = listOf("query", "retryCount"), outputs = listOf("result"),
            steps = listOf("process"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "process", summary = "process", capabilities = listOf("process"), integrations = emptyList(), approvalPoints = emptyList(),
            failurePolicy = "stop", inputSchema = listOf(
                FieldDefinition("query", "string", true, "query"), FieldDefinition("retryCount", "integer", true, "retry count"),
            ),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "start", NodePosition(0.0, 0.0))), edges = emptyList(),
        )

        val issues = validator.validate(graph, requirement, proposal, emptyList(), "inputs are a string query, an integer retryCount.").issues

        assertThat(issues).noneMatch { it.code == "MEANING_EXPLICIT_INPUT_FIELDS_MISMATCH" }
    }

    @Test fun `uppercase URL and SKU remain valid explicit input field names`() {
        val requirement = AutomationRequirement(
            objective = "Process input.", trigger = "manual", inputs = listOf("URL", "SKU"), outputs = listOf("result"),
            steps = listOf("process"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "process", summary = "process", capabilities = listOf("process"), integrations = emptyList(), approvalPoints = emptyList(),
            failurePolicy = "stop", inputSchema = listOf(
                FieldDefinition("URL", "string", true, "url"), FieldDefinition("SKU", "string", true, "sku"),
            ),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(WorkflowNode("start", NodeType.MANUAL_TRIGGER.wireName, "start", NodePosition(0.0, 0.0))), edges = emptyList(),
        )

        val issues = validator.validate(graph, requirement, proposal, emptyList(), "inputs are URL and SKU.").issues

        assertThat(issues).noneMatch { it.code == "MEANING_EXPLICIT_INPUT_FIELDS_MISMATCH" }
    }

    @Test fun `array binding item contracts must be compatible`() {
        val requirement = AutomationRequirement(
            objective = "Join results.", trigger = "manual", inputs = listOf("seed"), outputs = listOf("report"),
            steps = listOf("work", "report"), decisions = emptyList(), exceptions = emptyList(), humanApprovalRequired = false,
        )
        val proposal = AutomationProposal(
            name = "join", summary = "join", capabilities = listOf("join"), integrations = emptyList(), approvalPoints = emptyList(),
            failurePolicy = "stop", inputSchema = listOf(FieldDefinition("seed", "string", true, "seed")),
        )
        val worker = AgentDefinition(
            "worker", "worker", "worker", listOf(FieldDefinition("seed", "string", true, "seed")),
            listOf(FieldDefinition(
                "results", "array", true, "objects", itemType = "object",
                itemSchema = listOf(
                    FieldDefinition("name", "string", true, "name"),
                    FieldDefinition("internal", "string", false, "undeclared downstream field"),
                ),
            )),
            listOf("work"), listOf("invent"), listOf("seed"),
        )
        val reporter = AgentDefinition(
            "reporter", "reporter", "reporter",
            listOf(FieldDefinition(
                "results", "array", true, "objects", itemType = "object",
                itemSchema = listOf(FieldDefinition("name", "string", true, "name")),
            )),
            listOf(FieldDefinition("report", "string", true, "report")),
            listOf("report"), listOf("omit"), listOf("results"),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(
                WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                WorkflowNode("worker", "ai.generate", "Worker", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                WorkflowNode("report", "ai.generate", "Report", NodePosition(0.0, 0.0), mapOf("agentKey" to "reporter")),
            ),
            edges = listOf(
                WorkflowEdge("start-worker", "start", "worker", bindings = mapOf("seed" to "seed")),
                WorkflowEdge("worker-report", "worker", "report", bindings = mapOf("results" to "results")),
            ),
        )

        val issues = validator.validate(graph, requirement, proposal, listOf(worker, reporter), "Join results.").issues

        assertThat(issues).anyMatch { it.code == "MEANING_BINDING_TYPE_MISMATCH" }

        val optionalWorker = worker.copy(outputSchema = listOf(FieldDefinition(
            "results", "array", true, "objects", itemType = "object",
            itemSchema = listOf(FieldDefinition("name", "string", false, "optional source name")),
        )))
        val optionalIssues = validator.validate(graph, requirement, proposal, listOf(optionalWorker, reporter), "Join results.").issues
        assertThat(optionalIssues).anyMatch { it.code == "MEANING_BINDING_TYPE_MISMATCH" }
    }
}
