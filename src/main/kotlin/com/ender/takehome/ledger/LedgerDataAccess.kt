package com.ender.takehome.ledger

import com.ender.takehome.generated.tables.Payments.PAYMENTS
import com.ender.takehome.generated.tables.RentCharges.RENT_CHARGES
import com.ender.takehome.generated.tables.records.PaymentsRecord
import com.ender.takehome.generated.tables.records.RentChargesRecord
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class LedgerDataAccess(private val dsl: DSLContext) {

    // --- RentCharge ---

    fun findChargeById(id: Long): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findChargesByLeaseIdCursor(leaseId: Long, startAfterId: Long?, limit: Int): List<RentCharge> =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(chargeCursorCondition(startAfterId))
            .orderBy(RENT_CHARGES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findChargesByLeaseIdAndStatusCursor(leaseId: Long, status: RentChargeStatus, startAfterId: Long?, limit: Int): List<RentCharge> =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(RENT_CHARGES.STATUS.eq(status.name))
            .and(chargeCursorCondition(startAfterId))
            .orderBy(RENT_CHARGES.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    /** Locks the charge row until the transaction ends, serializing payment attempts on it. */
    fun findChargeByIdForUpdate(id: Long): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.toModel()

    fun findChargeByLeaseIdAndDueDate(leaseId: Long, dueDate: LocalDate): RentCharge? =
        dsl.selectFrom(RENT_CHARGES)
            .where(RENT_CHARGES.LEASE_ID.eq(leaseId))
            .and(RENT_CHARGES.DUE_DATE.eq(dueDate))
            .fetchOne()
            ?.toModel()

    fun saveCharge(charge: RentCharge): RentCharge {
        if (charge.id == 0L) {
            val record = dsl.newRecord(RENT_CHARGES).apply {
                leaseId = charge.leaseId
                amount = charge.amount
                dueDate = charge.dueDate
                status = charge.status.name
            }
            record.store()
            return charge.copy(id = record.id!!)
        }
        dsl.update(RENT_CHARGES)
            .set(RENT_CHARGES.STATUS, charge.status.name)
            .where(RENT_CHARGES.ID.eq(charge.id))
            .execute()
        return charge
    }

    // --- Payment ---

    fun findPaymentsByRentChargeIdCursor(rentChargeId: Long, startAfterId: Long?, limit: Int): List<Payment> =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(paymentCursorCondition(startAfterId))
            .orderBy(PAYMENTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun findPaymentById(id: Long): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findPaymentByStripePaymentIntentId(stripePaymentIntentId: String): Payment? =
        dsl.selectFrom(PAYMENTS)
            .where(PAYMENTS.STRIPE_PAYMENT_INTENT_ID.eq(stripePaymentIntentId))
            .fetchOne()
            ?.toModel()

    fun existsActivePaymentForCharge(rentChargeId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectFrom(PAYMENTS)
                .where(PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
                .and(PAYMENTS.STATUS.`in`(PaymentStatus.ACTIVE.map { it.name }))
        )

    fun setStripePaymentIntentId(paymentId: Long, stripePaymentIntentId: String) {
        dsl.update(PAYMENTS)
            .set(PAYMENTS.STRIPE_PAYMENT_INTENT_ID, stripePaymentIntentId)
            .where(PAYMENTS.ID.eq(paymentId))
            .and(PAYMENTS.STRIPE_PAYMENT_INTENT_ID.isNull)
            .execute()
    }

    /**
     * Compare-and-set the payment status. Returns false if the payment is no longer in [from]
     * (another request or webhook already moved it), so callers never apply a transition twice.
     */
    fun transitionPaymentStatus(paymentId: Long, from: PaymentStatus, to: PaymentStatus, failureReason: String?): Boolean =
        dsl.update(PAYMENTS)
            .set(PAYMENTS.STATUS, to.name)
            .set(PAYMENTS.FAILURE_REASON, failureReason)
            .set(PAYMENTS.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
            .where(PAYMENTS.ID.eq(paymentId))
            .and(PAYMENTS.STATUS.eq(from.name))
            .execute() == 1

    fun savePayment(payment: Payment): Payment {
        if (payment.id == 0L) {
            val record = dsl.newRecord(PAYMENTS).apply {
                rentChargeId = payment.rentChargeId
                amount = payment.amount
                paymentMethod = payment.paymentMethod.name
                status = payment.status.name
                notes = payment.notes
                recordedBy = payment.recordedBy
                paymentCardId = payment.paymentCardId
                stripePaymentIntentId = payment.stripePaymentIntentId
                failureReason = payment.failureReason
            }
            record.store()
            return payment.copy(id = record.id!!)
        }
        return payment
    }

    // --- Cursor helpers ---

    private fun chargeCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) RENT_CHARGES.ID.gt(startAfterId) else DSL.noCondition()

    private fun paymentCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) PAYMENTS.ID.gt(startAfterId) else DSL.noCondition()

    // --- Record mappers ---

    private fun RentChargesRecord.toModel() = RentCharge(
        id = id!!,
        leaseId = leaseId!!,
        amount = amount!!,
        dueDate = dueDate!!,
        status = RentChargeStatus.valueOf(status!!),
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )

    private fun PaymentsRecord.toModel() = Payment(
        id = id!!,
        rentChargeId = rentChargeId!!,
        amount = amount!!,
        paymentMethod = PaymentMethod.valueOf(paymentMethod!!),
        status = PaymentStatus.valueOf(status!!),
        notes = notes,
        recordedBy = recordedBy!!,
        paymentCardId = paymentCardId,
        stripePaymentIntentId = stripePaymentIntentId,
        failureReason = failureReason,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
        updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
    )
}
