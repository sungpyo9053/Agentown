package com.agentvillage.builder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetaAgentSchemaStrictnessTest {
    private fun schema(): JsonNode = javaClass.getResourceAsStream("/builder/meta-agent-design-bundle.schema.json")!!.use {
        ObjectMapper().readTree(it)
    }

    @Test
    fun `every strict object requires every declared property`() {
        val schema = schema()
        val violations = mutableListOf<String>()

        inspect(schema, "$", violations)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `field definitions expose integer and optional array cardinality constraints`() {
        val field = schema()["\$defs"]["fields"]["items"]

        assertThat(field["properties"]["type"]["enum"].map(JsonNode::asText)).contains("integer")
        assertThat(field["properties"].fieldNames().asSequence().toList()).contains("minItems", "maxItems", "itemType", "itemSchema")
        assertThat(field["required"].map(JsonNode::asText)).contains("minItems", "maxItems", "itemType", "itemSchema")
        assertThat(field["properties"]["itemSchema"]["anyOf"].first()["\$ref"].asText()).isEqualTo("#/\$defs/fields")
    }

    @Test
    fun `ai node config requires nullable structured input defaults`() {
        val config = schema()["properties"]["proposal"]["properties"]["graphPlan"]["properties"]["nodes"]
            .get("items").get("properties").get("config").get("anyOf").get(3)

        assertThat(config["required"].map(JsonNode::asText)).contains("inputDefaults")
        assertThat(config["properties"]["inputDefaults"]["anyOf"].map { it.path("type").asText() })
            .contains("array", "null")
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
