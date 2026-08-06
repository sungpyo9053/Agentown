package com.agentvillage.common.exception

import org.springframework.http.HttpStatus

open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

class NotFoundException(code: String, message: String) : ApiException(HttpStatus.NOT_FOUND, code, message)
class ConflictException(code: String, message: String) : ApiException(HttpStatus.CONFLICT, code, message)
class ForbiddenException(code: String, message: String) : ApiException(HttpStatus.FORBIDDEN, code, message)
class UnauthorizedException(code: String, message: String) : ApiException(HttpStatus.UNAUTHORIZED, code, message)
class BadRequestException(code: String, message: String) : ApiException(HttpStatus.BAD_REQUEST, code, message)
