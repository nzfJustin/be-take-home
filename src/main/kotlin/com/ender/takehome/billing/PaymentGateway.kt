package com.ender.takehome.billing

import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.Tenant
import java.math.BigDecimal

/**
 * Our boundary to the card processor (Stripe). Speaks only in our own types so
 * business logic and tests never depend on the Stripe SDK.
 */
interface PaymentGateway {

    /** Create a processor-side customer for [tenant]. Returns the customer ID. */
    fun createCustomer(tenant: Tenant): String

    /** Start a card setup for [customerId]. Returns the client secret the frontend confirms. */
    fun createSetupIntent(customerId: String): String

    /** Look up a saved card by its processor payment method ID. */
    fun getCard(paymentMethodId: String): GatewayCard

    /**
     * Charge a saved card. Declines are returned as a [PaymentStatus.FAILED] result.
     * Throws [PaymentGatewayException] when the outcome is unknown (network/processor error);
     * the webhook resolves those later.
     */
    fun chargeCard(request: GatewayChargeRequest): GatewayChargeResult

    /**
     * Verify and parse a webhook. Returns null for event types we don't act on.
     * Throws [InvalidWebhookException] when the signature or payload is invalid.
     */
    fun parseWebhookEvent(payload: String, signature: String): GatewayEvent?
}

data class GatewayCard(
    val paymentMethodId: String,
    val customerId: String?,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int,
)

data class GatewayChargeRequest(
    /** Our payment ID. Used as the idempotency key and stored as PaymentIntent metadata. */
    val paymentId: Long,
    val amount: BigDecimal,
    val customerId: String,
    val paymentMethodId: String,
)

data class GatewayChargeResult(
    val paymentIntentId: String?,
    val status: PaymentStatus,
    val failureReason: String? = null,
)

data class GatewayEvent(
    val eventId: String,
    val paymentIntentId: String,
    /** Our payment ID from PaymentIntent metadata, when the event carries it. */
    val paymentId: Long?,
    val status: PaymentStatus,
    val failureReason: String? = null,
)

class PaymentGatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class InvalidWebhookException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
