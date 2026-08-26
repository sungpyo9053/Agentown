package com.agentvillage.identity.infrastructure

import com.agentvillage.identity.domain.UserStatus
import com.agentvillage.common.domain.UserRole
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.http.HttpStatus
import org.springframework.beans.factory.annotation.Value
import java.io.Serializable
import java.util.UUID

data class AuthenticatedUser(
    val userId: UUID,
    private val userEmail: String,
    private val encodedPassword: String,
    private val active: Boolean,
    val role: UserRole = UserRole.USER,
) : UserDetails, Serializable {
    override fun getAuthorities() = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
    override fun getPassword() = encodedPassword
    override fun getUsername() = userEmail
    override fun isEnabled() = active
}

@Configuration
class SecurityConfiguration {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailsService(users: UserAccountRepository): UserDetailsService = UserDetailsService { loginId ->
        val user = users.findByEmailIgnoreCase(loginId.trim())
            ?: throw UsernameNotFoundException("User not found")
        AuthenticatedUser(user.id, user.email.orEmpty(), user.passwordHash, user.status == UserStatus.ACTIVE, user.role)
    }

    @Bean
    fun authenticationManager(
        userDetailsService: UserDetailsService,
        passwordEncoder: PasswordEncoder,
    ): AuthenticationManager {
        val provider = DaoAuthenticationProvider()
        provider.setUserDetailsService(userDetailsService)
        provider.setPasswordEncoder(passwordEncoder)
        return ProviderManager(provider)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfHandler = CsrfTokenRequestAttributeHandler()
        csrfHandler.setCsrfRequestAttributeName(null)

        http
            .cors { }
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                csrf.csrfTokenRequestHandler(csrfHandler)
                csrf.ignoringRequestMatchers("/api/runner/**", "/api/connectors/slack/events")
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .securityContext { it.securityContextRepository(HttpSessionSecurityContextRepository()) }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("/actuator/health/**", "/api/auth/signup", "/api/auth/login", "/api/auth/csrf", "/api/auth/availability", "/api/auth/email/**", "/api/auth/password/temporary", "/api/runner/**", "/api/connectors/slack/oauth/callback", "/api/connectors/slack/events", "/api/connectors/notion/oauth/callback").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/mini-homes/me").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/mini-homes/*").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/users/*").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/public/agents/*").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/public/users/*/harnesses").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/market/products/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .logout { logout ->
                logout.logoutUrl("/api/auth/logout")
                    .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                    .invalidateHttpSession(true)
                    .logoutSuccessHandler { _, response, _ -> response.status = HttpStatus.NO_CONTENT.value() }
            }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(
        @Value("\${security.cors.allowed-origins:http://localhost:3000}") allowedOrigins: String,
    ): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins.split(',').map(String::trim).filter(String::isNotBlank)
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().also { it.registerCorsConfiguration("/**", configuration) }
    }
}
