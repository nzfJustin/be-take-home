package com.ender.takehome.billing

import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stand-in for Stripe used by integration tests.
 *
 * - [confirmSetupIntent] plays the role of Stripe.js: it attaches a payment method to the
 *   customer the SetupIntent was created for.
 * - Payment methods whose ID contains "declined" are declined when charged.
 * - Webhooks are plain JSON [GatewayEvent]s; the signature must equal [VALID_SIGNATURE].
 */
class FakePaymentGateway : PaymentGateway {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val attachedCards = ConcurrentHashMap<String, String>() // payment method -> customer

    override fun createCustomer(tenant: Tenant): String = "cus_fake_${tenant.id}"

    override fun createSetupIntent(customerId: String): String = "seti_${customerId}_secret_fake"

    fun confirmSetupIntent(clientSecret: String, paymentMethodId: String) {
        val customerId = clientSecret.removePrefix("seti_").removeSuffix("_secret_fake")
        attachedCards[paymentMethodId] = customerId
    }

    override fun getCard(paymentMethodId: String): GatewayCard {
        val customerId = attachedCards[paymentMethodId]
            ?: throw PaymentGatewayException("No such payment method: $paymentMethodId")
        return GatewayCard(paymentMethodId, customerId, "visa", "4242", 12, 2030)
    }

    override fun chargeCard(request: GatewayChargeRequest): GatewayChargeResult {
        val paymentIntentId = "pi_fake_${request.paymentId}"
        return if ("declined" in request.paymentMethodId) {
            GatewayChargeResult(paymentIntentId, PaymentStatus.FAILED, "Your card was declined.")
        } else {
            GatewayChargeResult(paymentIntentId, PaymentStatus.SUCCEEDED)
        }
    }

    override fun parseWebhookEvent(payload: String, signature: String): GatewayEvent? {
        if (signature != VALID_SIGNATURE) throw InvalidWebhookException("Invalid signature")
        return objectMapper.readValue(payload)
    }

    companion object {
        const val VALID_SIGNATURE = "fake-valid-signature"
    }
}

@TestConfiguration
class FakePaymentGatewayConfig {

    @Bean
    @Primary
    fun fakePaymentGateway(): FakePaymentGateway = FakePaymentGateway()
}
