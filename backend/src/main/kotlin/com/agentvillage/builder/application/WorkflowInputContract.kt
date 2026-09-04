package com.agentvillage.builder.application

import com.agentvillage.builder.domain.FieldDefinition
import java.math.BigInteger

internal object WorkflowInputContract {
    private val supportedTypes = setOf("string", "array", "object", "boolean", "number", "integer")

    fun schemaIssue(fields: List<FieldDefinition>): String? = schemaIssue(fields, "외부 입력")

    private fun schemaIssue(fields: List<FieldDefinition>, path: String): String? {
        fields.firstOrNull { it.name.isBlank() }?.let { return "$path 필드명은 비어 있을 수 없습니다." }
        fields.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            return "$path 필드 '${it.key}'가 중복 선언되었습니다."
        }
        fields.firstOrNull { it.type.lowercase() !in supportedTypes }?.let {
            return "$path 필드 '${it.name}'의 타입 '${it.type}'을 지원하지 않습니다."
        }
        fields.firstOrNull { (it.minItems != null || it.maxItems != null) && !it.type.equals("array", true) }?.let {
            return "$path 필드 '${it.name}'의 배열 개수 제약은 array 타입에만 사용할 수 있습니다."
        }
        fields.firstOrNull { (it.minItems ?: 0) < 0 || (it.maxItems ?: 0) < 0 }?.let {
            return "$path 필드 '${it.name}'의 배열 개수 제약은 0 이상이어야 합니다."
        }
        fields.firstOrNull { it.minItems != null && it.maxItems != null && it.minItems > it.maxItems }?.let {
            return "$path 필드 '${it.name}'의 minItems는 maxItems보다 클 수 없습니다."
        }
        fields.firstOrNull { (it.itemType != null || it.itemSchema != null) && !it.type.equals("array", true) }?.let {
            return "$path 필드 '${it.name}'의 항목 계약은 array 타입에만 사용할 수 있습니다."
        }
        fields.firstOrNull { it.itemType != null && it.itemType.lowercase() !in supportedTypes }?.let {
            return "$path 필드 '${it.name}'의 항목 타입 '${it.itemType}'을 지원하지 않습니다."
        }
        fields.firstOrNull { it.itemSchema != null && !it.itemType.equals("object", true) }?.let {
            return "$path 필드 '${it.name}'의 itemSchema는 itemType=object일 때만 사용할 수 있습니다."
        }
        fields.firstOrNull { it.itemType.equals("object", true) && it.itemSchema.isNullOrEmpty() }?.let {
            return "$path 필드 '${it.name}'의 object 배열은 비어 있지 않은 itemSchema가 필요합니다."
        }
        fields.forEach { field ->
            field.itemSchema?.let { nested ->
                schemaIssue(nested, "$path.${field.name}[]")?.let { return it }
            }
        }
        return null
    }

    fun valueIssue(fields: List<FieldDefinition>, value: Map<String, Any?>, enforceEmpty: Boolean = false): String? {
        if (fields.isEmpty()) return if (enforceEmpty && value.isNotEmpty()) {
            "워크플로우 입력에 선언되지 않은 필드 '${value.keys.first()}'이 있습니다."
        } else null
        schemaIssue(fields)?.let { return it }
        val declared = fields.map { it.name }.toSet()
        value.keys.firstOrNull { it !in declared }?.let {
            return "워크플로우 입력에 선언되지 않은 필드 '$it'이 있습니다."
        }
        fields.firstOrNull { it.required && (!value.containsKey(it.name) || value[it.name] == null) }?.let {
            return "워크플로우 입력 필수 필드 '${it.name}'이 없습니다."
        }
        fields.forEach { field ->
            if (!value.containsKey(field.name)) return@forEach
            val actual = value[field.name]
            valueIssue(field, actual, field.name)?.let { return "워크플로우 입력 $it" }
        }
        return null
    }

    private fun valueIssue(field: FieldDefinition, actual: Any?, path: String): String? {
        if (!matchesType(field.type, actual)) return "필드 '$path'의 타입이 ${field.type}이 아닙니다."
        if (actual !is List<*>) return null
        field.minItems?.takeIf { actual.size < it }?.let { return "필드 '$path'은 최소 ${it}개 항목이 필요합니다." }
        field.maxItems?.takeIf { actual.size > it }?.let { return "필드 '$path'은 최대 ${it}개 항목만 허용합니다." }
        val itemType = field.itemType ?: return null
        actual.forEachIndexed { index, item ->
            val itemPath = "$path[$index]"
            if (!matchesType(itemType, item)) return "필드 '$itemPath'의 타입이 ${itemType}이 아닙니다."
            val nested = field.itemSchema ?: return@forEachIndexed
            val objectItem = item as? Map<*, *> ?: return "필드 '$itemPath'의 타입이 object가 아닙니다."
            val declared = nested.map { it.name }.toSet()
            objectItem.keys.firstOrNull { it !is String || it !in declared }?.let {
                return "필드 '$itemPath'에 선언되지 않은 필드 '$it'이 있습니다."
            }
            nested.firstOrNull { it.required && (!objectItem.containsKey(it.name) || objectItem[it.name] == null) }?.let {
                return "필드 '$itemPath.${it.name}'이 없습니다."
            }
            nested.forEach { nestedField ->
                val nestedValue = objectItem[nestedField.name] ?: return@forEach
                valueIssue(nestedField, nestedValue, "$itemPath.${nestedField.name}")?.let { return it }
            }
        }
        return null
    }

    private fun matchesType(type: String, actual: Any?): Boolean = when (type.lowercase()) {
        "string" -> actual is String
        "array" -> actual is List<*>
        "object" -> actual is Map<*, *>
        "boolean" -> actual is Boolean
        "number" -> actual is Number
        "integer" -> actual is Byte || actual is Short || actual is Int || actual is Long || actual is BigInteger
        else -> false
    }
}
