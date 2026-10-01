package com.ender.takehome.ledger

import com.ender.takehome.billing.CardModule
import com.ender.takehome.billing.GatewayChargeRequest
import com.ender.takehome.billing.PaymentGateway
import com.ender.takehome.billing.PaymentGatewayException
import com.ender.takehome.config.TransactionHelper
import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ConflictException
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.leasing.LeaseModule
import com.ender.takehome.model.Lease
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentCharge
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.tenant.TenantModule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class LedgerModule(
    private val dataAccess: LedgerDataAccess,
    private val leaseModule: LeaseModule,
    private val tenantModule: TenantModule,
    private val cardModule: CardModule,
    private val paymentGateway: PaymentGateway,
    private val transactionHelper: TransactionHelper,
) {

    private val log = LoggerFactory.getLogger(LedgerModule::class.java)

    fun getChargeById(id: Long): RentCharge =
        dataAccess.findChargeById(id) ?: throw ResourceNotFoundException("Rent charge not found: $id")

    fun getChargesByLeaseId(leaseId: Long, startAfterId: Long?, limit: Int): CursorPage<RentCharge> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findChargesByLeaseIdCursor(leaseId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getPendingChargesByLeaseId(leaseId: Long, startAfterId: Long?, limit: Int): CursorPage<RentCharge> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findChargesByLeaseIdAndStatusCursor(leaseId, RentChargeStatus.PENDING, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    @Transactional
    fun generateCharge(lease: Lease, dueDate: LocalDate): RentCharge? {
        val existing = dataAccess.findChargeByLeaseIdAndDueDate(lease.id, dueDate)
        if (existing != null) return null

        val charge = RentCharge(
            leaseId = lease.id,
            amount = lease.rentAmount,
            dueDate = dueDate,
        )
        return dataAccess.saveCharge(charge)
    }

    fun getPaymentsByRentChargeId(rentChargeId: Long, startAfterId: Long?, limit: Int): CursorPage<Payment> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findPaymentsByRentChargeIdCursor(rentChargeId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    @Transactional
    fun recordPayment(request: RecordPaymentRequest): Payment {
        val charge = dataAccess.findChargeById(request.rentChargeId)
            ?: throw ResourceNotFoundException("Rent charge not found: ${request.rentChargeId}")

        val payment = Payment(
            rentChargeId = charge.id,
            amount = request.amount,
            paymentMethod = request.paymentMethod,
            notes = request.notes,
            recordedBy = request.recordedBy,
        )
        val saved = dataAccess.savePayment(payment)

        dataAccess.saveCharge(charge.copy(status = RentChargeStatus.PAID))

        return saved
    }

    /**
     * Pay one of the tenant's rent charges with one of their saved cards.
     *
     * 1. In a transaction holding the charge row lock: verify ownership and that the charge is
     *    still payable, then record an INITIATED payment. The lock plus the active-payment check
     *    means concurrent requests can't both start a payment for the same charge.
     * 2. Outside any transaction: charge the card. Stripe is never called while holding DB locks.
     * 3. In a new transaction: record the PaymentIntent and apply the result.
     *
     * If the Stripe outcome is unknown ([PaymentGatewayException]) the payment stays INITIATED and
     * the webhook resolves it, matched through the payment ID in PaymentIntent metadata.
     */
    fun payWithCard(tenantId: Long, rentChargeId: Long, cardId: Long, recordedBy: String): Payment {
        val card = cardModule.getOwnedCard(tenantId, cardId)
        val customerId = checkNotNull(tenantModule.getById(tenantId).stripeCustomerId) {
            "Tenant $tenantId has a saved card but no Stripe customer"
        }

        val payment = transactionHelper.executeWithRetry {
            val charge = dataAccess.findChargeByIdForUpdate(rentChargeId)
                ?.takeIf { leaseModule.getById(it.leaseId).tenantId == tenantId }
                ?: throw ResourceNotFoundException("Rent charge not found: $rentChargeId")

            if (charge.status == RentChargeStatus.PAID) {
                throw ConflictException("Rent charge $rentChargeId is already paid")
            }
            if (dataAccess.existsActivePaymentForCharge(rentChargeId)) {
                throw ConflictException("A payment for rent charge $rentChargeId is already in progress")
            }

            dataAccess.savePayment(
                Payment(
                    rentChargeId = charge.id,
                    amount = charge.amount,
                    paymentMethod = PaymentMethod.CARD,
                    status = PaymentStatus.INITIATED,
                    recordedBy = recordedBy,
                    paymentCardId = card.id,
                )
            )
        }

        val result = try {
            paymentGateway.chargeCard(
                GatewayChargeRequest(
                    paymentId = payment.id,
                    amount = payment.amount,
                    customerId = customerId,
                    paymentMethodId = card.stripePaymentMethodId,
                )
            )
        } catch (e: PaymentGatewayException) {
            log.error("Card charge outcome unknown for payment ${payment.id}; leaving INITIATED for webhook", e)
            throw e
        }

        return transactionHelper.executeWithRetry {
            result.paymentIntentId?.let { dataAccess.setStripePaymentIntentId(payment.id, it) }
            applyPaymentStatus(payment.id, result.status, result.failureReason)
            dataAccess.findPaymentById(payment.id)!!
        }
    }

    /**
     * Move a payment to [next] if the lifecycle allows it, and keep the rent charge in sync.
     * Duplicate and out-of-order updates are ignored, so this is safe to call from both the
     * synchronous PaymentIntent result and Stripe webhooks. Must run inside a transaction.
     *
     * Returns true if the payment changed.
     */
    private fun applyPaymentStatus(paymentId: Long, next: PaymentStatus, failureReason: String?): Boolean {
        val payment = dataAccess.findPaymentById(paymentId)
            ?: throw ResourceNotFoundException("Payment not found: $paymentId")

        if (payment.status == next) return false
        if (!payment.status.canTransitionTo(next)) {
            log.warn("Ignoring illegal payment transition ${payment.status} -> $next for payment $paymentId")
            return false
        }
        if (!dataAccess.transitionPaymentStatus(paymentId, payment.status, next, failureReason)) {
            log.info("Payment $paymentId changed concurrently; skipping ${payment.status} -> $next")
            return false
        }

        val chargeStatus = when (next) {
            PaymentStatus.SUCCEEDED -> RentChargeStatus.PAID
            PaymentStatus.REFUNDED -> RentChargeStatus.PENDING
            else -> null
        }
        if (chargeStatus != null) {
            val charge = getChargeById(payment.rentChargeId)
            dataAccess.saveCharge(charge.copy(status = chargeStatus))
        }
        log.info("Payment $paymentId: ${payment.status} -> $next")
        return true
    }
}
