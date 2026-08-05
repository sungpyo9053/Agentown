package com.agentvillage.agent.infrastructure

import com.agentvillage.agent.domain.Agent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentRepository : JpaRepository<Agent, UUID> {
    fun findAllByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<Agent>
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): Agent?
    fun existsByIdAndOwnerId(id: UUID, ownerId: UUID): Boolean
}

