package com.agentvillage.execution.presentation

import com.agentvillage.execution.application.AdminExecutionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/executions")
class AdminExecutionController(private val service: AdminExecutionService) {
    @GetMapping fun list() = service.list()
    @GetMapping("/summary") fun summary() = service.summary()
}
