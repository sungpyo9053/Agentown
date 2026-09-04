package com.agentvillage.builder.application

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

object TFrameXRuntimeResources {
    val requiredPaths = listOf(
        "pyproject.toml",
        "agentown_tframex_adapter/__init__.py",
        "agentown_tframex_adapter/adapter.py",
        "agentown_tframex_adapter/codex_llm.py",
        "agentown_tframex_adapter/capabilities.py",
        "agentown_tframex_adapter/server.py",
    )

    fun read(path: String): String {
        require(path in requiredPaths) { "Unknown pinned TFrameX runtime resource: $path" }
        return TFrameXRuntimeResources::class.java.getResourceAsStream("/tframex-runtime/$path")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Pinned TFrameX runtime resource is missing: $path")
    }

    fun verifyRequired() = requiredPaths.forEach(::read)
}

@Component
class TFrameXRuntimeResourceStartupValidator {
    @PostConstruct
    fun validate() {
        TFrameXRuntimeResources.verifyRequired()
    }
}
