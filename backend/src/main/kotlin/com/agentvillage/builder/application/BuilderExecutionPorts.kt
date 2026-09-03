package com.agentvillage.builder.application

import java.time.Instant
import java.util.UUID

@org.springframework.modulith.NamedInterface("application")
data class ExternalWriteResult(
    val succeeded: Boolean,
    val ambiguous: Boolean = false,
    val externalId: String? = null,
    val externalUrl: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
)

@org.springframework.modulith.NamedInterface("application")
interface BuilderNotionExecutionPort {
    fun requireWritableConnection(ownerId: UUID, connectionId: UUID)
    fun previewPage(ownerId: UUID, connectionId: UUID, idempotencyKey: String, parentPageId: String, title: String, paragraphs: List<String>): UUID
    fun approvePage(ownerId: UUID, requestId: UUID, idempotencyKey: String): ExternalWriteResult
    fun reconcileStalePublishing(workspaceId: UUID, requestId: UUID, staleBefore: Instant): Boolean
}
