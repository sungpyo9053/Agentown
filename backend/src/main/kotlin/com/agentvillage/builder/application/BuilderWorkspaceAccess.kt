package com.agentvillage.builder.application

import com.agentvillage.builder.domain.BuilderWorkspace
import com.agentvillage.builder.infrastructure.BuilderWorkspaceRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@org.springframework.modulith.NamedInterface("application")
interface BuilderWorkspaceAccess {
    fun findWorkspaceId(ownerId: UUID): UUID?
    fun requireWorkspaceId(ownerId: UUID): UUID
}

@Component
class JpaBuilderWorkspaceAccess(private val workspaces: BuilderWorkspaceRepository) : BuilderWorkspaceAccess {
    @Transactional(readOnly = true)
    override fun findWorkspaceId(ownerId: UUID): UUID? = workspaces.findByOwnerId(ownerId)?.id

    @Transactional
    override fun requireWorkspaceId(ownerId: UUID): UUID =
        (workspaces.findByOwnerId(ownerId) ?: workspaces.save(BuilderWorkspace(ownerId = ownerId))).id
}
