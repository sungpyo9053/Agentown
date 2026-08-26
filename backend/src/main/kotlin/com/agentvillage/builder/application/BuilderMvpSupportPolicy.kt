package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AutomationRequirement
import com.agentvillage.builder.domain.MetaAgentDesignBundle
import com.agentvillage.common.exception.BadRequestException

object BuilderMvpSupportPolicy {
    private const val errorCode = "AUTOMATION_CAPABILITY_REQUIRED"

    fun requireSupported(instruction: String, bundle: MetaAgentDesignBundle) {
        requireSupported(listOf(instruction, requirementText(bundle.requirement)).joinToString("\n"))
    }

    fun requireSupported(requirement: AutomationRequirement) {
        requireSupported(requirementText(requirement))
    }

    internal fun unsupportedCapabilities(text: String): List<String> {
        val normalized = text.lowercase()
        return buildList {
            capability(normalized, "음성·녹음 파일 전사", "녹음 파일", "음성 파일", "오디오 파일", "speech to text", "transcription")
            if (listOf("로컬저장", "로컬 저장", "내 컴퓨터", "파일로 저장", "다운로드", "폴더에 저장").any(normalized::contains)) {
                add("로컬 파일 저장")
            }
            val emailSendNegated = listOf("이메일은 보내지", "메일은 보내지", "실제 이메일은 보내지", "실제 메일은 보내지").any(normalized::contains)
            if (!emailSendNegated && listOf("이메일 전송", "메일로 전송", "메일 발송", "email delivery", "이메일로 보내").any(normalized::contains)) {
                add("이메일 전송")
            }
            capability(normalized, "Google Workspace 쓰기", "google drive", "구글 드라이브", "google sheets", "구글 시트", "google calendar", "구글 캘린더", "microsoft forms")
            capability(normalized, "CRM 쓰기", "hubspot", "salesforce", "crm 상태", "crm에", "crm으로", "crm 상담", "crm 반영", "연락처를 만들")
            capability(normalized, "개발 도구 쓰기·배포", "jira", "github", "커밋", "자동 배포", "운영 서버까지")
            capability(normalized, "회계·상거래 시스템 쓰기", "quickbooks", "shopify", "erp")
            val socialPublishNegated = listOf("실제 게시하지", "게시하지 마", "게시하지 않").any(normalized::contains)
            if (!socialPublishNegated) capability(normalized, "소셜 네트워크 게시", "instagram", "linkedin", "자동 게시", "실제 게시")
            capability(normalized, "외부 메신저 전송", "whatsapp", "telegram", "twilio", "sms 발송")
            capability(normalized, "데이터베이스 ETL", "postgres", "mysql", "변환 적재")
            capability(normalized, "웹 스크래핑", "스크래핑", "크롤링")
            capability(normalized, "외부 API 연동", "기상 api", "날씨 api", "stripe")
            capability(normalized, "전자서명", "docusign", "전자서명")
            capability(normalized, "Microsoft 메일 쓰기", "outlook", "공유 사서함")
            capability(normalized, "계정·권한 프로비저닝", "workday", "okta", "aws iam", "권한을 자동 생성")
            capability(normalized, "외부 저장소 백업", "s3 버킷", "자동 백업", "백업해서")
            capability(normalized, "데스크톱 RPA", "rpa", "키보드로 자동 입력", "화면에 키보드")
        }.distinct()
    }

    private fun MutableList<String>.capability(text: String, label: String, vararg markers: String) {
        if (markers.any(text::contains)) add(label)
    }

    private fun requireSupported(text: String) {
        val required = unsupportedCapabilities(text)
        if (required.isEmpty()) return
        throw BadRequestException(
            errorCode,
            "사용자 목표를 구현하려면 표준 노드 또는 연결이 추가로 필요합니다: ${required.joinToString(", ")}. " +
                "요청을 거절하거나 다른 자동화로 바꾸지 않았으며, 카탈로그 등록과 Connector 검증 전에는 실행하지 않습니다.",
        )
    }

    private fun requirementText(requirement: AutomationRequirement) = listOf(
        requirement.objective,
        requirement.trigger,
        requirement.inputs.joinToString(" "),
        requirement.outputs.joinToString(" "),
        requirement.steps.joinToString(" "),
        requirement.decisions.joinToString(" "),
        requirement.exceptions.joinToString(" "),
    ).joinToString("\n")
}
