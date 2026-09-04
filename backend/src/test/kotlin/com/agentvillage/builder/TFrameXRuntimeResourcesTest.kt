package com.agentvillage.builder

import com.agentvillage.builder.application.TFrameXRuntimeResources
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TFrameXRuntimeResourcesTest {
    @Test
    fun `all required pinned runtime resources are packaged`() {
        TFrameXRuntimeResources.requiredPaths.forEach { path ->
            assertThat(TFrameXRuntimeResources.read(path))
                .describedAs(path)
                .isNotBlank()
        }
    }
}
