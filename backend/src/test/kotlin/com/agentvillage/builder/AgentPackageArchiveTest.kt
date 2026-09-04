package com.agentvillage.builder

import com.agentvillage.builder.presentation.AgentPackageArchive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class AgentPackageArchiveTest {
    @Test
    fun `download archive extracts into the agentown-agent directory`() {
        val bytes = AgentPackageArchive.create(mapOf(
            "AGENTS.md" to "common",
            "schemas/input.schema.json" to "{}",
        ))
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries += it.name }
        }

        assertThat(AgentPackageArchive.FILE_NAME).isEqualTo("agentown-agent.zip")
        assertThat(entries).containsExactly(
            "agentown-agent/AGENTS.md",
            "agentown-agent/schemas/input.schema.json",
        )
    }
}
