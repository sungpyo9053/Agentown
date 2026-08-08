package com.agentvillage.execution

import com.agentvillage.execution.application.ExecutionProcessor
import com.agentvillage.execution.application.ExecutionQueueWorker
import com.agentvillage.execution.domain.Execution
import com.agentvillage.execution.domain.ExecutionStatus
import com.agentvillage.execution.infrastructure.ExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class ExecutionQueueRecoveryTest {
    @Test
    fun `stale running execution is returned to durable queue after restart`() {
        val repository = mock<ExecutionRepository>()
        val processor = mock<ExecutionProcessor>()
        val execution = Execution(
            harnessId = UUID.randomUUID(), harnessVersionId = UUID.randomUUID(), ownerId = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID().toString(), inputJson = emptyMap(),
            status = ExecutionStatus.RUNNING, heartbeatAt = Instant.now().minusSeconds(600),
        )
        whenever(repository.findAllByStatusAndHeartbeatAtBefore(any(), any())).thenReturn(listOf(execution))
        val worker = ExecutionQueueWorker(repository, processor)
        try {
            worker.recover()
            assertThat(execution.status).isEqualTo(ExecutionStatus.QUEUED)
        } finally {
            worker.shutdown()
        }
    }
}
