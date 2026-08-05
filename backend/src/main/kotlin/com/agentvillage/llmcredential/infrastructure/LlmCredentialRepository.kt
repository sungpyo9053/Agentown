package com.agentvillage.llmcredential.infrastructure

import com.agentvillage.llmcredential.domain.LlmCredential
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmCredentialRepository : JpaRepository<LlmCredential, UUID> {
    fun findAllByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<LlmCredential>
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): LlmCredential?
}

