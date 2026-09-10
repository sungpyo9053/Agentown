package com.agentvillage.builder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetaAgentSchemaStrictnessTest {
    @Test
    fun `model node catalog and local artifact formats match runtime registration`() {
        val properties = schema()["properties"]["proposal"]["properties"]["graphPlan"]["properties"]["nodes"]
            .get("items").get("properties")
        assertThat(properties["nodeType"]["enum"].map(JsonNode::asText).toSet())
            .isEqualTo(com.agentvillage.builder.domain.NodeType.entries
                .filter { it != com.agentvillage.builder.domain.NodeType.PARALLEL_MAP_MOCK }.map { it.wireName }.toSet())
        val format = properties["config"]["anyOf"].single { it.path("properties").has("format") }
        assertThat(format["properties"]["format"]["enum"].map(JsonNode::asText).toSet())
            .isEqualTo(com.agentvillage.builder.application.LocalArtifactContract.formats)
    }
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
        val variants = schema()["\$defs"]["fields"]["items"]["anyOf"]
        val field = variants.single { it["properties"]["type"]["enum"].any { type -> type.asText() == "array" } }

        assertThat(variants.flatMap { it["properties"]["type"]["enum"].map(JsonNode::asText) })
            .containsExactlyInAnyOrder("string", "boolean", "number", "integer", "object", "array")
        assertThat(field["properties"].fieldNames().asSequence().toList()).contains("minItems", "maxItems", "itemType", "itemSchema")
        assertThat(field["required"].map(JsonNode::asText)).contains("minItems", "maxItems", "itemType", "itemSchema")
        assertThat(field["properties"]["itemSchema"]["anyOf"].first()["\$ref"].asText()).isEqualTo("#/\$defs/fields")
    }

    @Test
    fun `typed field contracts omit irrelevant nulls without dropping applicable constraints`() {
        val variants = schema()["\$defs"]["fields"]["items"]["anyOf"]
        val expected = mapOf(
            "string" to setOf("format", "enumValues", "minLength"), "boolean" to emptySet(),
            "number" to setOf("minimum", "maximum"), "integer" to setOf("minimum", "maximum"),
            "object" to setOf("objectSchema"),
            "array" to setOf("minItems", "maxItems", "itemType", "itemSchema", "itemFormat", "itemMinLength", "uniqueItems", "uniqueBy"),
        )
        variants.forEach { variant ->
            variant["properties"]["type"]["enum"].forEach { type ->
                assertThat(variant["properties"].fieldNames().asSequence().toSet())
                    .isEqualTo(setOf("name", "type", "required", "description") + expected.getValue(type.asText()))
            }
        }
        val parsed = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(
            """{"name":"sourceText","type":"string","required":true,"description":"원문","format":null,"enumValues":null,"minLength":1}""",
            com.agentvillage.builder.domain.FieldDefinition::class.java,
        )
        assertThat(parsed.minLength).isEqualTo(1)
        assertThat(parsed.minItems).isNull()
        assertThat(parsed.itemSchema).isNull()
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
