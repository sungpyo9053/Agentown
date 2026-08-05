@org.springframework.modulith.ApplicationModule(
    displayName = "Execution",
    allowedDependencies = {"common :: exception", "agent :: application", "harness", "identity :: security", "llmcredential :: application", "llmcredential :: domain"}
)
package com.agentvillage.execution;
