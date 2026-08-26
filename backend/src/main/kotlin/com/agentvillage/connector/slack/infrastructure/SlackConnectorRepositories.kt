package com.agentvillage.connector.slack.infrastructure

import com.agentvillage.connector.slack.domain.*
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import java.util.UUID

interface ConnectorOauthStateRepository : JpaRepository<ConnectorOauthState, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByStateHash(stateHash: String): ConnectorOauthState?
}
interface ConnectorConnectionRepository : JpaRepository<ConnectorConnection, UUID> {
    fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<ConnectorConnection>
    fun findAllByWorkspaceIdAndProviderOrderByCreatedAtDesc(workspaceId: UUID, provider: ConnectorProvider): List<ConnectorConnection>
    fun findByWorkspaceIdAndProviderAndExternalAccountId(workspaceId: UUID, provider: ConnectorProvider, externalAccountId: String): ConnectorConnection?
    fun findFirstByProviderAndExternalAccountIdAndStatus(provider: ConnectorProvider, externalAccountId: String, status: ConnectorStatus): ConnectorConnection?
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): ConnectorConnection?
}
interface ConnectorEventRepository : JpaRepository<ConnectorEvent, UUID> {
    fun existsByProviderEventId(providerEventId: String): Boolean
    fun findAllByConnectionIdOrderByReceivedAtDesc(connectionId: UUID): List<ConnectorEvent>
}
