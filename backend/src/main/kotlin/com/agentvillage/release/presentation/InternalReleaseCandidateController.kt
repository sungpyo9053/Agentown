package com.agentvillage.release.presentation

import com.agentvillage.common.exception.ForbiddenException
import com.agentvillage.identity.application.UserDirectory
import com.agentvillage.release.application.ReleaseCandidateCommand
import com.agentvillage.release.application.ReleaseControlService
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest

@RestController @RequestMapping("/api/internal/releases")
class InternalReleaseCandidateController(private val service: ReleaseControlService, private val users: UserDirectory, @Value("\${release.agent-token:}") private val configuredToken: String) {
    @GetMapping("/{releaseKey}") fun get(@RequestHeader("X-Release-Agent-Token") token: String, @PathVariable releaseKey: String): Any {
        val owner = authenticate(token)
        return service.internalByKey(owner.id, releaseKey)
    }
    @PostMapping("/candidates")
    fun candidate(@RequestHeader("X-Release-Agent-Token") token: String, @RequestBody command: ReleaseCandidateCommand): Any {
        val owner = authenticate(token)
        return service.publishCandidate(owner.id, command)
    }
    private fun authenticate(token: String) = users.findByEmail("admin@reviewdr.kr").also {
        if (configuredToken.isBlank() || !MessageDigest.isEqual(configuredToken.toByteArray(), token.toByteArray())) throw ForbiddenException("RELEASE_AGENT_UNAUTHORIZED", "Release Agent 인증이 필요합니다.")
    } ?: throw ForbiddenException("RELEASE_OWNER_MISSING", "admin@reviewdr.kr 운영자 계정이 필요합니다.")
}
