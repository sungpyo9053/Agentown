package com.agentvillage.builder

import com.agentvillage.builder.application.HarnessPackageRenderer
import com.agentvillage.builder.application.StructuredMetaAgentPipeline
import com.agentvillage.builder.application.MetaAgentAuditService
import com.agentvillage.builder.application.DeterministicMockMetaAgentModel
import com.agentvillage.builder.domain.MetaAgentDesignBundle
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.kotlin.mock
import java.nio.file.Files
import java.nio.file.Path

/** Explicit opt-in artifact export for real downloaded-runner acceptance checks. */
class LocalOfficePackageExportTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "LOCAL_OFFICE_FIXTURE_ROOT", matches = ".+")
    fun `render current package assets for a supplied design fixture`() {
        val mapper = jacksonObjectMapper()
        System.getenv("LOCAL_OFFICE_FIXTURE_ROOT").split(',').forEach { path ->
            val root = Path.of(path).toRealPath()
            val original: MetaAgentDesignBundle = mapper.readValue(Files.readString(root.resolve("design-bundle.json")))
            val bundle = if (System.getenv("LOCAL_OFFICE_NORMALIZE_BOUND_CONTRACTS") == "true") {
                StructuredMetaAgentPipeline(DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(mock()), mock())
                    .normalizeBoundAgentSchemas(original)
            } else original
            mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("exported-design-bundle.json").toFile(), bundle)
            HarnessPackageRenderer(mapper).render(bundle).forEach { (name, content) ->
                val target = root.resolve(name).normalize()
                require(target.startsWith(root))
                Files.createDirectories(target.parent)
                Files.writeString(target, content)
            }
        }
    }
}
