package com.ender.takehome.model

import java.math.BigDecimal
import java.time.Instant

enum class PaymentMethod { CASH, CHECK, OTHER, CARD }

/**
 * Payment lifecycle.
 *
 * ```
 *              ┌──► SUCCEEDED ──► REFUNDED
 * INITIATED ───┤
 *              └──► FAILED
 * ```
 *
 * Manual (offline) payments are recorded directly as [SUCCEEDED]. Card payments
 * start as [INITIATED] and are moved forward by the Stripe PaymentIntent result
 * and webhooks. [FAILED] and [REFUNDED] are terminal.
 */
enum class PaymentStatus {
    INITIATED,
    SUCCEEDED,
    FAILED,
    REFUNDED;

    fun canTransitionTo(next: PaymentStatus): Boolean = when (this) {
        INITIATED -> next == SUCCEEDED || next == FAILED
        SUCCEEDED -> next == REFUNDED
        FAILED, REFUNDED -> false
    }

    companion object {
        /** Statuses that block another payment attempt on the same rent charge. */
        val ACTIVE = setOf(INITIATED, SUCCEEDED)
    }
}

data class Payment(
    val id: Long = 0,
    val rentChargeId: Long,
    val amount: BigDecimal,
    val paymentMethod: PaymentMethod,
    val status: PaymentStatus = PaymentStatus.SUCCEEDED,
    val notes: String? = null,
    val recordedBy: String,
    val paymentCardId: Long? = null,
    val stripePaymentIntentId: String? = null,
    val failureReason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
