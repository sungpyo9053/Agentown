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
            "object" -> linkedMapOf<String, Any?>().apply {
                field.objectSchema.orEmpty().forEach { nested -> put(nested.name, value(nested, "$path.${nested.name}", index)) }
            }
            "boolean" -> false
            "number" -> field.minimum ?: 1.0
            "integer" -> (field.minimum ?: 1.0).toInt()
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
        "string", null -> if (field.name == "keyColumns") "id" else sampleItemText(field, index)
        else -> sampleText(field, index)
    }

    private fun sampleItemText(field: FieldDefinition, index: Int): String = when (field.itemFormat) {
        "date" -> "2026-09-05"
        "date-time" -> "2026-09-05T09:00:00+09:00"
        "uri" -> "https://example.com/evidence-${index + 1}"
        else -> if (isReferenceLocation(field)) "https://example.com/material-${index + 1}" else sampleText(field, index)
    }

    private fun isReferenceLocation(field: FieldDefinition): Boolean {
        val hint = "${field.name} ${field.description}".lowercase()
        return listOf("url", "uri", "link", "링크", "출처", "자료 위치", "조사할 자료", "참고 자료").any(hint::contains)
    }

    private fun sampleText(field: FieldDefinition, index: Int): String = field.enumValues?.firstOrNull() ?: with(field.name) { when {
        field.format == "date" -> "2026-09-05"
        field.format == "date-time" -> "2026-09-05T09:00:00+09:00"
        field.format == "uri" -> "https://example.com/evidence-${index + 1}"
        contains("date", true) || endsWith("At") -> "2026-09-05"
        contains("url", true) -> "https://example.com/evidence-${index + 1}"
        contains("status", true) -> "READY"
        else -> "${field.description.ifBlank { field.name }} 예시 ${index + 1}"
    } }
}
