package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.domain.BuilderRunStatus
import com.agentvillage.builder.domain.WorkflowStatus
import com.agentvillage.common.exception.ApiException
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class AutomationIntentCorpusTest : IntegrationTestSupport() {
    @Autowired lateinit var service: BuilderService
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper

    data class CorpusRow(
        val id: String,
        val category: String,
        val sourceUrl: String,
        val concreteExpected: String,
        val minAgents: Int,
        val concreteInstruction: String,
        val vagueInstruction: String,
    )

    data class CaseResult(
        val id: String,
        val category: String,
        val variant: String,
        val expected: String,
        val actual: String,
        val passed: Boolean,
        val agentCount: Int = 0,
        val nodeTypes: List<String> = emptyList(),
        val errorCode: String? = null,
        val detail: String? = null,
    )

    @Test
    fun `one hundred researched automation intents preserve meaning and execution contracts`() {
        val rows = loadCorpus()
        assertThat(rows).hasSize(50)
        val results = rows.flatMap { row ->
            listOf(
                evaluate("${row.id}-A", row, "CONCRETE", row.concreteExpected, row.concreteInstruction, row.minAgents),
                evaluate("${row.id}-B", row, "VAGUE", "CLARIFY", row.vagueInstruction, 0),
            )
        }
        writeReport(results)

        assertThat(results).hasSize(100)
        assertThat(results.filterNot { it.passed })
            .withFailMessage(results.filterNot { it.passed }.joinToString("\n") { "${it.id} ${it.category}: expected=${it.expected} actual=${it.actual} code=${it.errorCode} detail=${it.detail}" })
            .isEmpty()
        assertThat(results.count { it.actual == "DESIGN" }).isEqualTo(25)
        assertThat(results.count { it.actual == "CLARIFY" }).isEqualTo(51)
        assertThat(results.count { it.actual == "CAPABILITY_REQUIRED" }).isEqualTo(24)
    }

    private fun evaluate(id: String, row: CorpusRow, variant: String, expected: String, instruction: String, minAgents: Int): CaseResult {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("corpus-$suffix@example.com", "password123", "corpus_$suffix", "TC ${row.id}"))
        val initial = service.createConversation(owner.id, "corpus-conversation-$suffix")
        return try {
            var snapshot = service.sendMessage(owner.id, initial.conversationId, instruction, "corpus-message-$suffix")
            if (snapshot.status == WorkflowStatus.NEEDS_CLARIFICATION) {
                return CaseResult(id, row.category, variant, expected, "CLARIFY", expected == "CLARIFY", detail = snapshot.clarificationQuestions.joinToString { it.field })
            }
            val plan = snapshot.proposal?.graphPlan
            val meaningPreserved = plan != null && snapshot.agentDefinitions.size >= minAgents &&
                (instruction.contains("Slack", true) || plan.nodes.none { it.nodeType.startsWith("slack.") }) &&
                (instruction.contains("Notion", true) || instruction.contains("FAQ", true) || plan.nodes.none { it.nodeType.startsWith("notion.") })
            if (!meaningPreserved) {
                return CaseResult(id, row.category, variant, expected, "WRONG_TEMPLATE", false, snapshot.agentDefinitions.size, plan?.nodes?.map { it.nodeType }.orEmpty())
            }
            snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "corpus-design-$suffix")
            if (snapshot.validation?.valid != true) {
                return CaseResult(id, row.category, variant, expected, "INVALID_GRAPH", false, snapshot.agentDefinitions.size, snapshot.graph?.nodes?.map { it.nodeType }.orEmpty(), detail = snapshot.validation?.issues?.joinToString { it.code })
            }
            var run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("text" to "검증용 입력", "message" to "검증용 입력"), "corpus-run-$suffix")
            if (run.status == BuilderRunStatus.WAITING_APPROVAL) {
                run = service.decideExecution(owner.id, run.id, true, "corpus-execution-$suffix")
            }
            val passed = expected == "DESIGN" && run.status == BuilderRunStatus.SUCCEEDED && run.requirementMatched == true
            CaseResult(id, row.category, variant, expected, if (run.status == BuilderRunStatus.SUCCEEDED) "DESIGN" else "EXECUTION_FAIL", passed, snapshot.agentDefinitions.size, snapshot.graph?.nodes?.map { it.nodeType }.orEmpty(), detail = "run=${run.status}, requirementMatched=${run.requirementMatched}")
        } catch (exception: ApiException) {
            val actual = if (exception.code == "AUTOMATION_CAPABILITY_REQUIRED") "CAPABILITY_REQUIRED" else "REJECT"
            val normalizedExpected = if (expected == "REJECT") "CAPABILITY_REQUIRED" else expected
            CaseResult(id, row.category, variant, normalizedExpected, actual, normalizedExpected == actual, errorCode = exception.code, detail = exception.message)
        } catch (exception: Exception) {
            CaseResult(id, row.category, variant, expected, "ERROR", false, errorCode = exception::class.simpleName, detail = exception.message)
        }
    }

    private fun loadCorpus(): List<CorpusRow> {
        val lines = requireNotNull(javaClass.getResourceAsStream("/builder/automation-intent-corpus.tsv"))
            .bufferedReader().use { it.readLines() }.filter(String::isNotBlank)
        return lines.drop(1).map { line ->
            val columns = line.split('\t')
            require(columns.size == 7) { "Invalid corpus row: $line" }
            CorpusRow(columns[0], columns[1], columns[2], columns[3], columns[4].toInt(), columns[5], columns[6])
        }
    }

    private fun writeReport(results: List<CaseResult>) {
        val target = Path.of("build/reports/automation-intent-corpus.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), mapOf(
            "total" to results.size,
            "passed" to results.count { it.passed },
            "failed" to results.count { !it.passed },
            "results" to results,
        ))
    }
}
