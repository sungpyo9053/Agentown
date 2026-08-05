package com.agentvillage.execution.application

import com.agentvillage.execution.domain.ExecutionEvent
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class ExecutionEventStream {
    private val emitters = ConcurrentHashMap<UUID, MutableSet<SseEmitter>>()
    fun subscribe(executionId: UUID, history: List<ExecutionEvent>): SseEmitter {
        val emitter = SseEmitter(30 * 60 * 1000L)
        history.forEach { emitter.send(SseEmitter.event().id(it.sequenceNo.toString()).name(it.eventType).data(it)) }
        emitters.computeIfAbsent(executionId) { ConcurrentHashMap.newKeySet() }.add(emitter)
        fun remove() { emitters[executionId]?.remove(emitter) }
        emitter.onCompletion(::remove); emitter.onTimeout(::remove); emitter.onError { remove() }
        return emitter
    }
    fun publish(event: ExecutionEvent) {
        emitters[event.executionId]?.toList()?.forEach { emitter ->
            runCatching { emitter.send(SseEmitter.event().id(event.sequenceNo.toString()).name(event.eventType).data(event)) }
                .onFailure { emitters[event.executionId]?.remove(emitter) }
        }
    }
}
