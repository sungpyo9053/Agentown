package com.agentvillage.llmcredential.application

import com.agentvillage.common.exception.BadRequestException
import org.springframework.stereotype.Component

@Component
class ProviderOptionsPolicy {
    private val forbidden = Regex("(?i)(secret|token|password|api.?key|credential|authorization|organization.?id|project.?id)")

    fun validate(options: Map<String, Any>) {
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, child) ->
                    if (forbidden.containsMatchIn(key.toString())) {
                        throw BadRequestException("SECRET_IN_PROVIDER_OPTIONS", "providerOptions에는 비밀 값을 저장할 수 없습니다.")
                    }
                    visit(child)
                }
                is Iterable<*> -> value.forEach(::visit)
            }
        }
        visit(options)
    }
}
