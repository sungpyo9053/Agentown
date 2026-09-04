package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AgentGenerationDraftEntity
import com.agentvillage.builder.domain.AgentGenerationDraftState
import com.agentvillage.builder.domain.MetaAgentDesignBundle
import com.agentvillage.builder.domain.ValidationIssue
import com.agentvillage.builder.infrastructure.AgentGenerationDraftRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Durable NL2Agent state. Every mutation commits independently from the long-running
 * model request, so a timeout cannot erase the source request or the last valid draft.
 */
@Service
class AgentGenerationDraftService(
    private val drafts: AgentGenerationDraftRepository,
    private val mapper: ObjectMapper,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun start(context: PipelineContext, instruction: String, mode: StructuredMetaAgentPipeline.DesignMode) {
        val draft = drafts.findByConversationId(context.conversationId)?.apply {
            sourceInstruction = instruction
            designMode = mode.name
            state = AgentGenerationDraftState.STARTED
            attempt = 0
            bundleJson = null
            validationIssuesJson = emptyList()
            errorMessage = null
        } ?: AgentGenerationDraftEntity(
            conversationId = context.conversationId,
            workflowId = context.workflowId,
            sourceInstruction = instruction,
            designMode = mode.name,
        )
        drafts.save(draft)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun checkpoint(conversationId: UUID, bundle: MetaAgentDesignBundle) {
        val draft = requireNotNull(drafts.findByConversationId(conversationId)) { "Agent generation draft was not started" }
        draft.bundleJson = mapper.convertValue(bundle, object : TypeReference<Map<String, Any?>>() {})
        draft.attempt += 1
        draft.state = AgentGenerationDraftState.GENERATED
        draft.validationIssuesJson = emptyList()
        draft.errorMessage = null
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun reloadBundle(conversationId: UUID): MetaAgentDesignBundle {
        val json = requireNotNull(drafts.findByConversationId(conversationId)?.bundleJson) { "Persisted agent generation bundle is missing" }
        return mapper.convertValue(json, MetaAgentDesignBundle::class.java)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun validationFailed(conversationId: UUID, issues: List<ValidationIssue>) {
        drafts.findByConversationId(conversationId)?.apply {
            state = AgentGenerationDraftState.VALIDATION_FAILED
            validationIssuesJson = mapper.convertValue(issues, object : TypeReference<List<Map<String, Any?>>>() {})
            errorMessage = issues.joinToString(" ") { it.message }.take(500)
        }
    }

    @Transactional
    fun complete(conversationId: UUID) {
        val draft = requireNotNull(drafts.findByConversationId(conversationId))
        require(draft.bundleJson != null && draft.validationIssuesJson.isEmpty()) { "Only a persisted validated draft can complete" }
        draft.state = AgentGenerationDraftState.COMPLETED
        draft.errorMessage = null
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(conversationId: UUID, message: String) {
        drafts.findByConversationId(conversationId)?.apply {
            state = AgentGenerationDraftState.FAILED
            errorMessage = message.take(500)
        }
    }
}
