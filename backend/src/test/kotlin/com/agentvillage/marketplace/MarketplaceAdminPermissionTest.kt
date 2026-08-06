package com.agentvillage.marketplace

import com.agentvillage.common.exception.ForbiddenException
import com.agentvillage.common.domain.UserRole
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.marketplace.application.MarketplaceService
import com.agentvillage.marketplace.domain.MarketProduct
import com.agentvillage.marketplace.presentation.CreateProductRequest
import com.agentvillage.marketplace.presentation.MarketplaceController
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class MarketplaceAdminPermissionTest {
    private val service = mock(MarketplaceService::class.java)
    private val controller = MarketplaceController(service)
    private val harnessId = UUID.randomUUID()
    private val request = CreateProductRequest(harnessId, "공식 글쓰기 팀", category = "CONTENT", official = true)

    @Test
    fun `normal user cannot register official harness`() {
        val user = AuthenticatedUser(UUID.randomUUID(), "user@example.com", "hash", true, UserRole.USER)

        assertThatThrownBy { controller.create(user, request) }
            .isInstanceOf(ForbiddenException::class.java)
            .hasMessage("공식 하네스 등록은 관리자만 가능합니다.")
    }

    @Test
    fun `admin can register owned published harness as official`() {
        val admin = AuthenticatedUser(UUID.randomUUID(), "admin@agentown.local", "hash", true, UserRole.ADMIN)
        val product = MarketProduct(
            harnessVersionId = UUID.randomUUID(), creatorId = admin.userId,
            title = request.title, category = request.category, official = true,
        )
        `when`(service.create(admin.userId, harnessId, request.title, request.description, request.category, true))
            .thenReturn(product)

        val created = controller.create(admin, request)

        assertThat(created).isSameAs(product)
        verify(service).create(admin.userId, harnessId, request.title, request.description, request.category, true)
    }
}
