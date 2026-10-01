package com.ender.takehome.billing

import com.ender.takehome.model.PaymentStatus
import com.stripe.StripeClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StripePaymentGatewayTest {

    private val webhookSecret = "whsec_test"
    private val gateway = StripePaymentGateway(StripeClient("sk_test_dummy"), webhookSecret)

    /** Builds a Stripe-Signature header the same way Stripe does (HMAC-SHA256 of "t.payload"). */
    private fun sign(payload: String, secret: String = webhookSecret): String {
        val t = Instant.now().epochSecond
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val v1 = mac.doFinal("$t.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$t,v1=$v1"
    }

    private fun event(type: String, obj: String) =
        """{"id":"evt_1","object":"event","type":"$type","api_version":"2024-06-20","data":{"object":$obj}}"""

    private fun request(amount: String) = GatewayChargeRequest(
        paymentId = 1L,
        amount = BigDecimal(amount),
        customerId = "cus_1",
        paymentMethodId = "pm_1",
    )

    @Test
    fun `amounts convert to cents exactly`() {
        assertEquals(250000L, StripePaymentGateway.toMinorUnits(request("2500.00")))
        assertEquals(1999L, StripePaymentGateway.toMinorUnits(request("19.99")))
        assertEquals(500L, StripePaymentGateway.toMinorUnits(request("5")))
    }

    @Test
    fun `amounts with sub-cent precision are rejected`() {
        assertThrows<ArithmeticException> { StripePaymentGateway.toMinorUnits(request("10.005")) }
    }

    @Test
    fun `stripe intent statuses map onto the payment lifecycle`() {
        assertEquals(PaymentStatus.SUCCEEDED, StripePaymentGateway.mapIntentStatus("succeeded"))
        assertEquals(PaymentStatus.FAILED, StripePaymentGateway.mapIntentStatus("requires_payment_method"))
        assertEquals(PaymentStatus.FAILED, StripePaymentGateway.mapIntentStatus("canceled"))
        assertEquals(PaymentStatus.INITIATED, StripePaymentGateway.mapIntentStatus("processing"))
        assertEquals(PaymentStatus.INITIATED, StripePaymentGateway.mapIntentStatus("requires_action"))
    }

    @Test
    fun `payment_intent succeeded webhook is parsed with our payment id`() {
        val payload = event(
            "payment_intent.succeeded",
            """{"id":"pi_123","object":"payment_intent","status":"succeeded","metadata":{"payment_id":"42"}}""",
        )

        val parsed = gateway.parseWebhookEvent(payload, sign(payload))!!

        assertEquals("evt_1", parsed.eventId)
        assertEquals("pi_123", parsed.paymentIntentId)
        assertEquals(42L, parsed.paymentId)
        assertEquals(PaymentStatus.SUCCEEDED, parsed.status)
    }

    @Test
    fun `fully refunded charge webhook maps to REFUNDED`() {
        val payload = event("charge.refunded", """{"id":"ch_1","object":"charge","refunded":true,"payment_intent":"pi_123"}""")

        val parsed = gateway.parseWebhookEvent(payload, sign(payload))!!

        assertEquals("pi_123", parsed.paymentIntentId)
        assertEquals(PaymentStatus.REFUNDED, parsed.status)
    }

    @Test
    fun `partially refunded charge webhook is ignored`() {
        val payload = event("charge.refunded", """{"id":"ch_1","object":"charge","refunded":false,"payment_intent":"pi_123"}""")

        assertNull(gateway.parseWebhookEvent(payload, sign(payload)))
    }

    @Test
    fun `unhandled event types are ignored`() {
        val payload = event("customer.created", """{"id":"cus_1","object":"customer"}""")

        assertNull(gateway.parseWebhookEvent(payload, sign(payload)))
    }

    @Test
    fun `webhook with a bad signature is rejected`() {
        val payload = event("payment_intent.succeeded", """{"id":"pi_123","object":"payment_intent"}""")

        assertThrows<InvalidWebhookException> { gateway.parseWebhookEvent(payload, sign(payload, "whsec_other")) }
    }
}
