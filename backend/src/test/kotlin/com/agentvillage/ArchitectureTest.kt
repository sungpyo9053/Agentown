package com.agentvillage

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ArchitectureTest {
    @Test
    fun `module dependencies are valid`() {
        ApplicationModules.of(AgentVillageApplication::class.java).verify()
    }
}

