package com.agentvillage.builder

import com.agentvillage.builder.application.ExternalWorkflowInputContract
import com.agentvillage.builder.application.WorkflowInputContract
import com.agentvillage.builder.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkflowInputContractTest {
    private val fields = listOf(
        FieldDefinition("warehouses", "array", true, "exactly three warehouse names", minItems = 3, maxItems = 3),
        FieldDefinition("asOfDate", "string", true, "research date"),
        FieldDefinition("fixture", "object", true, "provided evidence"),
    )

    @Test
    fun `explicit workflow input contract accepts only declared typed fields`() {
        val input = mapOf(
            "warehouses" to listOf("East", "West", "Central"),
            "asOfDate" to "2026-09-05",
            "fixture" to mapOf("East" to emptyList<Any>()),
        )

        assertThat(WorkflowInputContract.valueIssue(fields, input)).isNull()
    }

    @Test
    fun `explicit workflow input contract rejects missing wrong and internal fields`() {
        assertThat(WorkflowInputContract.valueIssue(fields, mapOf("warehouses" to emptyList<Any>(), "asOfDate" to "2026-09-05")))
            .contains("fixture", "필수")
        assertThat(WorkflowInputContract.valueIssue(fields, mapOf("warehouses" to "East", "asOfDate" to "2026-09-05", "fixture" to emptyMap<String, Any?>())))
            .contains("warehouses", "array")
        assertThat(WorkflowInputContract.valueIssue(fields, mapOf("warehouses" to emptyList<Any>(), "asOfDate" to "2026-09-05", "fixture" to emptyMap<String, Any?>(), "evidenceIds" to emptyList<Any>())))
            .contains("evidenceIds", "선언되지 않은")
        assertThat(WorkflowInputContract.valueIssue(listOf(FieldDefinition("note", "string", false, "optional note")), mapOf("note" to null)))
            .contains("note", "string")
    }

    @Test
    fun `empty legacy workflow input contract remains backward compatible`() {
        assertThat(WorkflowInputContract.valueIssue(emptyList(), mapOf("message" to "legacy input"))).isNull()
        assertThat(WorkflowInputContract.valueIssue(emptyList(), mapOf("message" to "unexpected"), enforceEmpty = true))
            .contains("message", "선언되지 않은")
        assertThat(WorkflowInputContract.valueIssue(emptyList(), emptyMap(), enforceEmpty = true)).isNull()
    }

    @Test
    fun `workflow input schema rejects duplicate names and unsupported types`() {
        assertThat(WorkflowInputContract.schemaIssue(fields + fields.first())).contains("warehouses", "중복")
        assertThat(WorkflowInputContract.schemaIssue(listOf(FieldDefinition("when", "date", true, "unsupported"))))
            .contains("when", "date", "지원하지")
    }

    @Test
    fun `integer is distinct from fractional number and array cardinality is enforced`() {
        val constrained = fields + FieldDefinition("attempts", "integer", true, "whole retry count")

        assertThat(WorkflowInputContract.valueIssue(constrained, mapOf(
            "warehouses" to listOf("East", "West"),
            "asOfDate" to "2026-09-05",
            "fixture" to emptyMap<String, Any?>(),
            "attempts" to 1,
        ))).contains("warehouses", "최소 3개")
        assertThat(WorkflowInputContract.valueIssue(constrained, mapOf(
            "warehouses" to listOf("East", "West", "Central", "North"),
            "asOfDate" to "2026-09-05",
            "fixture" to emptyMap<String, Any?>(),
            "attempts" to 1,
        ))).contains("warehouses", "최대 3개")
        assertThat(WorkflowInputContract.valueIssue(constrained, mapOf(
            "warehouses" to listOf("East", "West", "Central"),
            "asOfDate" to "2026-09-05",
            "fixture" to emptyMap<String, Any?>(),
            "attempts" to 1.5,
        ))).contains("attempts", "integer")
        assertThat(WorkflowInputContract.valueIssue(constrained, mapOf(
            "warehouses" to listOf("East", "West", "Central"),
            "asOfDate" to "2026-09-05",
            "fixture" to emptyMap<String, Any?>(),
            "attempts" to 1.0,
        ))).contains("attempts", "integer")
        assertThat(WorkflowInputContract.valueIssue(constrained, mapOf(
            "warehouses" to listOf("East", "West", "Central"),
            "asOfDate" to "2026-09-05",
            "fixture" to emptyMap<String, Any?>(),
            "attempts" to 2,
        ))).isNull()
    }

    @Test
    fun `array constraints are internally valid`() {
        assertThat(WorkflowInputContract.schemaIssue(listOf(FieldDefinition("name", "string", true, "name", minItems = 1))))
            .contains("name", "array 타입")
        assertThat(WorkflowInputContract.schemaIssue(listOf(FieldDefinition("items", "array", true, "items", minItems = 4, maxItems = 3))))
            .contains("minItems", "maxItems")
        assertThat(WorkflowInputContract.schemaIssue(listOf(FieldDefinition("name", "string", true, "name", itemSchema = emptyList()))))
            .contains("array 타입")
        assertThat(WorkflowInputContract.schemaIssue(listOf(FieldDefinition("items", "array", true, "items", itemType = "object", itemSchema = emptyList()))))
            .contains("비어 있지 않은 itemSchema")
    }

    @Test
    fun `nested object array rejects malformed items and missing evidence`() {
        val resultFields = listOf(
            FieldDefinition("warehouse", "string", true, "warehouse"),
            FieldDefinition("evidenceIds", "array", true, "evidence", itemType = "string"),
            FieldDefinition("status", "string", true, "status"),
        )
        val contract = listOf(FieldDefinition(
            "warehouseResults", "array", true, "warehouse results",
            itemType = "object", itemSchema = resultFields,
        ))

        assertThat(WorkflowInputContract.valueIssue(contract, mapOf("warehouseResults" to listOf("malformed"))))
            .contains("warehouseResults[0]", "object")
        assertThat(WorkflowInputContract.valueIssue(contract, mapOf("warehouseResults" to listOf(
            mapOf("warehouse" to "동부", "status" to "SUCCEEDED"),
        )))).contains("warehouseResults[0].evidenceIds", "없습니다")
        assertThat(WorkflowInputContract.valueIssue(contract, mapOf("warehouseResults" to listOf(
            mapOf("warehouse" to "동부", "evidenceIds" to listOf(100), "status" to "SUCCEEDED"),
        )))).contains("warehouseResults[0].evidenceIds[0]", "string")
        assertThat(WorkflowInputContract.valueIssue(contract, mapOf("warehouseResults" to listOf(
            mapOf("warehouse" to "동부", "evidenceIds" to listOf("E-WH-001"), "status" to "SUCCEEDED"),
        )))).isNull()
    }

    @Test
    fun `external contract root normalizes nested target bindings`() {
        val proposal = AutomationProposal(
            name = "nested", summary = "nested", capabilities = emptyList(), integrations = emptyList(),
            approvalPoints = emptyList(), failurePolicy = "stop",
            graphPlan = WorkflowGraphPlan(
                entryNodeId = "start",
                nodes = listOf(
                    WorkflowNodePlan("start", "manual.trigger", "Start"),
                    WorkflowNodePlan("agent", "ai.generate", "Agent", mapOf("agentKey" to "worker")),
                ),
                edges = listOf(WorkflowEdgePlan(
                    "edge", "start", "agent", bindings = listOf(WorkflowFieldBinding("request.payload", "details.items")),
                )),
            ),
        )
        val agent = AgentDefinition(
            key = "worker", name = "Worker", role = "work",
            inputSchema = listOf(FieldDefinition("details", "object", true, "nested details")),
            outputSchema = emptyList(), behaviorRules = emptyList(), forbiddenRules = emptyList(), evidenceRequirements = emptyList(),
        )

        assertThatThrownBy { ExternalWorkflowInputContract.resolve(proposal, listOf(agent)) }
            .hasMessageContaining("명시적 proposal.inputSchema")
    }
}
