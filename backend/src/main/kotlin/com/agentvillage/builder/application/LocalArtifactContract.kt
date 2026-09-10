package com.agentvillage.builder.application

import com.agentvillage.builder.domain.FieldDefinition
import com.agentvillage.builder.domain.AutomationProposal
import com.agentvillage.builder.domain.NodeType

/** Local-only renderer contract; the model supplies content, never a filesystem path. */
object LocalArtifactContract {
    val formats = setOf("pptx", "xlsx")
    fun outputSummary() = output.joinToString(", ") { field ->
        "${field.name}(${field.type}${field.enumValues?.joinToString("|", prefix = ":") ?: ""})"
    }
    fun producerInstruction(format: String): String {
        require(format in formats)
        val structure = if (format == "pptx") """{"format":"pptx","title":"120자 이하","slides":[{"title":"52자 이하","bullets":["100자 이하"],"sourceNote":"160자 이하"}]}. slides 1~30개, 각 bullets 1~4개.""" else
            """{"format":"xlsx","title":"120자 이하","sheets":[{"name":"31자 이하","columns":["열 제목"],"rows":[["셀 값"]]}]}. sheets 1~12개, columns 1~30개, rows 1~5000개, 각 행 길이=columns 수. 셀은 2000자 이하 문자열/유한 숫자/불리언/null이며 수식은 실행되지 않는다."""
        return "파일 제작 계약: artifactJson 필드에 다음 구조의 유효한 JSON 문자열을 반환한다. $structure " +
            "저장 경로를 생성하지 않는다. 검수된 근거와 미확인 사항을 보존하며 글자 제한 안에서 핵심을 명료하게 쓴다."
    }
    fun requested(text: String) = Regex("(?i)(\\bpptx?\\b|\\bxlsx\\b|파워포인트|엑셀)").containsMatchIn(text)
    fun configured(proposal: AutomationProposal) = proposal.graphPlan?.nodes?.any {
        it.nodeType == NodeType.LOCAL_ARTIFACT_RENDER.wireName && it.config["format"] in formats
    } == true
    val input = listOf(FieldDefinition("artifactJson", "string", true,
        "실제 파일 내용 명세 JSON. format/title 및 slides 또는 sheets를 포함한다. 저장 경로는 지정하지 않는다.", minLength = 2))
    val output = listOf(
        FieldDefinition("artifactPath", "string", true, "사용자 PC에 생성한 새 결과 파일 경로", minLength = 1),
        FieldDefinition("artifactSha256", "string", true, "생성한 파일 SHA-256", minLength = 64),
        FieldDefinition("artifactFormat", "string", true, "파일 형식", enumValues = formats.toList()),
        FieldDefinition("artifactBytes", "integer", true, "실제 파일 크기", minimum = 1.0),
        FieldDefinition("artifactQuality", "string", true, "내용 및 시각 검토 상태", enumValues = listOf("REVIEW_REQUIRED")),
    )
}
