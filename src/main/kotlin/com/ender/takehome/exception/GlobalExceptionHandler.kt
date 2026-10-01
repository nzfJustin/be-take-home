package com.ender.takehome.exception

import com.ender.takehome.billing.InvalidWebhookException
import com.ender.takehome.billing.PaymentGatewayException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleNotFound(ex: ResourceNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(404, "Not Found", ex.message ?: "Resource not found")
        )

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(409, "Conflict", ex.message ?: "Conflict")
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") {
            "${it.field}: ${it.defaultMessage}"
        }
        return ResponseEntity.badRequest().body(
            ErrorResponse(400, "Bad Request", message)
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(400, "Bad Request", ex.message ?: "Invalid request")
        )

    @ExceptionHandler(PaymentGatewayException::class)
    fun handlePaymentGateway(ex: PaymentGatewayException): ResponseEntity<ErrorResponse> {
        log.error("Payment processor error", ex)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            ErrorResponse(502, "Bad Gateway", "Payment processor is unavailable, please try again")
        )
    }

    @ExceptionHandler(InvalidWebhookException::class)
    fun handleInvalidWebhook(ex: InvalidWebhookException): ResponseEntity<ErrorResponse> {
        log.warn("Rejected webhook: ${ex.message}")
        return ResponseEntity.badRequest().body(
            ErrorResponse(400, "Bad Request", ex.message ?: "Invalid webhook")
        )
    }
}
