package com.agentvillage.builder

import com.agentvillage.builder.application.SchemaSampleGenerator
import com.agentvillage.builder.domain.FieldDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchemaSampleGeneratorTest {
    @Test
    fun `memo examples use field meaning rather than unrelated inventory facts`() {
        val sample = SchemaSampleGenerator.generate(listOf(FieldDefinition("userMemo", "string", true, "제품 사용 목적과 불편")))
        assertThat(sample["userMemo"].toString()).contains("제품 사용 목적과 불편").doesNotContain("재고", "매니저")
    }

    @Test
    fun `memo named fields preserve schema type and enum constraints`() {
        val sample = SchemaSampleGenerator.generate(listOf(
            FieldDefinition("memoStatus", "string", true, "상태", enumValues = listOf("MISSING", "READY")),
            FieldDefinition("memos", "array", true, "메모 목록", minItems = 2, maxItems = 2, itemType = "string"),
        ))
        assertThat(sample["memoStatus"]).isEqualTo("MISSING")
        assertThat(sample["memos"] as List<*>).hasSize(2)
    }
}
