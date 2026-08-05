package com.agentvillage.artifact.presentation

import com.agentvillage.artifact.application.ArtifactService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController @RequestMapping("/api/artifacts")
class ArtifactController(private val service: ArtifactService) {
    @GetMapping("/{id}") fun get(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.requireOwned(id, p.userId)
    @GetMapping("/{id}/download") fun download(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID): ResponseEntity<Void> {
        val a = service.requireOwned(id, p.userId)
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI(a.externalUrl).toASCIIString()).build()
    }
}
