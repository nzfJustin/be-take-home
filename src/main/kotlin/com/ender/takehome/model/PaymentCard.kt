package com.ender.takehome.model

import java.time.Instant

/** A card saved via Stripe. We only hold the Stripe payment method ID and display metadata. */
data class PaymentCard(
    val id: Long = 0,
    val tenantId: Long,
    val stripePaymentMethodId: String,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int,
    val createdAt: Instant = Instant.now(),
)
