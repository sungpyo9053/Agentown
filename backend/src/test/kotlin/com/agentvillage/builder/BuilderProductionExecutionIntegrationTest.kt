package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.BuilderApprovalRepository
import com.agentvillage.builder.infrastructure.BuilderRunRepository
import com.agentvillage.builder.infrastructure.BuilderStepRunRepository
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.connector.notion.application.NotionConnectorService
import com.agentvillage.connector.notion.domain.NotionPageWriteStatus
import com.agentvillage.connector.notion.infrastructure.NotionPageWriteRepository
import com.agentvillage.connector.notion.infrastructure.NotionCreatedPage
import com.agentvillage.connector.notion.infrastructure.NotionOauthGateway
import com.agentvillage.connector.notion.infrastructure.NotionOauthResult
import com.agentvillage.connector.notion.infrastructure.NotionTokenInvalidException
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.context.event.EventListener
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.client.HttpClientErrorException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "connectors.notion.enabled=true",
    "connectors.notion.client-id=notion-client",
    "connectors.notion.client-secret=notion-secret",
    "connectors.notion.redirect-uri=https://reviewdr.kr/api/connectors/notion/oauth/callback",
    "builder.production.reconciliation.stale-after-seconds=60",
    "builder.production.reconciliation.interval-ms=86400000",
    "builder.production.reconciliation.batch-size=2",
    "spring.task.scheduling.enabled=false",
])
class BuilderProductionExecutionIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var builder: BuilderService
    @Autowired lateinit var production: BuilderProductionExecutionService
    @Autowired lateinit var notion: NotionConnectorService
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var runs: BuilderRunRepository
    @Autowired lateinit var approvals: BuilderApprovalRepository
    @Autowired lateinit var steps: BuilderStepRunRepository
    @Autowired lateinit var pageWrites: NotionPageWriteRepository
    @Autowired lateinit var reconciler: BuilderPublishingReconciler
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @MockBean lateinit var generator: ProductionContentGenerator
    @MockBean lateinit var gateway: NotionOauthGateway

    @Test
    fun `startup and scheduled reconciliation is bounded and preserves evidence while skipping fresh or ineligible records`() {
        val owner = account("publishing-recovery")
        val active = activate(owner, design(owner))
        val connectionId = connectNotion(owner, "publishing-recovery-workspace")
        whenever(generator.generate(any())).thenReturn(validOutput())
        val stale = (1..3).map { index ->
            preparePublishing(owner, active, connectionId, "stale-$index", staleRun = true, staleRequest = true)
        }
        val freshRun = preparePublishing(owner, active, connectionId, "fresh-run", staleRun = false, staleRequest = false)
        val freshRequest = preparePublishing(owner, active, connectionId, "fresh-request", staleRun = true, staleRequest = false)
        val waiting = production.start(
            owner,
            active.workflowId,
            ProductionRunRequest(mapOf("topic" to "승인 대기 보존"), connectionId, "12345678901234567890123456789012"),
            "waiting-${UUID.randomUUID()}",
        )
        await(owner, waiting.id, BuilderRunStatus.WAITING_APPROVAL)
        makeOld("builder_runs", waiting.id)

        val preserved = publishingEvidence(stale.first().runId)
        val startup = BuilderPublishingReconciler::class.java.getDeclaredMethod("reconcileAtStartup")
        assertThat(startup.getAnnotation(EventListener::class.java).value.toList()).contains(ApplicationReadyEvent::class)
        reconciler.reconcileAtStartup()
        assertThat(stale.count { production.get(owner, it.runId).status == BuilderRunStatus.AMBIGUOUS }).isEqualTo(2)

        val scheduled = BuilderPublishingReconciler::class.java.getDeclaredMethod("reconcileOnSchedule")
        assertThat(scheduled.getAnnotation(Scheduled::class.java).fixedDelayString)
            .isEqualTo("\${builder.production.reconciliation.interval-ms:30000}")
        reconciler.reconcileOnSchedule()
        assertThat(stale.map { production.get(owner, it.runId).status }).containsOnly(BuilderRunStatus.AMBIGUOUS)
        assertThat(production.get(owner, freshRun.runId).status).isEqualTo(BuilderRunStatus.PUBLISHING)
        assertThat(production.get(owner, freshRequest.runId).status).isEqualTo(BuilderRunStatus.PUBLISHING)
        assertThat(pageWrites.findById(freshRun.requestId).orElseThrow().status).isEqualTo(NotionPageWriteStatus.PUBLISHING)
        assertThat(pageWrites.findById(freshRequest.requestId).orElseThrow().status).isEqualTo(NotionPageWriteStatus.PUBLISHING)
        assertThat(production.get(owner, waiting.id).status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)

        val recovered = production.get(owner, stale.first().runId)
        assertThat(recovered.currentNodeId).isNull()
        assertThat(recovered.failureCode).isEqualTo("NOTION_PAGE_CREATE_AMBIGUOUS")
        assertThat(recovered.failureMessage).contains("중복 페이지 생성을 막기 위해").contains("대상 Notion 페이지")
        assertThat(recovered.steps.last().status).isEqualTo(BuilderStepStatus.FAILED)
        assertThat(recovered.steps.last().errorMessage).isEqualTo(recovered.failureMessage)
        assertThat(publishingEvidence(stale.first().runId)).isEqualTo(preserved)
        stale.forEach { fixture ->
            val request = pageWrites.findById(fixture.requestId).orElseThrow()
            assertThat(request.status).isEqualTo(NotionPageWriteStatus.AMBIGUOUS)
            assertThat(request.failureCode).isEqualTo("NOTION_PAGE_CREATE_AMBIGUOUS")
            assertThat(request.failureMessage).isEqualTo(production.get(owner, fixture.runId).failureMessage)
        }
        verify(gateway, times(0)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `concurrent stale reconciliation is exact once and recovered publication is terminal`() {
        val owner = account("publishing-race")
        val active = activate(owner, design(owner))
        val connectionId = connectNotion(owner, "publishing-concurrent-recovery-workspace")
        whenever(generator.generate(any())).thenReturn(validOutput())
        val fixture = preparePublishing(owner, active, connectionId, "concurrent-recovery", staleRun = true, staleRequest = true)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val recovered = listOf(
                pool.submit<Int> { production.reconcileStalePublishing() },
                pool.submit<Int> { production.reconcileStalePublishing() },
            ).sumOf { it.get(10, TimeUnit.SECONDS) }
            assertThat(recovered).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
        assertThat(production.get(owner, fixture.runId).status).isEqualTo(BuilderRunStatus.AMBIGUOUS)
        assertThat(production.decide(owner, fixture.runId, true, "repeat-approval").status).isEqualTo(BuilderRunStatus.AMBIGUOUS)
        assertThatThrownBy { production.retry(owner, fixture.runId, "ambiguous-retry") }
            .isInstanceOf(ConflictException::class.java)
        assertThat(production.reconcileStalePublishing()).isZero()
        verify(gateway, times(0)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `late provider completion cannot replace stale ambiguous recovery`() {
        val owner = account("publishing-late")
        val active = activate(owner, design(owner))
        val connectionId = connectNotion(owner, "publishing-late-completion-workspace")
        whenever(generator.generate(any())).thenReturn(validOutput())
        val enteredProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        whenever(gateway.createPage(any(), any(), any(), any())).thenAnswer {
            enteredProvider.countDown()
            check(releaseProvider.await(10, TimeUnit.SECONDS))
            NotionCreatedPage("late-page", "https://notion.so/late-page")
        }
        val run = production.start(
            owner,
            active.workflowId,
            ProductionRunRequest(mapOf("topic" to "늦은 완료"), connectionId, "12345678901234567890123456789012"),
            "late-start-${UUID.randomUUID()}",
        )
        await(owner, run.id, BuilderRunStatus.WAITING_APPROVAL)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val completion = pool.submit<RunView> { production.decide(owner, run.id, true, "late-approval") }
            assertThat(enteredProvider.await(10, TimeUnit.SECONDS)).isTrue()
            val requestId = requireNotNull(runs.findById(run.id).orElseThrow().externalWriteRequestId)
            makeOld("builder_runs", run.id)
            makeOld("notion_page_write_requests", requestId)
            assertThat(production.reconcileStalePublishing()).isEqualTo(1)
            releaseProvider.countDown()
            assertThat(completion.get(10, TimeUnit.SECONDS).status).isEqualTo(BuilderRunStatus.AMBIGUOUS)
        } finally {
            releaseProvider.countDown()
            pool.shutdownNow()
        }
        val recovered = production.get(owner, run.id)
        assertThat(recovered.status).isEqualTo(BuilderRunStatus.AMBIGUOUS)
        assertThat(recovered.output).doesNotContainKeys("notionPageId", "notionUrl")
        assertThat(pageWrites.findById(requireNotNull(runs.findById(run.id).orElseThrow().externalWriteRequestId)).orElseThrow().status)
            .isEqualTo(NotionPageWriteStatus.AMBIGUOUS)
        verify(gateway, times(1)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `workflow production history is owner scoped newest first bounded and destination safe`() {
        val owner = account("history-owner")
        val stranger = account("history-stranger")
        val active = activate(owner, design(owner))
        mvc.perform(
            get("/api/builder/workflows/${active.workflowId}/production-runs").with(user(principal(owner))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        val connectionId = UUID.randomUUID()
        val baseTime = Instant.parse("2026-08-29T00:00:00Z")
        val productionStatuses = listOf(
            BuilderRunStatus.QUEUED,
            BuilderRunStatus.GENERATING,
            BuilderRunStatus.WAITING_APPROVAL,
            BuilderRunStatus.PUBLISHING,
            BuilderRunStatus.FAILED,
            BuilderRunStatus.AMBIGUOUS,
            BuilderRunStatus.SUCCEEDED,
        )
        val createdIds = (0..20).map { index ->
            val run = runs.saveAndFlush(BuilderRun(
                workspaceId = active.workspaceId,
                workflowId = active.workflowId,
                workflowVersionId = requireNotNull(active.currentVersionId),
                templateVersionId = null,
                runMode = BuilderRunMode.PRODUCTION,
                status = productionStatuses[index % productionStatuses.size],
                inputJson = mapOf("token" to "masked-input-token"),
                outputJson = mapOf("title" to "기록 $index"),
                idempotencyKey = "production-history-$index-${UUID.randomUUID()}",
                destinationJson = mapOf(
                    "provider" to "NOTION",
                    "connectionId" to connectionId.toString(),
                    "parentPageId" to "parent-page-must-not-leak",
                    "accessToken" to "destination-token-must-not-leak",
                ),
            ))
            jdbc.update(
                "update builder_runs set created_at=?, updated_at=? where id=?",
                Timestamp.from(baseTime.plusSeconds(index.toLong())),
                Timestamp.from(baseTime.plusSeconds(index.toLong())),
                run.id,
            )
            run.id
        }
        val simulation = runs.saveAndFlush(BuilderRun(
            workspaceId = active.workspaceId,
            workflowId = active.workflowId,
            workflowVersionId = requireNotNull(active.currentVersionId),
            templateVersionId = null,
            runMode = BuilderRunMode.SIMULATION,
            status = BuilderRunStatus.SUCCEEDED,
            inputJson = mapOf("message" to "exclude me"),
            idempotencyKey = "production-history-simulation-${UUID.randomUUID()}",
        ))
        jdbc.update(
            "update builder_runs set created_at=?, updated_at=? where id=?",
            Timestamp.from(baseTime.plusSeconds(100)),
            Timestamp.from(baseTime.plusSeconds(100)),
            simulation.id,
        )

        val response = mvc.perform(
            get("/api/builder/workflows/${active.workflowId}/production-runs").with(user(principal(owner))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(20))
            .andExpect(jsonPath("$[0].destinationConnectionId").value(connectionId.toString()))
            .andExpect(jsonPath("$[0].run.mode").value("PRODUCTION"))
            .andExpect(jsonPath("$[0].run.steps").isArray)
            .andReturn().response.contentAsString
        val body = mapper.readTree(response)
        assertThat(body.map { UUID.fromString(it["run"]["id"].asText()) })
            .containsExactlyElementsOf(createdIds.asReversed().take(20))
        assertThat(response).doesNotContain("parent-page-must-not-leak")
        assertThat(response).doesNotContain("destination-token-must-not-leak")
        assertThat(response).doesNotContain("masked-input-token")
        assertThat(response).doesNotContain(simulation.id.toString())

        val strangerWorkflow = design(stranger)
        mvc.perform(
            get("/api/builder/workflows/${strangerWorkflow.workflowId}/production-runs").with(user(principal(owner))),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `active approved graph previews then publishes its notion node exactly once`() {
        val owner = account("production-owner")
        val stranger = account("production-stranger")
        val designed = design(owner)
        val connectionId = connectNotion(owner, "prod-workspace-1")
        val strangerConnectionId = connectNotion(stranger, "prod-workspace-stranger")
        val request = ProductionRunRequest(
            input = mapOf("topic" to "주간 개발 상태", "sourceText" to "API 연결 완료", "nested" to mapOf("apiKey" to "never-store")),
            notionConnectionId = connectionId,
            notionParentPageId = "12345678901234567890123456789012",
        )

        assertThatThrownBy { production.start(owner, designed.workflowId, request, "production-before-active") }
            .isInstanceOf(ConflictException::class.java)

        val active = activate(owner, designed)
        assertThat(active.status).isEqualTo(WorkflowStatus.ACTIVE)
        assertThat(active.graph!!.nodes.map { it.nodeType }).contains("notion.create_page")
        whenever(generator.generate(any())).thenReturn(validOutput())
        whenever(gateway.createPage(any(), any(), any(), any()))
            .thenReturn(NotionCreatedPage("created-page-1", "https://notion.so/created-page-1"))

        assertThatThrownBy {
            production.start(owner, active.workflowId, request.copy(notionConnectionId = strangerConnectionId), "production-foreign-connection")
        }.isInstanceOf(NotFoundException::class.java)

        val queued = production.start(owner, active.workflowId, request, "production-start-1")
        assertThat(production.start(owner, active.workflowId, request.copy(input = mapOf("changed" to true)), "production-start-1").id).isEqualTo(queued.id)
        val waiting = await(owner, queued.id, BuilderRunStatus.WAITING_APPROVAL)
        val approvedGraphNodeIds = active.graph!!.nodes.map { it.id }.toSet()
        assertThat(waiting.mode).isEqualTo(BuilderRunMode.PRODUCTION)
        assertThat(waiting.attemptCount).isEqualTo(1)
        assertThat(waiting.output).containsEntry("title", "주간 개발 상태 보고서")
        assertThat(waiting.steps.map { it.nodeId }).allMatch(approvedGraphNodeIds::contains)
        assertThat(waiting.steps).noneSatisfy { step -> assertThat(step.nodeId).startsWith("production-") }
        verify(gateway, times(0)).createPage(any(), any(), any(), any())
        assertThatThrownBy { production.get(stranger, queued.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { production.decide(stranger, queued.id, true, "production-foreign-approval") }.isInstanceOf(NotFoundException::class.java)
        verify(gateway, times(0)).createPage(any(), any(), any(), any())

        val succeeded = production.decide(owner, queued.id, true, "production-approve-1")
        assertThat(succeeded.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(active.graph!!.nodes.single { it.nodeType == "notion.create_page" }.id).isEqualTo(succeeded.steps.last().nodeId)
        assertThat(succeeded.output).containsEntry("notionUrl", "https://notion.so/created-page-1")
        assertThat(production.decide(owner, queued.id, true, "production-approve-1").status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        verify(gateway, times(1)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `provider failures are persisted and retry is capped at three generation attempts`() {
        val owner = account("production-retry")
        val active = activate(owner, design(owner))
        val connectionId = connectNotion(owner, "prod-workspace-retry")
        whenever(generator.generate(any())).thenReturn(validOutput())
        whenever(gateway.createPage(any(), any(), any(), any())).thenThrow(knownRejected())
        val request = ProductionRunRequest(mapOf("topic" to "장애 검증", "sourceText" to "provider unavailable"), connectionId, "12345678901234567890123456789012")
        val runId = production.start(owner, active.workflowId, request, "retry-start").id

        repeat(3) { index ->
            val waiting = await(owner, runId, BuilderRunStatus.WAITING_APPROVAL)
            assertThat(waiting.attemptCount).isEqualTo(index + 1)
            val failed = production.decide(owner, runId, true, "retry-approval-${index + 1}")
            assertThat(failed.status).isEqualTo(BuilderRunStatus.FAILED)
            assertThat(failed.failureCode).isEqualTo("NOTION_PAGE_CREATE_REJECTED")
            assertThat(failed.failureMessage).contains("연결 권한과 상위 페이지 공유 상태")
            assertThat(failed.failureMessage).doesNotContain("provider unavailable")
            if (index < 2) production.retry(owner, runId, "retry-${index + 1}")
        }
        assertThatThrownBy { production.retry(owner, runId, "retry-over-limit") }
            .isInstanceOf(ConflictException::class.java)
        verify(gateway, times(3)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `expired notion publication fails durably then reconnects and explicitly retries the preserved run`() {
        val owner = account("production-expired")
        val active = activate(owner, design(owner))
        val externalId = "prod-workspace-expired"
        val connectionId = connectNotion(owner, externalId)
        whenever(generator.generate(any())).thenReturn(validOutput())
        whenever(gateway.createPage(any(), any(), any(), any())).thenThrow(NotionTokenInvalidException())
        whenever(gateway.refresh("refresh-$externalId")).thenThrow(knownRejected())
        val run = production.start(
            owner,
            active.workflowId,
            ProductionRunRequest(mapOf("topic" to "만료 복구", "sourceText" to "보존할 원문"), connectionId, "12345678901234567890123456789012"),
            "expired-start",
        )
        val waiting = await(owner, run.id, BuilderRunStatus.WAITING_APPROVAL)

        val failed = production.decide(owner, waiting.id, true, "expired-approval-1")
        assertThat(failed.status).isEqualTo(BuilderRunStatus.FAILED)
        assertThat(failed.failureCode).isEqualTo("NOTION_CONNECTION_EXPIRED")
        assertThat(failed.failureMessage).isEqualTo("Notion 연결이 만료되었습니다. 업무 연결에서 다시 연결한 뒤 재시도해 주세요.")
        assertThat(failed.output).containsEntry("title", "주간 개발 상태 보고서")
        assertThat(failed.steps.last().status.name).isEqualTo("FAILED")
        assertThat(failed.steps.last().errorMessage).contains("업무 연결에서 다시 연결")
        assertThat(notion.status(owner).connections.single { it.id == connectionId }.status.name).isEqualTo("INVALID")
        verify(gateway, times(1)).createPage(any(), any(), any(), any())

        val preservedRun = production.get(owner, run.id)
        val preservedWrite = jdbc.queryForMap(
            "select id, status, notion_page_id, notion_url, failure_code, failure_message from notion_page_write_requests where id=(select external_write_request_id from builder_runs where id=?)",
            run.id,
        )
        assertThatThrownBy { production.retry(owner, run.id, "expired-retry-before-reconnect") }
            .isInstanceOfSatisfying(ConflictException::class.java) { error ->
                assertThat(error.code).isEqualTo("NOTION_CONNECTION_EXPIRED")
                assertThat(error.message).isEqualTo("Notion 연결이 만료되었습니다. 업무 연결에서 다시 연결한 뒤 재시도해 주세요.")
            }
        assertThat(production.get(owner, run.id)).isEqualTo(preservedRun)
        assertThat(jdbc.queryForMap(
            "select id, status, notion_page_id, notion_url, failure_code, failure_message from notion_page_write_requests where id=(select external_write_request_id from builder_runs where id=?)",
            run.id,
        )).isEqualTo(preservedWrite)
        verify(gateway, times(1)).createPage(any(), any(), any(), any())

        val reconnectedId = connectNotion(owner, externalId)
        assertThat(reconnectedId).isEqualTo(connectionId)
        assertThat(notion.status(owner).connections.count { it.workspaceId == externalId }).isEqualTo(1)
        assertThat(notion.status(owner).connections.single { it.id == connectionId }.status.name).isEqualTo("ACTIVE")
        whenever(gateway.createPage(any(), any(), any(), any()))
            .thenReturn(NotionCreatedPage("recovered-page", "https://notion.so/recovered-page"))

        assertThat(production.retry(owner, run.id, "expired-retry-after-reconnect").status).isEqualTo(BuilderRunStatus.QUEUED)
        val recoveredPreview = await(owner, run.id, BuilderRunStatus.WAITING_APPROVAL)
        assertThat(recoveredPreview.attemptCount).isEqualTo(2)
        val succeeded = production.decide(owner, run.id, true, "expired-approval-2")
        assertThat(succeeded.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(succeeded.output).containsEntry("notionUrl", "https://notion.so/recovered-page")
        verify(gateway, times(2)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `concurrent approvals claim one durable publishing intent and dispatch once`() {
        val owner = account("production-concurrent")
        val active = activate(owner, design(owner))
        val connectionId = connectNotion(owner, "prod-workspace-concurrent")
        whenever(generator.generate(any())).thenReturn(validOutput())
        val enteredProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        whenever(gateway.createPage(any(), any(), any(), any())).thenAnswer {
            enteredProvider.countDown()
            check(releaseProvider.await(10, TimeUnit.SECONDS))
            NotionCreatedPage("concurrent-page", "https://notion.so/concurrent-page")
        }
        val run = production.start(
            owner, active.workflowId,
            ProductionRunRequest(mapOf("topic" to "동시 승인"), connectionId, "12345678901234567890123456789012"),
            "concurrent-start",
        )
        await(owner, run.id, BuilderRunStatus.WAITING_APPROVAL)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<RunView> { production.decide(owner, run.id, true, "concurrent-approval") }
            assertThat(enteredProvider.await(10, TimeUnit.SECONDS)).isTrue()
            val duplicate = pool.submit<RunView> { production.decide(owner, run.id, true, "concurrent-approval") }.get(10, TimeUnit.SECONDS)
            assertThat(duplicate.status).isEqualTo(BuilderRunStatus.PUBLISHING)
            releaseProvider.countDown()
            assertThat(first.get(10, TimeUnit.SECONDS).status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        } finally {
            releaseProvider.countDown()
            pool.shutdownNow()
        }
        verify(gateway, times(1)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `ambiguous post-dispatch outcome is durable and cannot be retried or redispatched`() {
        val owner = account("production-ambiguous")
        val active = activate(owner, design(owner))
        val connectionId = connectNotion(owner, "prod-workspace-ambiguous")
        whenever(generator.generate(any())).thenReturn(validOutput())
        val providerCreates = AtomicInteger()
        whenever(gateway.createPage(any(), any(), any(), any())).thenAnswer {
            providerCreates.incrementAndGet()
            throw IllegalStateException("response lost after provider accepted the page")
        }
        val run = production.start(
            owner, active.workflowId,
            ProductionRunRequest(mapOf("topic" to "모호한 결과"), connectionId, "12345678901234567890123456789012"),
            "ambiguous-start",
        )
        await(owner, run.id, BuilderRunStatus.WAITING_APPROVAL)

        val ambiguous = production.decide(owner, run.id, true, "ambiguous-approval")
        assertThat(ambiguous.status).isEqualTo(BuilderRunStatus.AMBIGUOUS)
        assertThat(ambiguous.failureCode).isEqualTo("NOTION_PAGE_CREATE_AMBIGUOUS")
        assertThat(ambiguous.failureMessage).contains("재시도할 수 없습니다")
        assertThat(production.decide(owner, run.id, true, "ambiguous-approval").status).isEqualTo(BuilderRunStatus.AMBIGUOUS)
        assertThatThrownBy { production.retry(owner, run.id, "ambiguous-retry") }
            .isInstanceOf(ConflictException::class.java)
        assertThat(providerCreates.get()).isEqualTo(1)
        verify(gateway, times(1)).createPage(any(), any(), any(), any())
    }

    @Test
    fun `invalid generated structure fails before preview approval or external write`() {
        val owner = account("production-invalid")
        val active = activate(owner, design(owner))
        val connectionId = connectNotion(owner, "prod-workspace-invalid")
        whenever(generator.generate(any())).thenReturn(ProductionContentOutput(title = "", paragraphs = emptyList(), qualityChecks = emptyMap()))
        val run = production.start(
            owner, active.workflowId,
            ProductionRunRequest(mapOf("topic" to "잘못된 결과"), connectionId, "12345678901234567890123456789012"),
            "invalid-generation-start",
        )
        val failed = await(owner, run.id, BuilderRunStatus.FAILED)
        assertThat(failed.failureCode).isEqualTo("PRODUCTION_CONTENT_INVALID")
        assertThat(failed.pendingApprovalId).isNull()
        assertThat(failed.output).isNull()
        verify(gateway, times(0)).createPage(any(), any(), any(), any())
    }

    private fun design(ownerId: UUID): BuilderSnapshot {
        val suffix = UUID.randomUUID().toString().take(8)
        var snapshot = builder.createConversation(ownerId, "production-conversation-$suffix")
        snapshot = builder.sendMessage(
            ownerId,
            snapshot.conversationId,
            "글쓰기 자동화를 수동으로 시작하고 사용자가 제공한 주제와 원문만 사용해 한국어 보고서를 작성한다. 담당자 승인 후 Notion 페이지로 저장한다.",
            "production-message-$suffix",
        )
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        return builder.decideDesign(ownerId, snapshot.workflowId, true, "production-design-$suffix")
    }

    private fun activate(ownerId: UUID, snapshot: BuilderSnapshot): BuilderSnapshot {
        val suffix = UUID.randomUUID().toString().take(8)
        var run = builder.startSimulation(ownerId, snapshot.workflowId, mapOf("topic" to "검증", "sourceText" to "근거 원문"), "production-simulation-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)
        run = builder.decideExecution(ownerId, run.id, true, "production-simulation-approval-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        return builder.activate(ownerId, snapshot.workflowId, "production-activation-$suffix")
    }

    private fun connectNotion(ownerId: UUID, externalId: String): UUID {
        whenever(gateway.exchange(any(), any())).thenReturn(NotionOauthResult("access-$externalId", "refresh-$externalId", "bot-$externalId", externalId, "검증 Notion"))
        val authorizationUrl = notion.start(ownerId).authorizationUrl
        val state = URI(authorizationUrl).rawQuery.split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }.getValue("state")
        return notion.complete("code-$externalId", state, null).id
    }

    private data class PublishingFixture(val runId: UUID, val requestId: UUID)

    private fun preparePublishing(
        ownerId: UUID,
        active: BuilderSnapshot,
        connectionId: UUID,
        suffix: String,
        staleRun: Boolean,
        staleRequest: Boolean,
    ): PublishingFixture {
        val started = production.start(
            ownerId,
            active.workflowId,
            ProductionRunRequest(
                mapOf("topic" to "복구 $suffix", "sourceText" to "보존할 원문 $suffix"),
                connectionId,
                "12345678901234567890123456789012",
            ),
            "publishing-$suffix-${UUID.randomUUID()}",
        )
        await(ownerId, started.id, BuilderRunStatus.WAITING_APPROVAL)
        val run = runs.findById(started.id).orElseThrow()
        val requestId = requireNotNull(run.externalWriteRequestId)
        val approval = approvals.findByRunIdAndStatus(run.id, ApprovalStatus.PENDING)!!
        approval.status = ApprovalStatus.APPROVED
        approval.idempotencyKey = "publishing-approval-$suffix"
        approval.decidedBy = ownerId
        approval.decidedAt = Instant.now()
        approvals.save(approval)
        val approvalStep = steps.findAllByRunIdOrderBySequenceNo(run.id).last { it.status == BuilderStepStatus.WAITING_APPROVAL }
        approvalStep.status = BuilderStepStatus.SUCCEEDED
        approvalStep.outputJson = approvalStep.inputJson + ("approved" to true)
        steps.save(approvalStep)
        val writeNode = active.graph!!.nodes.single { it.nodeType == NodeType.NOTION_CREATE_PAGE.wireName }
        steps.saveAndFlush(BuilderStepRun(
            runId = run.id,
            nodeId = writeNode.id,
            nodeType = NodeType.NOTION_CREATE_PAGE.wireName,
            sequenceNo = steps.findAllByRunIdOrderBySequenceNo(run.id).maxOf { it.sequenceNo } + 1,
            status = BuilderStepStatus.RUNNING,
            inputJson = mapOf("writeRequestId" to requestId.toString()),
        ))
        run.status = BuilderRunStatus.PUBLISHING
        run.currentNodeId = writeNode.id
        runs.saveAndFlush(run)
        val request = pageWrites.findById(requestId).orElseThrow()
        request.approvalIdempotencyKey = "publishing-approval-$suffix"
        request.approvedBy = ownerId
        request.approvedAt = Instant.now()
        request.status = NotionPageWriteStatus.PUBLISHING
        pageWrites.saveAndFlush(request)
        if (staleRun) makeOld("builder_runs", run.id)
        if (staleRequest) makeOld("notion_page_write_requests", request.id)
        return PublishingFixture(run.id, request.id)
    }

    private fun makeOld(table: String, id: UUID) {
        require(table in setOf("builder_runs", "notion_page_write_requests"))
        jdbc.update("update $table set updated_at=? where id=?", Timestamp.from(Instant.now().minusSeconds(600)), id)
    }

    private fun publishingEvidence(runId: UUID): Map<String, Any> = mapOf(
        "run" to jdbc.queryForMap(
            "select input_json::text, output_json::text, destination_json::text, external_write_request_id, attempt_count from builder_runs where id=?",
            runId,
        ),
        "approval" to jdbc.queryForMap(
            "select status, idempotency_key, decided_by, decided_at from builder_approvals where run_id=?",
            runId,
        ),
        "successfulSteps" to jdbc.queryForList(
            "select node_id, node_type, sequence_no, input_json::text, output_json::text from builder_step_runs where run_id=? and status='SUCCEEDED' order by sequence_no",
            runId,
        ),
    )

    private fun validOutput() = ProductionContentOutput(
        title = "주간 개발 상태 보고서",
        paragraphs = listOf("API 연결 완료", "승인 후에만 Notion에 기록합니다."),
        evidence = listOf("사용자 입력: API 연결 완료"),
        qualityChecks = mapOf(
            "factsSeparatedFromInterpretation" to true,
            "unsupportedClaimsAbsent" to true,
            "readyForHumanReview" to true,
        ),
    )

    private fun knownRejected(): HttpClientErrorException = HttpClientErrorException.create(
        HttpStatus.BAD_REQUEST,
        "provider rejected request",
        HttpHeaders.EMPTY,
        ByteArray(0),
        StandardCharsets.UTF_8,
    )

    private fun account(prefix: String): UUID = identities.register(
        RegisterUserCommand("$prefix-${UUID.randomUUID()}@example.com", "password123", "${prefix.replace('-', '_')}_${UUID.randomUUID().toString().take(8)}", prefix),
    ).id

    private fun principal(ownerId: UUID) = AuthenticatedUser(ownerId, "$ownerId@example.com", "unused", true)

    private fun await(ownerId: UUID, runId: UUID, expected: BuilderRunStatus): RunView {
        repeat(100) {
            val current = production.get(ownerId, runId)
            if (current.status == expected) return current
            if (current.status == BuilderRunStatus.FAILED && expected != BuilderRunStatus.FAILED) error("run failed: ${current.failureCode} ${current.failureMessage}")
            Thread.sleep(50)
        }
        error("run $runId did not reach $expected")
    }
}
