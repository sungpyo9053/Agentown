package com.agentvillage.agent.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "agent_definitions")
class AgentDefinition(
    @Id @Column(name = "agent_id") val agentId: UUID,
    @Column(name = "task_description", nullable = false, columnDefinition = "text") var taskDescription: String,
    @Column(name = "desired_output", nullable = false, columnDefinition = "text") var desiredOutput: String,
    @Column(nullable = false, columnDefinition = "text") var prohibitions: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_schema", nullable = false, columnDefinition = "jsonb")
    var inputSchema: Map<String, Any>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_schema", nullable = false, columnDefinition = "jsonb")
    var outputSchema: Map<String, Any>,
    @Column(name = "agent_markdown", nullable = false, columnDefinition = "text") var agentMarkdown: String,
    @Column(name = "guide_markdown", nullable = false, columnDefinition = "text") var guideMarkdown: String,
    @Column(name = "generated_at", nullable = false) var generatedAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)
