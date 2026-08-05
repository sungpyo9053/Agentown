package com.agentvillage.agent.infrastructure

import com.agentvillage.agent.domain.AgentDefinition
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentDefinitionRepository : JpaRepository<AgentDefinition, UUID>
