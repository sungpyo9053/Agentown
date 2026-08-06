package com.agentvillage.harness.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.harness.domain.HarnessStatus
import com.agentvillage.harness.infrastructure.HarnessRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class AdminHarnessView(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val visibility: String,
    val status: HarnessStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminHarnessSummary(val total: Long, val draft: Long, val published: Long, val blocked: Long)

@Service
class AdminHarnessService(private val harnesses: HarnessRepository) {
    @Transactional(readOnly = true)
    fun list() = harnesses.findTop100ByOrderByCreatedAtDesc().map {
        AdminHarnessView(it.id, it.ownerId, it.name, it.visibility.name, it.status, it.createdAt, it.updatedAt)
    }

    @Transactional(readOnly = true)
    fun summary() = AdminHarnessSummary(
        harnesses.count(),
        harnesses.countByStatus(HarnessStatus.DRAFT),
        harnesses.countByStatus(HarnessStatus.PUBLISHED),
        harnesses.countByStatus(HarnessStatus.BLOCKED),
    )

    @Transactional
    fun changeStatus(id: UUID, status: HarnessStatus): AdminHarnessView {
        if (status !in setOf(HarnessStatus.BLOCKED, HarnessStatus.DRAFT, HarnessStatus.DEPRECATED)) {
            throw BadRequestException("ADMIN_HARNESS_STATUS_INVALID", "관리자 화면에서는 차단, 차단 해제 또는 폐기만 할 수 있습니다.")
        }
        val harness = harnesses.findById(id).orElseThrow {
            NotFoundException("HARNESS_NOT_FOUND", "하네스를 찾을 수 없습니다.")
        }
        harness.status = status
        return AdminHarnessView(
            harness.id, harness.ownerId, harness.name, harness.visibility.name,
            harness.status, harness.createdAt, harness.updatedAt,
        )
    }
}
