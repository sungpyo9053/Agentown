package com.agentvillage.builder.infrastructure

import com.agentvillage.builder.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BuilderWorkspaceRepository : JpaRepository<BuilderWorkspace, UUID> { fun findByOwnerId(ownerId: UUID): BuilderWorkspace? }
interface BuilderConversationRepository : JpaRepository<BuilderConversation, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): BuilderConversation?
    fun findByWorkspaceIdAndIdempotencyKey(workspaceId: UUID, idempotencyKey: String): BuilderConversation?
    fun findTop20ByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<BuilderConversation>
    fun findTop20ByWorkspaceIdAndPurposeOrderByCreatedAtDesc(workspaceId: UUID, purpose: BuilderConversationPurpose): List<BuilderConversation>
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
}
interface BuilderStepRunRepository : JpaRepository<BuilderStepRun, UUID> { fun findAllByRunIdOrderBySequenceNo(runId: UUID): List<BuilderStepRun> }
interface MetaAgentRunRepository : JpaRepository<MetaAgentRun, UUID> {
    fun findAllByWorkflowIdOrderByCreatedAtAsc(workflowId: UUID): List<MetaAgentRun>
}
interface BuilderGenerationJobRepository : JpaRepository<BuilderGenerationJob, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): BuilderGenerationJob?
    fun findByWorkspaceIdAndIdempotencyKey(workspaceId: UUID, idempotencyKey: String): BuilderGenerationJob?
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
