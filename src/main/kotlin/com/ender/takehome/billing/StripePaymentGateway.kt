package com.ender.takehome.billing

import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.Tenant
import com.google.gson.JsonSyntaxException
import com.stripe.StripeClient
import com.stripe.exception.CardException
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.model.Charge
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import com.stripe.model.StripeObject
import com.stripe.net.RequestOptions
import com.stripe.param.CustomerCreateParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.SetupIntentCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.RoundingMode

@Component
class StripePaymentGateway(
    private val stripe: StripeClient,
    @Value("\${stripe.webhook-secret}") private val webhookSecret: String,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(StripePaymentGateway::class.java)

    override fun createCustomer(tenant: Tenant): String = call("create customer") {
        val params = CustomerCreateParams.builder()
            .setEmail(tenant.email)
            .setName("${tenant.firstName} ${tenant.lastName}")
            .putMetadata(METADATA_TENANT_ID, tenant.id.toString())
            .build()
        // Keyed by tenant so a retried request can't create a second customer.
        val options = RequestOptions.builder().setIdempotencyKey("customer-tenant-${tenant.id}").build()
        stripe.v1().customers().create(params, options).id
    }

    override fun createSetupIntent(customerId: String): String = call("create setup intent") {
        val params = SetupIntentCreateParams.builder()
            .setCustomer(customerId)
            .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
            .addAllowedPaymentMethodType(SetupIntentCreateParams.AllowedPaymentMethodType.CARD)
            .build()
        stripe.v1().setupIntents().create(params).clientSecret
    }

    override fun getCard(paymentMethodId: String): GatewayCard = call("retrieve payment method") {
        val pm = stripe.v1().paymentMethods().retrieve(paymentMethodId)
        val card = requireNotNull(pm.card) { "Payment method $paymentMethodId is not a card" }
        GatewayCard(
            paymentMethodId = pm.id,
            customerId = pm.customer,
            brand = card.brand,
            last4 = card.last4,
            expMonth = card.expMonth.toInt(),
            expYear = card.expYear.toInt(),
        )
    }

    override fun chargeCard(request: GatewayChargeRequest): GatewayChargeResult {
        val params = PaymentIntentCreateParams.builder()
            .setAmount(toMinorUnits(request))
            .setCurrency(CURRENCY)
            .setCustomer(request.customerId)
            .setPaymentMethod(request.paymentMethodId)
            .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
            .setConfirm(true)
            .setOffSession(true)
            .putMetadata(METADATA_PAYMENT_ID, request.paymentId.toString())
            .build()
        // One PaymentIntent per payment row, even if this call is retried.
        val options = RequestOptions.builder().setIdempotencyKey("payment-${request.paymentId}").build()

        return try {
            val intent = stripe.v1().paymentIntents().create(params, options)
            GatewayChargeResult(intent.id, mapIntentStatus(intent.status), intent.lastPaymentError?.message)
        } catch (e: CardException) {
            // Declined: Stripe created the PaymentIntent but it did not succeed.
            log.info("Card declined for payment ${request.paymentId}: code=${e.code} decline=${e.declineCode}")
            GatewayChargeResult(e.stripeError?.paymentIntent?.id, PaymentStatus.FAILED, e.userMessage ?: e.message)
        } catch (e: StripeException) {
            throw PaymentGatewayException("Stripe charge failed for payment ${request.paymentId}: ${e.message}", e)
        }
    }

    override fun parseWebhookEvent(payload: String, signature: String): GatewayEvent? {
        val event = try {
            stripe.constructEvent(payload, signature, webhookSecret)
        } catch (e: SignatureVerificationException) {
            throw InvalidWebhookException("Invalid Stripe signature", e)
        } catch (e: JsonSyntaxException) {
            throw InvalidWebhookException("Malformed Stripe payload", e)
        }

        return when (event.type) {
            "payment_intent.succeeded" -> fromIntent(event, PaymentStatus.SUCCEEDED)
            "payment_intent.payment_failed" -> fromIntent(event, PaymentStatus.FAILED)
            "charge.refunded" -> fromRefundedCharge(event)
            else -> null
        }
    }

    private fun fromIntent(event: Event, status: PaymentStatus): GatewayEvent {
        val intent = eventObject(event) as PaymentIntent
        return GatewayEvent(
            eventId = event.id,
            paymentIntentId = intent.id,
            paymentId = intent.metadata?.get(METADATA_PAYMENT_ID)?.toLongOrNull(),
            status = status,
            failureReason = intent.lastPaymentError?.message,
        )
    }

    private fun fromRefundedCharge(event: Event): GatewayEvent? {
        val charge = eventObject(event) as Charge
        // Partial refunds are out of scope; only a full refund moves the payment to REFUNDED.
        if (charge.refunded != true || charge.paymentIntent == null) return null
        return GatewayEvent(
            eventId = event.id,
            paymentIntentId = charge.paymentIntent,
            paymentId = null,
            status = PaymentStatus.REFUNDED,
        )
    }

    private fun eventObject(event: Event): StripeObject =
        // getObject() is empty when the event's API version differs from the SDK's; fall back to raw deserialization.
        event.dataObjectDeserializer.`object`.orElseGet { event.dataObjectDeserializer.deserializeUnsafe() }

    private fun <T> call(action: String, block: () -> T): T =
        try {
            block()
        } catch (e: StripeException) {
            throw PaymentGatewayException("Stripe failed to $action: ${e.message}", e)
        }

    companion object {
        const val CURRENCY = "usd"
        const val METADATA_PAYMENT_ID = "payment_id"
        const val METADATA_TENANT_ID = "tenant_id"

        fun toMinorUnits(request: GatewayChargeRequest): Long =
            request.amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()

        /** Stripe PaymentIntent status -> our lifecycle. Anything still in flight stays INITIATED. */
        fun mapIntentStatus(stripeStatus: String): PaymentStatus = when (stripeStatus) {
            "succeeded" -> PaymentStatus.SUCCEEDED
            "requires_payment_method", "canceled" -> PaymentStatus.FAILED
            else -> PaymentStatus.INITIATED // processing, requires_action, requires_confirmation, requires_capture
        }
    }
}
