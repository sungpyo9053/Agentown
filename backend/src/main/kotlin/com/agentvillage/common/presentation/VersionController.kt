package com.agentvillage.common.presentation

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class DeploymentVersion(val application: String, val commitSha: String, val artifact: String, val startedAt: Instant)

@RestController
class VersionController(@Value("\${release.commit-sha:unknown}") private val commitSha: String, @Value("\${release.artifact:local-development}") private val artifact: String) {
    private val startedAt = Instant.now()
    @GetMapping("/api/version") fun version() = DeploymentVersion("agentown", commitSha, artifact, startedAt)
}
