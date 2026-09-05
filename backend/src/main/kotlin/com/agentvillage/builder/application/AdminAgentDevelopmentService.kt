package com.agentvillage.builder.application

import com.agentvillage.builder.infrastructure.BuilderActivityEventRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

data class AgentDevelopmentMetrics(
    val windowDays: Int,
    val from: Instant,
    val to: Instant,
    val naturalLanguageInputs: Long,
    val generations: StatusMetrics,
    val runs: RunMetrics,
    val versionsCreated: Long,
    val packageDownloads: Long,
    val connectorEvents: List<ConnectorMetrics>,
    val tokens: TokenMetrics,
    val estimatedCost: BigDecimal?,
    val funnel: FunnelMetrics,
    val recentInputs: List<NaturalLanguageInputView>,
    val privacy: PrivacySummary = PrivacySummary(),
)

data class StatusMetrics(val total: Long, val succeeded: Long, val failed: Long, val cancelled: Long, val successRate: Double?)
data class RunMetrics(val total: Long, val succeeded: Long, val failed: Long, val pending: Long, val reused: Long, val reuseRate: Double?, val successRate: Double?)
data class ConnectorMetrics(val connector: String, val attempts: Long, val succeeded: Long, val failed: Long, val mode: String)
data class TokenMetrics(val input: Long, val output: Long, val total: Long)
data class FunnelMetrics(
    val developViews: Long,
    val guidedRequests: Long,
    val examplesSelected: Long,
    val sessionsCreated: Long,
    val generationRequests: Long,
    val successfulRuns: Long,
    val packageDownloads: Long,
    val upgradeViews: Long,
    val activeWorkspaces: Long,
)
data class NaturalLanguageInputView(val workspaceAlias: String, val instruction: String, val status: String, val createdAt: Instant)
data class PrivacySummary(
    val rawNaturalLanguageStored: Boolean = true,
    val rawContentExposedToAdmin: Boolean = true,
    val aggregateIdentityMode: String = "PSEUDONYMOUS",
)
data class AgentDevelopmentActivityView(
    val id: String,
    val workspaceAlias: String,
    val eventType: String,
    val targetType: String?,
    val targetAlias: String?,
    val outcome: String,
    val httpStatus: Int,
    val durationMs: Long,
    val createdAt: Instant,
)

@Service
class AdminAgentDevelopmentService(
    private val jdbc: JdbcTemplate,
    private val activityEvents: BuilderActivityEventRepository,
) {
    @Transactional(readOnly = true)
    fun metrics(requestedDays: Int): AgentDevelopmentMetrics {
        val days = requestedDays.coerceIn(1, 90)
        val to = Instant.now()
        val from = to.minus(days.toLong(), ChronoUnit.DAYS)
        val fromTimestamp = Timestamp.from(from)
        val generation = jdbc.queryForMap(
            """
            select count(*) total,
                   count(*) filter (where job.status = 'SUCCEEDED') succeeded,
                   count(*) filter (where job.status = 'FAILED') failed,
                   count(*) filter (where job.status = 'CANCELLED') cancelled
            from builder_generation_jobs job
            join builder_conversations conversation on conversation.id = job.conversation_id
            where conversation.purpose = 'AGENT_DEVELOPMENT' and job.created_at >= ?
            """.trimIndent(), fromTimestamp,
        )
        val run = jdbc.queryForMap(
            """
            select count(*) total,
                   count(*) filter (where run.status = 'SUCCEEDED') succeeded,
                   count(*) filter (where run.status in ('FAILED', 'AMBIGUOUS')) failed,
                   count(*) filter (where run.status not in ('SUCCEEDED', 'FAILED', 'AMBIGUOUS')) pending,
                   count(*) filter (where exists (
                       select 1 from builder_runs older
                       where older.workflow_id = run.workflow_id and older.created_at < run.created_at
                   )) reused,
                   coalesce(sum(run.input_tokens), 0) input_tokens,
                   coalesce(sum(run.output_tokens), 0) output_tokens
            from builder_runs run
            join builder_workflows workflow on workflow.id = run.workflow_id
            join builder_conversations conversation on conversation.id = workflow.conversation_id
            where conversation.purpose = 'AGENT_DEVELOPMENT' and run.created_at >= ?
            """.trimIndent(), fromTimestamp,
        )
        val connectors = jdbc.query(
            """
            select step.node_type,
                   count(*) attempts,
                   count(*) filter (where step.status = 'SUCCEEDED') succeeded,
                   count(*) filter (where step.status = 'FAILED') failed
            from builder_step_runs step
            join builder_runs run on run.id = step.run_id
            join builder_workflows workflow on workflow.id = run.workflow_id
            join builder_conversations conversation on conversation.id = workflow.conversation_id
            where conversation.purpose = 'AGENT_DEVELOPMENT'
              and step.created_at >= ?
              and (step.node_type like 'slack.%' or step.node_type like 'email.%'
                   or step.node_type like 'notion.%' or step.node_type like 'github.%'
                   or step.node_type like 'news.%' or step.node_type like 'flight.%'
                   or step.node_type like 'knowledge.%')
            group by step.node_type order by attempts desc, step.node_type
            """.trimIndent(),
            { rs, _ -> ConnectorMetrics(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), if (rs.getString(1).endsWith(".mock")) "MOCK" else "LIVE") },
            fromTimestamp,
        )
        val generationTotal = generation.long("total")
        val generationSucceeded = generation.long("succeeded")
        val generationFailed = generation.long("failed")
        val generationCancelled = generation.long("cancelled")
        val runTotal = run.long("total")
        val runSucceeded = run.long("succeeded")
        val runFailed = run.long("failed")
        val runPending = run.long("pending")
        val reused = run.long("reused")
        val inputTokens = run.long("input_tokens")
        val outputTokens = run.long("output_tokens")
        val recentInputs = jdbc.query(
            """
            select conversation.workspace_id, job.instruction, job.status, job.created_at
            from builder_generation_jobs job
            join builder_conversations conversation on conversation.id = job.conversation_id
            where conversation.purpose = 'AGENT_DEVELOPMENT' and job.created_at >= ?
            order by job.created_at desc
            limit 200
            """.trimIndent(),
            { rs, _ -> NaturalLanguageInputView(
                workspaceAlias = "workspace-${alias(rs.getString(1))}",
                instruction = rs.getString(2),
                status = rs.getString(3),
                createdAt = rs.getTimestamp(4).toInstant(),
            ) },
            fromTimestamp,
        )
        fun eventCount(type: String) = jdbc.queryForObject(
            "select count(*) from builder_activity_events where event_type = ? and outcome = 'SUCCEEDED' and created_at >= ?",
            Long::class.java, type, fromTimestamp,
        ) ?: 0
        val packageDownloads = eventCount("PACKAGE_DOWNLOADED")
        val funnel = FunnelMetrics(
            developViews = eventCount("DEVELOP_VIEWED"),
            guidedRequests = eventCount("GUIDED_REQUEST_COMPOSED"),
            examplesSelected = eventCount("EXAMPLE_SELECTED"),
            sessionsCreated = eventCount("SESSION_CREATED"),
            generationRequests = eventCount("GENERATION_REQUESTED"),
            successfulRuns = runSucceeded,
            packageDownloads = packageDownloads,
            upgradeViews = eventCount("UPGRADE_VIEWED"),
            activeWorkspaces = jdbc.queryForObject("select count(distinct workspace_id) from builder_activity_events where created_at >= ?", Long::class.java, fromTimestamp) ?: 0,
        )
        return AgentDevelopmentMetrics(
            windowDays = days,
            from = from,
            to = to,
            naturalLanguageInputs = generationTotal,
            generations = StatusMetrics(generationTotal, generationSucceeded, generationFailed, generationCancelled, rate(generationSucceeded, generationSucceeded + generationFailed)),
            runs = RunMetrics(runTotal, runSucceeded, runFailed, runPending, reused, rate(reused, runTotal), rate(runSucceeded, runSucceeded + runFailed)),
            versionsCreated = count("""select count(*) from builder_workflow_versions version join builder_workflows workflow on workflow.id = version.workflow_id join builder_conversations conversation on conversation.id = workflow.conversation_id where conversation.purpose = 'AGENT_DEVELOPMENT' and version.created_at >= ?""", fromTimestamp),
            packageDownloads = packageDownloads,
            connectorEvents = connectors,
            tokens = TokenMetrics(inputTokens, outputTokens, inputTokens + outputTokens),
            estimatedCost = null,
            funnel = funnel,
            recentInputs = recentInputs,
        )
    }

    @Transactional(readOnly = true)
    fun activities() = activityEvents.findTop200ByOrderByCreatedAtDesc().map {
        AgentDevelopmentActivityView(
            id = alias(it.id.toString()),
            workspaceAlias = "workspace-${alias(it.workspaceId.toString())}",
            eventType = it.eventType,
            targetType = it.targetType,
            targetAlias = it.targetId?.let { id -> alias(id.toString()) },
            outcome = it.outcome,
            httpStatus = it.httpStatus,
            durationMs = it.durationMs,
            createdAt = it.createdAt,
        )
    }

    private fun count(sql: String, from: Timestamp) = jdbc.queryForObject(sql, Long::class.java, from) ?: 0
    private fun rate(numerator: Long, denominator: Long): Double? = if (denominator == 0L) null else BigDecimal(numerator * 100).divide(BigDecimal(denominator), 1, RoundingMode.HALF_UP).toDouble()
    private fun Map<String, Any?>.long(key: String) = (get(key) as Number).toLong()
    private fun alias(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
