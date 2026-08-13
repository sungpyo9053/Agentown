package com.agentvillage.identity.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.identity.domain.EmailVerification
import com.agentvillage.identity.infrastructure.EmailVerificationRepository
import com.agentvillage.identity.infrastructure.UserAccountRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

interface EmailGateway {
    fun send(to: String, subject: String, body: String)
}

@Service
@ConditionalOnProperty(name = ["auth.email.provider"], havingValue = "stub", matchIfMissing = true)
class StubEmailGateway : EmailGateway {
    override fun send(to: String, subject: String, body: String) = Unit
}

data class EmailCodeResponse(val verificationId: UUID, val expiresInSeconds: Long, val developmentCode: String? = null)
data class VerifiedEmail(val email: String, val verifiedAt: Instant)
data class TemporaryPasswordResponse(val message: String, val developmentTemporaryPassword: String? = null)

@Service
class EmailAuthService(
    private val verifications: EmailVerificationRepository,
    private val users: UserAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val emailGateway: EmailGateway,
    @Value("\${auth.email.expose-development-values:false}") private val exposeDevelopmentValues: Boolean,
) {
    private val random = SecureRandom()

    @Transactional
    fun sendCode(emailInput: String): EmailCodeResponse {
        val email = normalize(emailInput)
        if (users.existsByEmailIgnoreCase(email)) {
            throw ConflictException("EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다.")
        }
        val code = (random.nextInt(900000) + 100000).toString()
        val verification = verifications.save(
            EmailVerification(
                email = email,
                codeHash = hash(code),
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            ),
        )
        emailGateway.send(email, "[Agentown] 이메일 인증번호", "인증번호는 $code 입니다. 10분 안에 입력해 주세요.")
        return EmailCodeResponse(verification.id, 600, if (exposeDevelopmentValues) code else null)
    }

    @Transactional
    fun verify(verificationId: UUID, code: String) {
        val verification = verifications.findById(verificationId).orElseThrow {
            BadRequestException("EMAIL_VERIFICATION_NOT_FOUND", "인증 요청을 찾을 수 없습니다.")
        }
        if (verification.expiresAt.isBefore(Instant.now())) {
            throw BadRequestException("EMAIL_CODE_EXPIRED", "인증번호가 만료되었습니다.")
        }
        if (verification.verifiedAt != null) return
        if (!MessageDigest.isEqual(verification.codeHash.toByteArray(), hash(code.trim()).toByteArray())) {
            throw BadRequestException("EMAIL_CODE_INVALID", "인증번호가 올바르지 않습니다.")
        }
        verification.verifiedAt = Instant.now()
    }

    @Transactional(readOnly = true)
    fun requireVerified(verificationId: UUID, emailInput: String): VerifiedEmail {
        val email = normalize(emailInput)
        val verification = verifications.findById(verificationId).orElseThrow {
            BadRequestException("EMAIL_VERIFICATION_NOT_FOUND", "이메일 인증을 먼저 완료해 주세요.")
        }
        if (verification.verifiedAt == null || verification.expiresAt.isBefore(Instant.now()) || verification.consumedAt != null) {
            throw BadRequestException("EMAIL_VERIFICATION_REQUIRED", "유효한 이메일 인증이 필요합니다.")
        }
        if (verification.email != email) {
            throw BadRequestException("EMAIL_VERIFICATION_MISMATCH", "인증한 이메일과 일치하지 않습니다.")
        }
        return VerifiedEmail(email, requireNotNull(verification.verifiedAt))
    }

    @Transactional
    fun consume(verificationId: UUID) {
        verifications.findById(verificationId).orElseThrow().consumedAt = Instant.now()
    }

    @Transactional
    fun issueTemporaryPassword(emailInput: String): TemporaryPasswordResponse {
        val email = normalize(emailInput)
        val user = users.findByEmailIgnoreCase(email)
        val message = "가입된 이메일이면 임시 비밀번호를 전송했습니다."
        if (user == null) return TemporaryPasswordResponse(message)
        val temporary = temporaryPassword()
        user.passwordHash = passwordEncoder.encode(temporary)
        emailGateway.send(email, "[Agentown] 임시 비밀번호", "임시 비밀번호는 $temporary 입니다. 로그인 후 변경해 주세요.")
        return TemporaryPasswordResponse(message, if (exposeDevelopmentValues) temporary else null)
    }

    fun normalize(input: String): String {
        val email = input.trim().lowercase(Locale.ROOT)
        if (email.length > 320 || !email.matches(Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE))) {
            throw BadRequestException("EMAIL_INVALID", "올바른 이메일 주소를 입력해 주세요.")
        }
        return email
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun temporaryPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@"
        return buildString { repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
