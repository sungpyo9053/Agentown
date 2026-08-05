package com.agentvillage

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@ActiveProfiles("test")
abstract class IntegrationTestSupport {
    companion object {
        private val externalUrl = System.getProperty("test.database.url")
        private val postgres = if (externalUrl != null) null
            else PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            if (externalUrl != null) {
                registry.add("spring.datasource.url") { externalUrl }
                registry.add("spring.datasource.username") { System.getProperty("test.database.username", "agent_village") }
                registry.add("spring.datasource.password") { System.getProperty("test.database.password", "agent_village_local") }
            } else {
                registry.add("spring.datasource.url") { postgres!!.jdbcUrl }
                registry.add("spring.datasource.username") { postgres!!.username }
                registry.add("spring.datasource.password") { postgres!!.password }
            }
        }
    }
}
