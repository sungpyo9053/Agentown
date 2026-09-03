package com.agentvillage.builder.infrastructure

import com.agentvillage.builder.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

interface BuilderWorkspaceRepository : JpaRepository<BuilderWorkspace, UUID> { fun findByOwnerId(ownerId: UUID): BuilderWorkspace? }
interface BuilderConversationRepository : JpaRepository<BuilderConversation, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): BuilderConversation?
    fun findByWorkspaceIdAndIdempotencyKey(workspaceId: UUID, idempotencyKey: String): BuilderConversation?
    fun findTop20ByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<BuilderConversation>
}
interface BuilderMessageRepository : JpaRepository<BuilderMessage, UUID> {
    fun findAllByConversationIdOrderByCreatedAt(conversationId: UUID): List<BuilderMessage>
    fun findByConversationIdAndIdempotencyKey(conversationId: UUID, idempotencyKey: String): BuilderMessage?
}
interface BuilderRequirementRepository : JpaRepository<BuilderRequirementEntity, UUID> { fun findByConversationId(conversationId: UUID): BuilderRequirementEntity? }
interface BuilderProposalRepository : JpaRepository<BuilderProposalEntity, UUID> { fun findByConversationId(conversationId: UUID): BuilderProposalEntity? }
interface BuilderWorkflowRepository : JpaRepository<BuilderWorkflow, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): BuilderWorkflow?
    fun findByConversationId(conversationId: UUID): BuilderWorkflow?
    fun findAllByWorkspaceIdAndStatusOrderByUpdatedAtDesc(workspaceId: UUID, status: WorkflowStatus): List<BuilderWorkflow>
}
interface BuilderWorkflowVersionRepository : JpaRepository<BuilderWorkflowVersion, UUID> {
    fun findAllByWorkflowIdOrderByVersionNoDesc(workflowId: UUID): List<BuilderWorkflowVersion>
    fun findByIdAndWorkflowId(id: UUID, workflowId: UUID): BuilderWorkflowVersion?
}
interface BuilderApprovalRepository : JpaRepository<BuilderApproval, UUID> {
    fun findByWorkspaceIdAndIdempotencyKey(workspaceId: UUID, idempotencyKey: String): BuilderApproval?
    fun findByRunIdAndStatus(runId: UUID, status: ApprovalStatus): BuilderApproval?
}
interface BuilderRunRepository : JpaRepository<BuilderRun, UUID> {
    fun findByWorkspaceIdAndIdempotencyKey(workspaceId: UUID, idempotencyKey: String): BuilderRun?
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): BuilderRun?
    fun findFirstByWorkflowIdAndWorkflowVersionIdAndStatusOrderByUpdatedAtDesc(workflowId: UUID, workflowVersionId: UUID, status: BuilderRunStatus): BuilderRun?
    fun findTop20ByWorkspaceIdAndWorkflowIdAndRunModeOrderByCreatedAtDesc(
        workspaceId: UUID,
        workflowId: UUID,
        runMode: BuilderRunMode,
    ): List<BuilderRun>
    @Query(
        value = """
            select run.id
            from builder_runs run
            join notion_page_write_requests request on request.id = run.external_write_request_id
            where run.run_mode = 'PRODUCTION'
              and run.status = 'PUBLISHING'
              and run.updated_at < :staleBefore
              and request.status = 'PUBLISHING'
              and request.updated_at < :staleBefore
              and exists (
                  select 1 from builder_step_runs step
                  where step.run_id = run.id
                    and step.node_type = 'notion.create_page'
                    and step.status = 'RUNNING'
              )
            order by run.updated_at asc, run.id asc
            limit :batchSize
        """,
        nativeQuery = true,
    )
    fun findStaleIds(
        staleBefore: Instant,
        batchSize: Int,
    ): List<UUID>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from BuilderRun run where run.id = :id")
    fun findForUpdate(id: UUID): BuilderRun?
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BuilderRun run set run.status = :next, run.attemptCount = run.attemptCount + 1 where run.id = :id and run.status = :expected")
    fun claim(id: UUID, expected: BuilderRunStatus, next: BuilderRunStatus): Int
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BuilderRun run set run.status = :next where run.id = :id and run.status = :expected")
    fun transition(id: UUID, expected: BuilderRunStatus, next: BuilderRunStatus): Int
}
interface BuilderStepRunRepository : JpaRepository<BuilderStepRun, UUID> { fun findAllByRunIdOrderBySequenceNo(runId: UUID): List<BuilderStepRun> }
interface MetaAgentRunRepository : JpaRepository<MetaAgentRun, UUID> {
    fun findAllByWorkflowIdOrderByCreatedAtAsc(workflowId: UUID): List<MetaAgentRun>
}
interface BuilderGenerationJobRepository : JpaRepository<BuilderGenerationJob, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): BuilderGenerationJob?
    fun findByWorkspaceIdAndIdempotencyKey(workspaceId: UUID, idempotencyKey: String): BuilderGenerationJob?
    fun findFirstByWorkspaceIdAndConversationIdAndStatusInOrderByCreatedAtDesc(
        workspaceId: UUID,
        conversationId: UUID,
        statuses: Collection<BuilderGenerationStatus>,
    ): BuilderGenerationJob?
}
interface BuilderUsageRecordRepository : JpaRepository<BuilderUsageRecord, UUID> {
    fun findByOwnerIdAndIdempotencyKey(ownerId: UUID, idempotencyKey: String): BuilderUsageRecord?
}
interface BuilderAutomationTeamRepository : JpaRepository<BuilderAutomationTeam, UUID> {
    fun findByWorkflowVersionId(workflowVersionId: UUID): BuilderAutomationTeam?
    fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<BuilderAutomationTeam>
}
interface BuilderAutomationTeamMemberRepository : JpaRepository<BuilderAutomationTeamMember, UUID> {
    fun findAllByTeamIdOrderBySequenceNo(teamId: UUID): List<BuilderAutomationTeamMember>
}
