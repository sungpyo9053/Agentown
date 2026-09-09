package com.agentvillage.builder.application

/** Shared, domain-neutral guidance; output contracts and permissions stay authoritative. */
internal object AgentOutputQualityPolicy {
    val instructions: String by lazy {
        requireNotNull(javaClass.getResourceAsStream("/builder/agent-output-quality.txt")) {
            "Agent output quality policy is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }.also {
            require(it.isNotBlank()) { "Agent output quality policy is empty" }
        }
    }
}
