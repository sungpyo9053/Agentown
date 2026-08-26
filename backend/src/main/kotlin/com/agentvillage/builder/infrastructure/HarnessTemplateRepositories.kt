package com.agentvillage.builder.infrastructure

import com.agentvillage.builder.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HarnessTemplateRepository : JpaRepository<HarnessTemplate, UUID> {
    fun findByTemplateKey(templateKey: String): HarnessTemplate?
    fun findAllByStatusOrderByCategoryAscNameAsc(status: HarnessTemplateStatus): List<HarnessTemplate>
}
interface HarnessTemplateVersionRepository : JpaRepository<HarnessTemplateVersion, UUID> {
    fun findByTemplateIdAndContentHash(templateId: UUID, contentHash: String): HarnessTemplateVersion?
    fun findTopByTemplateIdOrderByVersionNoDesc(templateId: UUID): HarnessTemplateVersion?
    fun findByTemplateIdAndVersionNo(templateId: UUID, versionNo: Int): HarnessTemplateVersion?
}
interface HarnessTemplateSyncRunRepository : JpaRepository<HarnessTemplateSyncRun, UUID> {
    fun findTopByOrderByStartedAtDesc(): HarnessTemplateSyncRun?
}
