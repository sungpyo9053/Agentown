package com.agentvillage.builder

import com.agentvillage.builder.application.SchemaSampleGenerator
import com.agentvillage.builder.application.WorkflowInputContract
import com.agentvillage.builder.domain.FieldDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchemaSampleGeneratorTest {
    @Test
    fun `candidate identifiers are not mistaken for dates and remain unique`() {
        val fields = listOf(FieldDefinition("candidates", "array", true, "후보", minItems = 3,
            itemType = "object", uniqueItems = true, uniqueBy = "candidateId", itemSchema = listOf(
                FieldDefinition("candidateId", "string", true, "후보 ID"),
                FieldDefinition("candidateName", "string", true, "후보 이름"),
            )))
        val sample = SchemaSampleGenerator.generate(fields)
        val candidates = sample["candidates"] as List<Map<String, Any?>>
        assertThat(candidates.map { it["candidateId"] }).doesNotHaveDuplicates().hasSize(3)
        assertThat(candidates.map { it["candidateName"] }).doesNotHaveDuplicates()
        assertThat(WorkflowInputContract.valueIssue(fields, sample)).isNull()
    }

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
