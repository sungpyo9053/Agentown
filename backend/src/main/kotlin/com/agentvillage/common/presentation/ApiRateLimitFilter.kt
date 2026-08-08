package com.agentvillage.common.presentation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

data class RateLimitRule(val name: String, val requests: Int, val windowSeconds: Long)

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ApiRateLimitFilter(
    @Value("\${security.rate-limit.enabled:true}") private val enabled: Boolean,
    @Value("\${security.rate-limit.trust-forwarded-for:false}") private val trustForwardedFor: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : OncePerRequestFilter() {
    private data class Window(var startedAt: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val rule = if (enabled) rule(request) else null
        if (rule == null) return chain.doFilter(request, response)
        val now = clock.instant().epochSecond
        val client = clientAddress(request)
        val key = "${rule.name}:$client"
        val window = windows.compute(key) { _, current ->
            if (current == null || now - current.startedAt >= rule.windowSeconds) Window(now, 1)
            else current.apply { count += 1 }
        }!!
        val remaining = (rule.requests - window.count).coerceAtLeast(0)
        val retryAfter = (rule.windowSeconds - (now - window.startedAt)).coerceAtLeast(1)
        response.setHeader("X-RateLimit-Limit", rule.requests.toString())
        response.setHeader("X-RateLimit-Remaining", remaining.toString())
        if (window.count > rule.requests) {
            response.status = 429
            response.contentType = "application/json;charset=UTF-8"
            response.setHeader("Retry-After", retryAfter.toString())
            response.writer.write("{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}")
            return
        }
        if (windows.size > 10_000) windows.entries.removeIf { now - it.value.startedAt > 3_600 }
        chain.doFilter(request, response)
    }

    private fun rule(request: HttpServletRequest): RateLimitRule? {
        if (request.method != "POST") return null
        val path = request.requestURI
        return when {
            path == "/api/auth/login" -> RateLimitRule("login", 20, 300)
            path == "/api/auth/phone/send-code" || path == "/api/auth/password/temporary" -> RateLimitRule("phone", 5, 600)
            path == "/api/designer/companies/design" -> RateLimitRule("designer", 30, 60)
            path.matches(Regex("^/api/harnesses/[^/]+/executions$")) -> RateLimitRule("execution", 10, 60)
            else -> null
        }
    }

    private fun clientAddress(request: HttpServletRequest): String {
        if (trustForwardedFor) request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return request.remoteAddr ?: "unknown"
    }
}

@Configuration
class TimeConfiguration {
    @Bean fun systemClock(): Clock = Clock.systemUTC()
}
