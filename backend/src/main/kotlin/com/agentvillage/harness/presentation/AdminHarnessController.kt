package com.agentvillage.harness.presentation

import com.agentvillage.harness.application.AdminHarnessService
import com.agentvillage.harness.domain.HarnessStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class UpdateAdminHarnessRequest(val status: HarnessStatus)

@RestController
@RequestMapping("/api/admin/harnesses")
class AdminHarnessController(private val service: AdminHarnessService) {
    @GetMapping fun list() = service.list()
    @GetMapping("/summary") fun summary() = service.summary()
    @PatchMapping("/{id}/status")
    fun status(@PathVariable id: UUID, @RequestBody request: UpdateAdminHarnessRequest) =
        service.changeStatus(id, request.status)
}
