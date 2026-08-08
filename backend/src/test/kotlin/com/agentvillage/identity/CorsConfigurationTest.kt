package com.agentvillage.identity

import com.agentvillage.identity.infrastructure.SecurityConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class CorsConfigurationTest {
    @Test
    fun `configured public origin is allowed through same origin web proxy`() {
        val source = SecurityConfiguration().corsConfigurationSource("https://agentown.example.com,http://localhost:3000")
        val configuration = source.getCorsConfiguration(MockHttpServletRequest("POST", "/api/auth/login"))
        assertThat(configuration?.checkOrigin("https://agentown.example.com")).isEqualTo("https://agentown.example.com")
        assertThat(configuration?.checkOrigin("https://attacker.example")).isNull()
    }
}
