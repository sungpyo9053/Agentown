package com.agentvillage.builder.application

import java.net.URI
import java.util.UUID

/** Navigation only: a package never carries credentials or starts a paid run on open. */
object HostedPackageEntry {
    fun files(publicOrigin: String, conversationId: UUID, localOnly: Boolean = false): Map<String, String> {
        val origin = URI(publicOrigin)
        require(origin.scheme == "https" && origin.host != null && origin.rawUserInfo == null &&
            origin.rawQuery == null && origin.rawFragment == null && origin.path.orEmpty() in listOf("", "/")) {
            "Hosted package origin must be an HTTPS origin"
        }
        val url = "${publicOrigin.trimEnd('/')}/develop?session=$conversationId&panel=output"
        if (localOnly) return mapOf(
            "OPEN_IN_AGENTOWN.html" to """
                <!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; form-action 'none'">
                <title>내 PC에서 파일 제작</title><body><h1>이 패키지는 내 PC에서 파일을 제작합니다</h1>
                <p>START_HERE.md의 설치 안내와 실행 전 점검을 따라 주세요. 실제 결과 파일은 패키지의 results 폴더에 생성됩니다.</p>
                <p>서버 채팅 실행은 이 로컬 파일 제작 도구를 실행하지 않습니다. PowerPoint·Excel 앱 설치 자체는 파일 생성에 필수가 아닙니다.</p>
                <p><a href="$url" rel="noreferrer">사이트에서 에이전트 설계 확인</a></p>
                <p>설계 확인에는 이 에이전트를 만든 계정의 로그인이 필요합니다. 이 파일을 열어도 자동 실행·설치·결제하지 않습니다.</p></body></html>
            """.trimIndent(),
            "WEB_START.txt" to "이 패키지의 실제 파일 제작은 로컬 실행기 전용입니다. START_HERE.md를 확인하세요.\n설계 확인: $url\n",
        )
        return mapOf(
            "OPEN_IN_AGENTOWN.html" to """
                <!doctype html>
                <html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; form-action 'none'">
                <title>Agentown에서 실행</title>
                <body><h1>설치 없이 에이전트 사용하기</h1>
                <p><a href="$url" rel="noreferrer">Agentown에서 내 에이전트 열기</a></p>
                <p>인터넷 연결과 이 에이전트를 만든 Agentown 계정의 로그인이 필요합니다. Python 설치나 별도 AI 서비스 로그인은 필요하지 않습니다.</p>
                <p>사이트에 저장된 최신 설계를 엽니다. 입력 자료와 설계 버전을 확인한 뒤 직접 실행하세요. 파일을 여는 것만으로 실행하거나 결제하지 않습니다.</p>
                <p>이 파일은 오프라인 실행기가 아닙니다. 로컬 실행이 필요하면 START_HERE.md의 개발자용 실행 안내를 확인하세요.</p></body></html>
            """.trimIndent(),
            "WEB_START.txt" to """
                설치 없이 사용: OPEN_IN_AGENTOWN.html을 열고 링크를 누르세요.
                링크가 열리지 않으면 브라우저 주소창에 아래 주소를 붙여넣으세요.
                $url

                에이전트를 만든 Agentown 계정과 인터넷 연결이 필요합니다.
                사이트에 저장된 최신 설계를 사용하며, 다운로드 시점 버전의 오프라인 실행과는 다릅니다.
                자료 입력 후 실행 버튼을 직접 눌러야 합니다. 계정 비밀번호나 AI 인증 정보는 이 패키지에 포함되지 않습니다.
            """.trimIndent(),
        )
    }
}
