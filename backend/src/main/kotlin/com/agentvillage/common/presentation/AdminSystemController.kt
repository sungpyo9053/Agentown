package com.agentvillage.common.presentation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import java.time.Instant

data class AdminSystemStatus(
    val status: String,
    val checkedAt: Instant,
    val uptimeSeconds: Long,
    val processors: Int,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val workerMode: String,
)

@RestController
@RequestMapping("/api/admin/system")
class AdminSystemController {
    @GetMapping
    fun status(): AdminSystemStatus {
        val runtime = Runtime.getRuntime()
        return AdminSystemStatus(
            status = "UP",
            checkedAt = Instant.now(),
            uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000,
            processors = runtime.availableProcessors(),
            heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            heapMaxMb = runtime.maxMemory() / 1024 / 1024,
            workerMode = "LOCAL_COROUTINE",
        )
    }
}
