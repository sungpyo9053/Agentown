package com.agentvillage.artifact

import com.agentvillage.artifact.application.ArtifactRepository
import com.agentvillage.artifact.application.ArtifactService
import com.agentvillage.common.exception.BadRequestException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ArtifactUrlSecurityTest {
    private val service = ArtifactService(mock<ArtifactRepository>())

    @Test
    fun `download URL rejects plaintext and local network targets`() {
        assertThatThrownBy { service.validateUrl("http://example.com/file.zip") }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.validateUrl("https://127.0.0.1/private") }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.validateUrl("https://localhost/private") }
            .isInstanceOf(BadRequestException::class.java)
    }
}
