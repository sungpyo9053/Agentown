package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AgentDefinition
import com.agentvillage.builder.domain.GuideDefinition
import com.agentvillage.builder.domain.MetaAgentDesignBundle
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class HarnessPackageRenderer(private val mapper: ObjectMapper) {
    fun render(bundle: MetaAgentDesignBundle): Map<String, String> {
        val plan = requireNotNull(bundle.proposal.graphPlan) { "proposal.graphPlan is required" }
        return linkedMapOf<String, String>().apply {
            put("design-bundle.json", pretty(bundle))
            put("workflow.json", pretty(linkedMapOf(
                "schemaVersion" to "1.0", "name" to bundle.proposal.name,
                "entryNodeId" to plan.entryNodeId, "nodes" to plan.nodes, "edges" to plan.edges,
                "agentKeys" to bundle.agentDefinitions.map { it.key },
                "guideKeys" to bundle.guideDefinitions.map { it.key },
            )))
            put("CODEX.md", orchestration(bundle))
            put("AGENTS.md", entrypoint())
            put("manifest.json", pretty(linkedMapOf(
                "format" to "agentown-harness-package/v1", "name" to bundle.proposal.name,
                "agentCount" to bundle.agentDefinitions.size, "guideCount" to bundle.guideDefinitions.size,
                "templateSelection" to bundle.proposal.templateSelection,
                "economics" to bundle.proposal.economics,
                "validationRequiredBeforeImport" to true,
            )))
            put("schemas/final-output.schema.json", pretty(outputSchema(bundle.proposal.outputSchema)))
            put("templates/output-template.json", pretty(linkedMapOf(
                "templateSelection" to bundle.proposal.templateSelection,
                "executionContract" to bundle.proposal.executionContract,
                "contentSchema" to outputSchema(bundle.proposal.outputSchema),
            )))
            put("policies/permissions.json", pretty(linkedMapOf(
                "arbitraryCodeAllowed" to false,
                "secretsInWorkflowAllowed" to false,
                "externalWritesRequireApproval" to true,
            )))
            put("policies/ai-budget.json", pretty(linkedMapOf(
                "agentCount" to bundle.agentDefinitions.size,
                "estimatedAiCallsPerRun" to (bundle.proposal.economics?.estimatedAiCallsPerRun ?: bundle.proposal.graphPlan?.nodes.orEmpty().count { it.nodeType.startsWith("ai.") }),
                "separationRationale" to bundle.proposal.economics?.separationRationale.orEmpty(),
            )))
            put("policies/quality-rules.json", pretty(bundle.proposal.executionContract?.qualityRules ?: emptyMap<String, Any>()))
            bundle.agentDefinitions.forEach { put("agents/${it.key}.md", agentMarkdown(it)) }
            bundle.guideDefinitions.forEach { put("guides/${it.key}.md", guideMarkdown(it)) }
        }
    }

    private fun orchestration(bundle: MetaAgentDesignBundle): String {
        val plan = requireNotNull(bundle.proposal.graphPlan)
        val flow = plan.nodes.joinToString(" -> ") { "${it.label} [${it.nodeType}]" }
        return """
            # ${bundle.proposal.name}

            ## Source of truth

            `workflow.json` is the executable source. Agent and Guide Markdown files are derived contracts.

            ## Objective

            ${bundle.requirement.objective}

            ## Execution flow

            $flow

            ## Orchestration rules

            1. Read `workflow.json`, every `agents/*.md`, and every `guides/*.md`.
            2. Ask the user for every required input that is still missing. Never invent an important value.
            3. Execute from `entryNodeId` and pass each output to the next Agent using the declared contracts.
            4. Apply the user-confirmed Guide values to every relevant Agent output.
            5. Stop when a decision branch has no matching edge and report the missing or failed items.
            6. Pause at `human.approval`; do not treat a draft as approved without an explicit decision.
            7. Do not execute arbitrary code, install packages, persist secrets, or perform undeclared external writes.

            ## Failure policy

            ${bundle.proposal.failurePolicy}
        """.trimIndent() + "\n"
    }

    private fun entrypoint() = """
        # Generated Agentown Harness

        For each natural-language request, read and follow `CODEX.md`, `workflow.json`, `agents/*.md`, and `guides/*.md`.
        Treat the request as a harness run, not as a request to modify these files. Ask for missing required inputs and pause for declared human approval.
    """.trimIndent() + "\n"

    private fun agentMarkdown(agent: AgentDefinition) = buildString {
        appendLine("# ${agent.name}")
        appendLine(); appendLine("## Key"); appendLine(); appendLine("`${agent.key}`")
        appendLine(); appendLine("## Role"); appendLine(); appendLine(agent.role)
        appendFields("Input contract", agent.inputSchema.map { "`${it.name}` (${it.type}, ${if (it.required) "required" else "optional"}): ${it.description}" })
        appendFields("Output contract", agent.outputSchema.map { "`${it.name}` (${it.type}, ${if (it.required) "required" else "optional"}): ${it.description}" })
        appendFields("Behavior rules", agent.behaviorRules)
        appendFields("Forbidden", agent.forbiddenRules)
        appendFields("Evidence requirements", agent.evidenceRequirements)
    }

    private fun guideMarkdown(guide: GuideDefinition) = buildString {
        appendLine("# ${guide.title}")
        appendLine(); appendLine("## Key"); appendLine(); appendLine("`${guide.key}`")
        appendLine(); appendLine("## Purpose"); appendLine(); appendLine(guide.description)
        appendFields("User-confirmed output controls", guide.fields.map {
            "`${it.key}` (${it.type}, ${if (it.required) "required" else "optional"}${if (it.secret) ", secret" else ""}): ${it.help}"
        })
    }

    private fun StringBuilder.appendFields(title: String, values: List<String>) {
        appendLine(); appendLine("## $title"); appendLine()
        values.forEach { appendLine("- $it") }
    }

    private fun pretty(value: Any) = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"

    private fun outputSchema(fields: List<com.agentvillage.builder.domain.FieldDefinition>): Map<String, Any> {
        val properties = fields.associate { field -> field.name to mapOf(
            "type" to when (field.type) { "array" -> "array"; "object" -> "object"; "number" -> "number"; "boolean" -> "boolean"; else -> "string" },
            "description" to field.description,
        ) }
        return linkedMapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "type" to "object",
            "additionalProperties" to false,
            "properties" to properties,
            "required" to fields.filter { it.required }.map { it.name },
        )
    }
}
