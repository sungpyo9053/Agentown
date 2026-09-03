package com.agentvillage.connector.notion.application

import com.agentvillage.builder.application.BuilderNotionExecutionPort
import com.agentvillage.builder.application.ExternalWriteResult
import com.agentvillage.connector.notion.domain.NotionPageWriteStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class BuilderNotionExecutionAdapter(private val notion: NotionConnectorService) : BuilderNotionExecutionPort {
    override fun requireWritableConnection(ownerId: UUID, connectionId: UUID) = notion.requireWritableConnection(ownerId, connectionId)

    override fun previewPage(
        ownerId: UUID,
        connectionId: UUID,
        idempotencyKey: String,
        parentPageId: String,
        title: String,
        paragraphs: List<String>,
    ): UUID = notion.previewPage(ownerId, connectionId, idempotencyKey, NotionPagePreviewRequest(parentPageId, title, paragraphs)).id

    override fun approvePage(ownerId: UUID, requestId: UUID, idempotencyKey: String): ExternalWriteResult =
        notion.approvePage(ownerId, requestId, idempotencyKey).let { result ->
            ExternalWriteResult(
                succeeded = result.status == NotionPageWriteStatus.SUCCEEDED,
                ambiguous = result.status == NotionPageWriteStatus.AMBIGUOUS,
                externalId = result.notionPageId,
                externalUrl = result.notionUrl,
                failureCode = result.failureCode,
                failureMessage = when (result.status) {
                    NotionPageWriteStatus.FAILED -> if (result.failureCode == "NOTION_CONNECTION_EXPIRED") {
                        result.failureMessage
                    } else {
                        "Notion이 페이지 생성을 거부했습니다. 연결 권한과 상위 페이지 공유 상태를 확인한 뒤 재시도하세요."
                    }
                    NotionPageWriteStatus.AMBIGUOUS -> "Notion 요청 후 결과를 확인하지 못했습니다. 중복 방지를 위해 재시도할 수 없습니다. 대상 페이지를 확인하세요."
                    else -> null
                },
            )
        }

    override fun reconcileStalePublishing(workspaceId: UUID, requestId: UUID, staleBefore: Instant): Boolean =
        notion.reconcileStalePublishing(workspaceId, requestId, staleBefore)
}
