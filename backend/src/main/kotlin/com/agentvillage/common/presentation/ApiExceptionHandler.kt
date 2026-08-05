package com.agentvillage.common.presentation

import com.agentvillage.common.exception.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ApiError(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val fieldErrors: Map<String, String> = emptyMap(),
    val details: Map<String, Any?> = emptyMap(),
)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException, request: HttpServletRequest): ResponseEntity<ApiError> =
        ResponseEntity.status(exception.status).body(
            ApiError(
                status = exception.status.value(),
                code = exception.code,
                message = exception.message,
                path = request.requestURI,
                details = exception.details,
            ),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val fields = exception.bindingResult.allErrors.associate { error ->
            (error as? FieldError)?.field.orEmpty() to (error.defaultMessage ?: "invalid value")
        }
        return ResponseEntity.badRequest().body(
            ApiError(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "VALIDATION_ERROR",
                message = "요청 값을 확인해 주세요.",
                path = request.requestURI,
                fieldErrors = fields,
            ),
        )
    }
}
