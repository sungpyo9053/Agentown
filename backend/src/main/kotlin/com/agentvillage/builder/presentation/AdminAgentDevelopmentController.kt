package com.agentvillage.builder.presentation

import com.agentvillage.builder.application.AdminAgentDevelopmentService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/agent-development")
class AdminAgentDevelopmentController(private val service: AdminAgentDevelopmentService) {
    @GetMapping("/metrics")
    fun metrics(@RequestParam(defaultValue = "30") days: Int) = service.metrics(days)

    @GetMapping("/activities")
    fun activities() = service.activities()
}
