package com.ender.takehome.billing

import com.ender.takehome.generated.tables.PaymentCards.PAYMENT_CARDS
import com.ender.takehome.generated.tables.records.PaymentCardsRecord
import com.ender.takehome.model.PaymentCard
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class CardDataAccess(private val dsl: DSLContext) {

    fun findById(id: Long): PaymentCard? =
        dsl.selectFrom(PAYMENT_CARDS)
            .where(PAYMENT_CARDS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findByStripePaymentMethodId(stripePaymentMethodId: String): PaymentCard? =
        dsl.selectFrom(PAYMENT_CARDS)
            .where(PAYMENT_CARDS.STRIPE_PAYMENT_METHOD_ID.eq(stripePaymentMethodId))
            .fetchOne()
            ?.toModel()

    fun findByTenantIdCursor(tenantId: Long, startAfterId: Long?, limit: Int): List<PaymentCard> =
        dsl.selectFrom(PAYMENT_CARDS)
            .where(PAYMENT_CARDS.TENANT_ID.eq(tenantId))
            .and(cursorCondition(startAfterId))
            .orderBy(PAYMENT_CARDS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun save(card: PaymentCard): PaymentCard {
        val record = dsl.newRecord(PAYMENT_CARDS).apply {
            tenantId = card.tenantId
            stripePaymentMethodId = card.stripePaymentMethodId
            brand = card.brand
            last4 = card.last4
            expMonth = card.expMonth
            expYear = card.expYear
        }
        record.store()
        return card.copy(id = record.id!!)
    }

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) PAYMENT_CARDS.ID.gt(startAfterId) else DSL.noCondition()

    private fun PaymentCardsRecord.toModel() = PaymentCard(
        id = id!!,
        tenantId = tenantId!!,
        stripePaymentMethodId = stripePaymentMethodId!!,
        brand = brand!!,
        last4 = last4!!,
        expMonth = expMonth!!,
        expYear = expYear!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
