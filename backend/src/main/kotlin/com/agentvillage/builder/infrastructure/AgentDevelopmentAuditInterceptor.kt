package com.agentvillage.builder.infrastructure

import com.agentvillage.builder.domain.BuilderActivityEvent
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

@Component
class AgentDevelopmentActivityRecorder(
    private val workspaces: BuilderWorkspaceRepository,
    private val events: BuilderActivityEventRepository,
) {
    private val engagementEvents = setOf("DEVELOP_VIEWED", "GUIDED_REQUEST_COMPOSED", "EXAMPLE_SELECTED", "UPGRADE_VIEWED")

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordEngagement(user: AuthenticatedUser, eventType: String) {
        require(eventType in engagementEvents) { "Unsupported engagement event" }
        val workspace = workspaces.findByOwnerId(user.userId) ?: return
        events.save(BuilderActivityEvent(workspaceId = workspace.id, eventType = eventType, outcome = "SUCCEEDED", httpStatus = 202, durationMs = 0))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(user: AuthenticatedUser, request: HttpServletRequest, response: HttpServletResponse) {
        val workspace = workspaces.findByOwnerId(user.userId) ?: return
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: request.requestURI
        val target = UUID_PATTERN.find(request.requestURI)?.value?.let(UUID::fromString)
        val status = response.status
        events.save(
            BuilderActivityEvent(
                workspaceId = workspace.id,
                eventType = eventType(request.method, pattern),
                targetType = targetType(pattern),
                targetId = target,
                outcome = if (status < 400) "SUCCEEDED" else "FAILED",
                httpStatus = status,
                durationMs = ((System.nanoTime() - (request.getAttribute(START_NANOS) as? Long ?: System.nanoTime())) / 1_000_000).coerceAtLeast(0),
            ),
        )
    }

    private fun eventType(method: String, pattern: String): String = when {
        method == "POST" && pattern.endsWith("/sessions") -> "SESSION_CREATED"
        method == "POST" && pattern.endsWith("/messages") -> "GENERATION_REQUESTED"
        pattern.endsWith("/design-decision") -> "DESIGN_DECIDED"
        pattern.endsWith("/patches") -> "GRAPH_PATCHED"
        method == "PUT" && pattern.contains("/agents/") -> "AGENT_UPDATED"
        pattern.endsWith("/simulations") -> "SIMULATION_REQUESTED"
        pattern.endsWith("/decision") -> "RUN_DECIDED"
        pattern.endsWith("/restore") -> "VERSION_RESTORED"
        pattern.endsWith("/cancel") -> "GENERATION_CANCELLED"
        pattern.endsWith("/package") -> "PACKAGE_DOWNLOADED"
        else -> "${method}_AGENT_DEVELOPMENT"
    }

    private fun targetType(pattern: String): String? = when {
        pattern.contains("/runs/") -> "RUN"
        pattern.contains("/jobs/") -> "JOB"
        pattern.contains("/versions/") -> "VERSION"
        pattern.contains("/sessions/") -> "SESSION"
        else -> null
    }

    companion object {
        const val START_NANOS = "agentDevelopmentAuditStartNanos"
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}

@Component
class AgentDevelopmentAuditInterceptor(
    private val recorder: AgentDevelopmentActivityRecorder,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (shouldRecord(request)) request.setAttribute(AgentDevelopmentActivityRecorder.START_NANOS, System.nanoTime())
        return true
    }

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        if (request.getAttribute(AgentDevelopmentActivityRecorder.START_NANOS) == null) return
        val user = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser ?: return
        runCatching { recorder.record(user, request, response) }
    }

    private fun shouldRecord(request: HttpServletRequest) =
        !request.requestURI.endsWith("/events") &&
            (request.method in setOf("POST", "PUT", "PATCH", "DELETE") || request.requestURI.endsWith("/package"))
}

@Component
class AgentDevelopmentAuditWebConfiguration(
    private val interceptor: AgentDevelopmentAuditInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor)
            .addPathPatterns("/api/agent-development/**")
    }
}
