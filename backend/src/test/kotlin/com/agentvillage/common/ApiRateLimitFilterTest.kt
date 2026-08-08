package com.agentvillage.common

import com.agentvillage.common.presentation.ApiRateLimitFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ApiRateLimitFilterTest {
    @Test
    fun `login burst is rejected with retry metadata`() {
        val filter = ApiRateLimitFilter(true, false, Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC))
        var passed = 0
        repeat(21) { index ->
            val request = MockHttpServletRequest("POST", "/api/auth/login").apply { remoteAddr = "203.0.113.10" }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, _ -> passed++ }
            if (index < 20) assertThat(response.status).isEqualTo(200)
            else {
                assertThat(response.status).isEqualTo(429)
                assertThat(response.getHeader("Retry-After")).isEqualTo("300")
                assertThat(response.contentAsString).doesNotContain("203.0.113.10")
            }
        }
        assertThat(passed).isEqualTo(20)
    }
}
