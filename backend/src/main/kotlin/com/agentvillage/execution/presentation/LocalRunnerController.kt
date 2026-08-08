package com.agentvillage.execution.presentation

import com.agentvillage.execution.application.LocalRunnerService
import com.agentvillage.execution.domain.LocalRunnerProvider
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class PairLocalRunnerRequest(val provider: LocalRunnerProvider, val deviceName: String)
data class CompleteRunnerJobRequest(val output: Map<String, Any>)
data class FailRunnerJobRequest(val code: String = "LOCAL_CLI_FAILED", val message: String)
data class RunnerProgressRequest(val eventType: String, val agentId: UUID? = null, val stepKey: String)

@RestController
class LocalRunnerController(private val service: LocalRunnerService) {
    @PostMapping("/api/local-runners/pair") fun pair(@AuthenticationPrincipal user: AuthenticatedUser, @RequestBody request: PairLocalRunnerRequest) = service.pair(user.userId, request.provider, request.deviceName)
    @GetMapping("/api/local-runners") fun list(@AuthenticationPrincipal user: AuthenticatedUser) = service.list(user.userId)
    @DeleteMapping("/api/local-runners/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun revoke(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable id: UUID) = service.revoke(user.userId, id)

    @PostMapping("/api/runner/heartbeat") fun heartbeat(@RequestHeader("X-Runner-Token") token: String) = service.heartbeat(token)
    @PostMapping("/api/runner/jobs/claim") fun claim(@RequestHeader("X-Runner-Token") token: String) = service.claim(token)
    @PostMapping("/api/runner/jobs/{id}/complete") @ResponseStatus(HttpStatus.NO_CONTENT) fun complete(@RequestHeader("X-Runner-Token") token: String, @PathVariable id: UUID, @RequestBody request: CompleteRunnerJobRequest) = service.complete(token, id, request.output)
    @PostMapping("/api/runner/jobs/{id}/events") @ResponseStatus(HttpStatus.NO_CONTENT) fun progress(@RequestHeader("X-Runner-Token") token: String, @PathVariable id: UUID, @RequestBody request: RunnerProgressRequest) = service.progress(token, id, request.eventType, request.agentId, request.stepKey)
    @PostMapping("/api/runner/jobs/{id}/fail") @ResponseStatus(HttpStatus.NO_CONTENT) fun fail(@RequestHeader("X-Runner-Token") token: String, @PathVariable id: UUID, @RequestBody request: FailRunnerJobRequest) = service.fail(token, id, request.code, request.message)
}
