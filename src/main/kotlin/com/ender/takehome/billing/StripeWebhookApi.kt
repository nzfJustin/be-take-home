package com.ender.takehome.billing

import com.ender.takehome.ledger.LedgerModule
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Receives Stripe webhooks. Unauthenticated at the HTTP layer (see SecurityConfig); every request
 * is verified against the Stripe-Signature header before anything is applied.
 *
 * Returns 200 for events we ignore or can't match so Stripe doesn't retry them forever.
 * Handling is idempotent, so Stripe's at-least-once redelivery is safe.
 */
@RestController
class StripeWebhookApi(
    private val paymentGateway: PaymentGateway,
    private val ledgerModule: LedgerModule,
) {

    @PostMapping("/api/stripe/webhook")
    fun handle(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature") signature: String,
    ) {
        val event = paymentGateway.parseWebhookEvent(payload, signature) ?: return
        ledgerModule.applyGatewayEvent(event)
    }
}
