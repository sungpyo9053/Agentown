package com.agentvillage.builder.presentation

import com.agentvillage.builder.application.HarnessTemplateCatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/output-templates")
class AdminHarnessTemplateController(private val catalog: HarnessTemplateCatalogService) {
    @GetMapping("/status") fun status() = catalog.status()
    @PostMapping("/sync") fun sync() = catalog.sync()
    @PostMapping("/versions/{versionId}/activate") fun activate(@PathVariable versionId: java.util.UUID) = catalog.activate(versionId)
    @PostMapping("/versions/{versionId}/deprecate") fun deprecate(@PathVariable versionId: java.util.UUID) = catalog.deprecate(versionId)
}
