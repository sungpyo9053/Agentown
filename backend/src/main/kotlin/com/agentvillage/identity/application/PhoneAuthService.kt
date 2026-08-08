package com.agentvillage.identity.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.identity.domain.PhoneVerification
import com.agentvillage.identity.infrastructure.PhoneVerificationRepository
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
import java.util.UUID

interface SmsGateway {
    fun send(phone: String, message: String)
}

@Service
@ConditionalOnProperty(name = ["auth.sms.provider"], havingValue = "stub", matchIfMissing = true)
class StubSmsGateway : SmsGateway {
    override fun send(phone: String, message: String) = Unit
}

data class PhoneCodeResponse(val verificationId: UUID, val expiresInSeconds: Long, val developmentCode: String? = null)
data class VerifiedPhone(val hash: String, val masked: String, val verifiedAt: Instant)
data class TemporaryPasswordResponse(val message: String, val developmentTemporaryPassword: String? = null)

@Service
class PhoneAuthService(
    private val verifications: PhoneVerificationRepository,
    private val users: UserAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val sms: SmsGateway,
    @Value("\${auth.sms.expose-development-values:false}") private val exposeDevelopmentValues: Boolean,
) {
    private val random = SecureRandom()

    @Transactional
    fun sendCode(phoneInput: String): PhoneCodeResponse {
        val phone = normalize(phoneInput)
        val code = (random.nextInt(900000) + 100000).toString()
        val verification = verifications.save(
            PhoneVerification(
                phoneHash = hash(phone),
                codeHash = hash(code),
                expiresAt = Instant.now().plus(3, ChronoUnit.MINUTES),
            ),
        )
        sms.send(phone, "[Agentown] 휴대폰 인증번호는 $code 입니다. 3분 안에 입력해 주세요.")
        return PhoneCodeResponse(verification.id, 180, if (exposeDevelopmentValues) code else null)
    }

    @Transactional
    fun verify(verificationId: UUID, code: String) {
        val verification = verifications.findById(verificationId).orElseThrow {
            BadRequestException("PHONE_VERIFICATION_NOT_FOUND", "인증 요청을 찾을 수 없습니다.")
        }
        if (verification.expiresAt.isBefore(Instant.now())) throw BadRequestException("PHONE_CODE_EXPIRED", "인증번호가 만료되었습니다.")
        if (verification.verifiedAt != null) return
        if (!MessageDigest.isEqual(verification.codeHash.toByteArray(), hash(code.trim()).toByteArray())) {
            throw BadRequestException("PHONE_CODE_INVALID", "인증번호가 올바르지 않습니다.")
        }
        verification.verifiedAt = Instant.now()
    }

    @Transactional(readOnly = true)
    fun requireVerified(verificationId: UUID, phoneInput: String): VerifiedPhone {
        val phone = normalize(phoneInput)
        val verification = verifications.findById(verificationId).orElseThrow {
            BadRequestException("PHONE_VERIFICATION_NOT_FOUND", "휴대폰 인증을 먼저 완료해 주세요.")
        }
        if (verification.verifiedAt == null || verification.expiresAt.isBefore(Instant.now()) || verification.consumedAt != null) {
            throw BadRequestException("PHONE_VERIFICATION_REQUIRED", "유효한 휴대폰 인증이 필요합니다.")
        }
        if (verification.phoneHash != hash(phone)) throw BadRequestException("PHONE_VERIFICATION_MISMATCH", "인증한 휴대폰 번호와 일치하지 않습니다.")
        return VerifiedPhone(verification.phoneHash, mask(phone), requireNotNull(verification.verifiedAt))
    }

    @Transactional
    fun consume(verificationId: UUID) {
        verifications.findById(verificationId).orElseThrow().consumedAt = Instant.now()
    }

    @Transactional
    fun issueTemporaryPassword(loginId: String, phoneInput: String): TemporaryPasswordResponse {
        val phone = normalize(phoneInput)
        val user = users.findByHandle(loginId.trim().lowercase())
        if (user == null || user.phoneHash != hash(phone)) {
            return TemporaryPasswordResponse("아이디와 휴대폰 번호가 일치하면 임시 비밀번호를 전송했습니다.")
        }
        val temporary = temporaryPassword()
        user.passwordHash = passwordEncoder.encode(temporary)
        sms.send(phone, "[Agentown] 임시 비밀번호는 $temporary 입니다. 로그인 후 변경해 주세요.")
        return TemporaryPasswordResponse(
            "아이디와 휴대폰 번호가 일치하면 임시 비밀번호를 전송했습니다.",
            if (exposeDevelopmentValues) temporary else null,
        )
    }

    fun hashPhone(phoneInput: String): String = hash(normalize(phoneInput))

    private fun normalize(input: String): String {
        val phone = input.filter(Char::isDigit)
        if (!phone.matches(Regex("^01[016789][0-9]{7,8}$"))) {
            throw BadRequestException("PHONE_INVALID", "올바른 국내 휴대폰 번호를 입력해 주세요.")
        }
        return phone
    }

    private fun mask(phone: String) = "${phone.take(3)}-****-${phone.takeLast(4)}"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun temporaryPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@"
        return buildString { repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
