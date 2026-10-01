package com.ender.takehome.billing

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.PaymentCard
import com.ender.takehome.tenant.TenantModule
import org.jooq.exception.IntegrityConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CardModule(
    private val dataAccess: CardDataAccess,
    private val tenantModule: TenantModule,
    private val gateway: PaymentGateway,
) {

    private val log = LoggerFactory.getLogger(CardModule::class.java)

    /**
     * Start saving a card: make sure the tenant has a Stripe customer, then return a
     * SetupIntent client secret for the frontend to confirm with Stripe.js.
     */
    fun createSetupIntent(tenantId: Long): String =
        gateway.createSetupIntent(ensureStripeCustomer(tenantId))

    /**
     * Save a card the tenant confirmed through a SetupIntent. Idempotent: registering the
     * same payment method again returns the existing card.
     */
    fun registerCard(tenantId: Long, stripePaymentMethodId: String): PaymentCard {
        dataAccess.findByStripePaymentMethodId(stripePaymentMethodId)?.let { return requireOwnedBy(it, tenantId) }

        val customerId = tenantModule.getById(tenantId).stripeCustomerId
        require(customerId != null) { "Create a setup intent before registering a card" }

        val card = gateway.getCard(stripePaymentMethodId)
        // The payment method must have been attached to this tenant's customer by their own SetupIntent.
        require(card.customerId == customerId) { "Payment method does not belong to this tenant" }

        val toSave = PaymentCard(
            tenantId = tenantId,
            stripePaymentMethodId = card.paymentMethodId,
            brand = card.brand,
            last4 = card.last4,
            expMonth = card.expMonth,
            expYear = card.expYear,
        )
        return try {
            dataAccess.save(toSave)
        } catch (e: IntegrityConstraintViolationException) {
            // A concurrent request registered the same payment method first.
            val existing = dataAccess.findByStripePaymentMethodId(stripePaymentMethodId) ?: throw e
            requireOwnedBy(existing, tenantId)
        }
    }

    fun listCards(tenantId: Long, startAfterId: Long?, limit: Int): CursorPage<PaymentCard> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findByTenantIdCursor(tenantId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    /** A card the tenant owns. Other tenants' cards are reported as not found. */
    fun getOwnedCard(tenantId: Long, cardId: Long): PaymentCard {
        val card = dataAccess.findById(cardId)
        if (card == null || card.tenantId != tenantId) throw ResourceNotFoundException("Card not found: $cardId")
        return card
    }

    private fun ensureStripeCustomer(tenantId: Long): String {
        val tenant = tenantModule.getById(tenantId)
        tenant.stripeCustomerId?.let { return it }

        val created = gateway.createCustomer(tenant)
        val linked = tenantModule.linkStripeCustomer(tenantId, created).stripeCustomerId!!
        if (linked != created) {
            log.warn("Tenant $tenantId was linked to $linked concurrently; Stripe customer $created is unused")
        }
        return linked
    }

    private fun requireOwnedBy(card: PaymentCard, tenantId: Long): PaymentCard {
        require(card.tenantId == tenantId) { "Payment method does not belong to this tenant" }
        return card
    }
}
