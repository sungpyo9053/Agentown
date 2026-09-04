package com.agentvillage

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = ["agentown.scheduling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SchedulingConfiguration
