package com.agentvillage.builder.application

import com.agentvillage.builder.domain.FieldDefinition
import com.agentvillage.builder.domain.AutomationProposal
import com.agentvillage.builder.domain.NodeType
import com.agentvillage.builder.domain.WorkflowGraphPlan

/** Local-only renderer contract; the model supplies content, never a filesystem path. */
object LocalArtifactContract {
    private val structures = mapOf(
        "pptx" to """{"format":"pptx","title":"120자 이하","slides":[{"title":"52자 이하","bullets":["100자 이하"],"sourceNote":"160자 이하"}]}. slides 1~30개, 각 bullets 1~4개.""",
        "xlsx" to """{"format":"xlsx","title":"120자 이하","sheets":[{"name":"31자 이하","columns":["열 제목"],"rows":[["셀 값"]]}]}. sheets 1~12개, columns 1~30개, rows 1~5000개, 각 행 길이=columns 수. 셀은 2000자 이하 문자열/유한 숫자/불리언/null이며 수식은 실행되지 않는다.""",
        "docx" to """{"format":"docx","title":"120자 이하","summary":"2000자 이하","sections":[{"heading":"120자 이하","paragraphs":["2000자 이하"],"newPage":false}],"sources":[{"label":"300자 이하","url":"https://출처주소"}]}. sections 1~30개, 각 paragraphs 1~10개, 전체 본문 100000자 이하, sources 0~30개. 출처는 실제 확인한 주소만 사용한다. 문단으로 분석을 전개하고 가정과 확인된 근거를 구분한다. 실제 페이지 수는 렌더링 검수 전에는 확정하지 않는다.""",
    )
    val formats = structures.keys + "bundle"
    val runtimeModules = mapOf("pptx" to listOf("pptx"), "xlsx" to listOf("openpyxl"), "docx" to listOf("docx"))
        .let { it + ("bundle" to it.values.flatten().distinct()) }
    fun normalizeGeneratedConfig(plan: WorkflowGraphPlan) = plan.copy(nodes = plan.nodes.map { node ->
        val key = node.config["rendererKey"] as? String
        val alias = formats.firstOrNull { key == it || key == "artifact.$it.v1" }
        if (node.nodeType == NodeType.LOCAL_ARTIFACT_RENDER.wireName && "format" !in node.config && alias != null)
            node.copy(config = (node.config - "rendererKey") + ("format" to alias))
        else node
    })
    fun outputSummary() = output.joinToString(", ") { field ->
        "${field.name}(${field.type}${field.enumValues?.joinToString("|", prefix = ":") ?: ""})"
    }
    fun producerInstruction(format: String): String {
        require(format in formats)
        val structure = structures[format] ?: (
            """{"format":"bundle","title":"120자 이하","artifacts":[파일명세1,파일명세2]}. artifacts는 서로 다른 형식 2~3개. 요청한 형식만 포함하고 각 원소는 아래 규칙을 준수한다. 중첩 bundle은 금지한다. """ +
                structures.values.joinToString(" ")
            )
        return "파일 제작 계약: artifactJson 필드에 다음 구조의 유효한 JSON 문자열을 반환한다. $structure " +
            "저장 경로를 생성하지 않는다. 검수된 근거와 미확인 사항을 보존하며 글자 제한 안에서 핵심을 명료하게 쓴다. " +
            "수신자가 결정하거나 실행할 내용으로 작성한다. 검수에서 발견한 오류는 본문에 바로잡아 반영하고, 사용자가 검수 기록을 요청하지 않았다면 내부 검수 과정이나 통과 선언을 별도 절로 넣지 않는다. 같은 한계는 반복하지 말고 관련 판단 옆이나 공통 한계에 명시한다."
    }
    fun requested(text: String) = Regex("(?i)(\\bpptx?\\b|\\bxlsx\\b|\\bdocx\\b|파워포인트|엑셀|워드 파일)").containsMatchIn(text)
    fun configured(proposal: AutomationProposal) = proposal.graphPlan?.nodes?.any {
        it.nodeType == NodeType.LOCAL_ARTIFACT_RENDER.wireName && it.config["format"] in formats
    } == true
    val input = listOf(FieldDefinition("artifactJson", "string", true,
        "실제 파일 내용 명세 JSON. format/title 및 해당 형식의 slides/sheets/sections/artifacts를 포함한다. 저장 경로는 지정하지 않는다.", minLength = 2))
    val output = listOf(
        FieldDefinition("artifactPath", "string", true, "사용자 PC에 생성한 새 결과 파일 경로", minLength = 1),
        FieldDefinition("artifactSha256", "string", true, "생성한 파일 SHA-256", minLength = 64),
        FieldDefinition("artifactFormat", "string", true, "파일 형식", enumValues = formats.toList()),
        FieldDefinition("artifactBytes", "integer", true, "실제 파일 크기", minimum = 1.0),
        FieldDefinition("artifactQuality", "string", true, "내용 및 시각 검토 상태", enumValues = listOf("REVIEW_REQUIRED")),
    )
}
