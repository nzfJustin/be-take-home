package com.ender.takehome.ledger

import com.ender.takehome.TestFixtures
import com.ender.takehome.billing.CardModule
import com.ender.takehome.billing.GatewayChargeResult
import com.ender.takehome.billing.PaymentGateway
import com.ender.takehome.billing.PaymentGatewayException
import com.ender.takehome.config.TransactionHelper
import com.ender.takehome.dto.request.RecordPaymentRequest
import com.ender.takehome.exception.ConflictException
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.leasing.LeaseModule
import com.ender.takehome.model.Payment
import com.ender.takehome.model.PaymentMethod
import com.ender.takehome.model.PaymentStatus
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.tenant.TenantModule
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class LedgerModuleTest {

    private val dataAccess = mockk<LedgerDataAccess>()
    private val leaseModule = mockk<LeaseModule>()
    private val tenantModule = mockk<TenantModule>()
    private val cardModule = mockk<CardModule>()
    private val gateway = mockk<PaymentGateway>()
    private val transactionHelper = mockk<TransactionHelper>()
    private val module = LedgerModule(dataAccess, leaseModule, tenantModule, cardModule, gateway, transactionHelper)

    private val lease = TestFixtures.lease()
    private val rentCharge = TestFixtures.rentCharge()

    // Card payment fixtures: tenant 1 owns lease 1 -> charge 1, and card 1.
    private val tenantId = 1L
    private val card = TestFixtures.paymentCard(id = 1, tenantId = tenantId)
    private val paymentId = 10L

    /** In-memory stand-in for the payments table so status transitions behave like the real thing. */
    private val payments = mutableMapOf<Long, Payment>()

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        payments.clear()

        every { transactionHelper.executeWithRetry(any(), any(), any<() -> Any>()) } answers {
            thirdArg<() -> Any>().invoke()
        }
        every { cardModule.getOwnedCard(tenantId, card.id) } returns card
        every { tenantModule.getById(tenantId) } returns TestFixtures.tenant(id = tenantId, stripeCustomerId = "cus_1")
        every { leaseModule.getById(lease.id) } returns lease
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge
        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { dataAccess.existsActivePaymentForCharge(rentCharge.id) } returns false
        every { dataAccess.saveCharge(any()) } answers { firstArg() }
        every { dataAccess.savePayment(any()) } answers {
            firstArg<Payment>().copy(id = paymentId).also { payments[paymentId] = it }
        }
        every { dataAccess.findPaymentById(any()) } answers { payments[firstArg()] }
        every { dataAccess.setStripePaymentIntentId(any(), any()) } answers {
            payments.computeIfPresent(firstArg()) { _, p -> p.copy(stripePaymentIntentId = secondArg()) }
        }
        every { dataAccess.transitionPaymentStatus(any(), any(), any(), any()) } answers {
            val p = payments[firstArg()]
            if (p?.status == secondArg<PaymentStatus>()) {
                payments[p.id] = p.copy(status = thirdArg(), failureReason = arg(3))
                true
            } else false
        }
    }

    @Test
    fun `generateCharge creates new charge when none exists for that month`() {
        val dueDate = LocalDate.of(2025, 7, 1)
        every { dataAccess.findChargeByLeaseIdAndDueDate(lease.id, dueDate) } returns null
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        val result = module.generateCharge(lease, dueDate)

        assertNotNull(result)
        assertEquals(lease.rentAmount, result!!.amount)
        assertEquals(dueDate, result.dueDate)
        verify(exactly = 1) { dataAccess.saveCharge(any()) }
    }

    @Test
    fun `generateCharge returns null when charge already exists for that month`() {
        val dueDate = LocalDate.of(2025, 7, 1)
        val existing = TestFixtures.rentCharge(leaseId = lease.id, dueDate = dueDate)
        every { dataAccess.findChargeByLeaseIdAndDueDate(lease.id, dueDate) } returns existing

        val result = module.generateCharge(lease, dueDate)

        assertNull(result)
        verify(exactly = 0) { dataAccess.saveCharge(any()) }
    }

    @Test
    fun `recordPayment creates payment and marks charge as paid`() {
        val request = RecordPaymentRequest(
            rentChargeId = rentCharge.id,
            amount = BigDecimal("2000.00"),
            paymentMethod = PaymentMethod.CHECK,
            notes = "Check #1234",
            recordedBy = "admin@test.com",
        )

        every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
        every { dataAccess.savePayment(any()) } answers { firstArg() }
        every { dataAccess.saveCharge(any()) } answers { firstArg() }

        val result = module.recordPayment(request)

        assertEquals(BigDecimal("2000.00"), result.amount)
        assertEquals(PaymentMethod.CHECK, result.paymentMethod)
        assertEquals("Check #1234", result.notes)
        verify(exactly = 1) { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
    }

    // --- payWithCard ---

    @Test
    fun `payWithCard charges the card and marks the charge paid on success`() {
        every { gateway.chargeCard(any()) } returns GatewayChargeResult("pi_1", PaymentStatus.SUCCEEDED)

        val result = module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com")

        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(PaymentMethod.CARD, result.paymentMethod)
        assertEquals(rentCharge.amount, result.amount)
        assertEquals("pi_1", result.stripePaymentIntentId)
        assertEquals(card.id, result.paymentCardId)
        verify {
            gateway.chargeCard(match {
                it.paymentId == paymentId && it.amount == rentCharge.amount &&
                    it.customerId == "cus_1" && it.paymentMethodId == card.stripePaymentMethodId
            })
        }
        verify(exactly = 1) { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
    }

    @Test
    fun `payWithCard records the payment as INITIATED before calling Stripe`() {
        every { gateway.chargeCard(any()) } answers {
            assertEquals(PaymentStatus.INITIATED, payments[paymentId]!!.status)
            GatewayChargeResult("pi_1", PaymentStatus.SUCCEEDED)
        }

        module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com")
    }

    @Test
    fun `payWithCard leaves the charge unpaid when the card is declined`() {
        every { gateway.chargeCard(any()) } returns
            GatewayChargeResult("pi_1", PaymentStatus.FAILED, "Your card was declined.")

        val result = module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com")

        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals("Your card was declined.", result.failureReason)
        verify(exactly = 0) { dataAccess.saveCharge(any()) }
    }

    @Test
    fun `payWithCard keeps the payment INITIATED while Stripe is still processing`() {
        every { gateway.chargeCard(any()) } returns GatewayChargeResult("pi_1", PaymentStatus.INITIATED)

        val result = module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com")

        assertEquals(PaymentStatus.INITIATED, result.status)
        verify(exactly = 0) { dataAccess.saveCharge(any()) }
    }

    @Test
    fun `payWithCard leaves the payment INITIATED when the Stripe outcome is unknown`() {
        every { gateway.chargeCard(any()) } throws PaymentGatewayException("timeout")

        assertThrows<PaymentGatewayException> { module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com") }

        assertEquals(PaymentStatus.INITIATED, payments[paymentId]!!.status)
    }

    @Test
    fun `payWithCard hides charges that belong to another tenant`() {
        every { leaseModule.getById(lease.id) } returns lease.copy(tenantId = 99)

        assertThrows<ResourceNotFoundException> { module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com") }
        verify(exactly = 0) { dataAccess.savePayment(any()) }
        verify(exactly = 0) { gateway.chargeCard(any()) }
    }

    @Test
    fun `payWithCard returns not found for a missing charge`() {
        every { dataAccess.findChargeByIdForUpdate(404) } returns null

        assertThrows<ResourceNotFoundException> { module.payWithCard(tenantId, 404, card.id, "tenant@test.com") }
    }

    @Test
    fun `payWithCard rejects a card the tenant does not own`() {
        every { cardModule.getOwnedCard(tenantId, 2) } throws ResourceNotFoundException("Card not found: 2")

        assertThrows<ResourceNotFoundException> { module.payWithCard(tenantId, rentCharge.id, 2, "tenant@test.com") }
        verify(exactly = 0) { gateway.chargeCard(any()) }
    }

    @Test
    fun `payWithCard rejects a charge that is already paid`() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge.copy(status = RentChargeStatus.PAID)

        assertThrows<ConflictException> { module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com") }
        verify(exactly = 0) { gateway.chargeCard(any()) }
    }

    @Test
    fun `payWithCard rejects a second payment while one is in progress`() {
        every { dataAccess.existsActivePaymentForCharge(rentCharge.id) } returns true

        assertThrows<ConflictException> { module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com") }
        verify(exactly = 0) { gateway.chargeCard(any()) }
    }

    @Test
    fun `payWithCard allows paying an overdue charge`() {
        every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge.copy(status = RentChargeStatus.OVERDUE)
        every { gateway.chargeCard(any()) } returns GatewayChargeResult("pi_1", PaymentStatus.SUCCEEDED)

        assertEquals(PaymentStatus.SUCCEEDED, module.payWithCard(tenantId, rentCharge.id, card.id, "tenant@test.com").status)
    }
}
