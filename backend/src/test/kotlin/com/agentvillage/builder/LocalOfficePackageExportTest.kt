package com.agentvillage.builder

import com.agentvillage.builder.application.HarnessPackageRenderer
import com.agentvillage.builder.domain.MetaAgentDesignBundle
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

/** Explicit opt-in artifact export for real downloaded-runner acceptance checks. */
class LocalOfficePackageExportTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "LOCAL_OFFICE_FIXTURE_ROOT", matches = ".+")
    fun `render current package assets for a supplied design fixture`() {
        val root = Path.of(System.getenv("LOCAL_OFFICE_FIXTURE_ROOT")).toRealPath()
        val mapper = jacksonObjectMapper()
        val bundle: MetaAgentDesignBundle = mapper.readValue(Files.readString(root.resolve("design-bundle.json")))
        HarnessPackageRenderer(mapper).render(bundle).forEach { (name, content) ->
            val target = root.resolve(name).normalize()
            require(target.startsWith(root))
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
    }
}
