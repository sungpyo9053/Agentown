package com.agentvillage.builder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetaAgentSchemaStrictnessTest {
    @Test
    fun `every strict object requires every declared property`() {
        val schema = javaClass.getResourceAsStream("/builder/meta-agent-design-bundle.schema.json")!!.use {
            ObjectMapper().readTree(it)
        }
        val violations = mutableListOf<String>()

        inspect(schema, "$", violations)

        assertThat(violations).isEmpty()
    }

    private fun inspect(node: JsonNode, path: String, violations: MutableList<String>) {
        if (node.isObject) {
            val properties = node.get("properties")
            if (node.path("type").asText() == "object" && properties?.isObject == true) {
                val declared = properties.fieldNames().asSequence().toSet()
                val required = node.path("required").map(JsonNode::asText).toSet()
                if (declared != required) violations += "$path declared=$declared required=$required"
            }
            node.fields().forEachRemaining { (key, value) -> inspect(value, "$path.$key", violations) }
        } else if (node.isArray) {
            node.forEachIndexed { index, value -> inspect(value, "$path[$index]", violations) }
        }
    }
}
