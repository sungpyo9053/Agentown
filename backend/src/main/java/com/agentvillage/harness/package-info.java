@org.springframework.modulith.ApplicationModule(
    displayName = "Harness",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {"common :: domain", "common :: exception", "agent :: application", "agent :: domain", "identity :: application", "identity :: security", "llmcredential :: domain"}
)
package com.agentvillage.harness;
