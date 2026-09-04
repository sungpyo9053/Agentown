package com.agentvillage.builder.application

import com.agentvillage.builder.domain.FieldDefinition

internal object SchemaSampleGenerator {
    fun generate(fields: List<FieldDefinition>): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        fields.forEach { field -> put(field.name, value(field, field.name, 0)) }
    }

    private fun value(field: FieldDefinition, path: String, index: Int): Any? = when {
        field.name == "csvA" -> "id,name\n1,old\n2,remove\n"
        field.name == "csvB" -> "id,name\n1,new\n3,add\n"
        field.name.contains("memo", true) -> "재고 확인이 필요하며 담당 매니저에게 인계합니다."
        else -> when (field.type.lowercase()) {
            "array" -> {
                val count = field.minItems ?: if (field.maxItems == 0) 0 else 1
                List(count) { itemIndex -> arrayItem(field, "$path[$itemIndex]", itemIndex) }
            }
            "object" -> emptyMap<String, Any?>()
            "boolean" -> false
            "number", "integer" -> 1
            else -> sampleText(field, index)
        }
    }

    private fun arrayItem(field: FieldDefinition, path: String, index: Int): Any? = when (field.itemType?.lowercase()) {
        "object" -> linkedMapOf<String, Any?>().apply {
            field.itemSchema.orEmpty().forEach { nested -> put(nested.name, value(nested, "$path.${nested.name}", index)) }
        }
        "boolean" -> false
        "number", "integer" -> index + 1
        "array" -> emptyList<Any?>()
        "string", null -> if (field.name == "keyColumns") "id" else sampleText(field, index)
        else -> sampleText(field, index)
    }

    private fun sampleText(field: FieldDefinition, index: Int): String = with(field.name) { when {
        contains("date", true) || endsWith("At") -> "2026-09-05"
        contains("url", true) -> "https://example.com/evidence-${index + 1}"
        contains("status", true) -> "READY"
        else -> "${field.description.ifBlank { field.name }} 예시 ${index + 1}"
    } }
}
