package com.agentvillage

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulith
import org.springframework.scheduling.annotation.EnableScheduling

@Modulith
@EnableScheduling
@SpringBootApplication
@EnableAsync
class AgentVillageApplication

fun main(args: Array<String>) {
    runApplication<AgentVillageApplication>(*args)
}
