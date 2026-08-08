package com.agentvillage.execution.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class ExecutionMetrics(registry: MeterRegistry) {
    private val queued = Counter.builder("agentown.execution.queued").description("Queued harness executions").register(registry)
    private val started = Counter.builder("agentown.execution.started").description("Started harness executions").register(registry)
    private val succeeded = Counter.builder("agentown.execution.completed").tag("outcome", "succeeded").register(registry)
    private val failed = Counter.builder("agentown.execution.completed").tag("outcome", "failed").register(registry)
    private val timedOut = Counter.builder("agentown.execution.completed").tag("outcome", "timeout").register(registry)
    private val duration = Timer.builder("agentown.execution.duration").description("Harness execution wall time").register(registry)

    fun queued() = queued.increment()
    fun started() = started.increment()
    fun completed(startedAt: Instant?, outcome: String) {
        when (outcome) {
            "SUCCEEDED" -> succeeded.increment()
            "TIMEOUT" -> timedOut.increment()
            else -> failed.increment()
        }
        startedAt?.let { duration.record(Duration.between(it, Instant.now()).coerceAtLeast(Duration.ZERO)) }
    }
}
