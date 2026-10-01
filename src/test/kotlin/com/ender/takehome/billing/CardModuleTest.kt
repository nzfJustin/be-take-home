package com.ender.takehome.billing

import com.ender.takehome.TestFixtures
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.tenant.TenantModule
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CardModuleTest {

    private val dataAccess = mockk<CardDataAccess>()
    private val tenantModule = mockk<TenantModule>()
    private val gateway = mockk<PaymentGateway>()
    private val module = CardModule(dataAccess, tenantModule, gateway)

    private val tenantId = 1L
    private val gatewayCard = GatewayCard(
        paymentMethodId = "pm_test_1",
        customerId = "cus_1",
        brand = "visa",
        last4 = "4242",
        expMonth = 12,
        expYear = 2030,
    )

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    @Test
    fun `createSetupIntent creates and links a Stripe customer the first time`() {
        val tenant = TestFixtures.tenant(id = tenantId)
        every { tenantModule.getById(tenantId) } returns tenant
        every { gateway.createCustomer(tenant) } returns "cus_new"
        every { tenantModule.linkStripeCustomer(tenantId, "cus_new") } returns tenant.copy(stripeCustomerId = "cus_new")
        every { gateway.createSetupIntent("cus_new") } returns "seti_secret"

        assertEquals("seti_secret", module.createSetupIntent(tenantId))
    }

    @Test
    fun `createSetupIntent reuses an existing Stripe customer`() {
        every { tenantModule.getById(tenantId) } returns TestFixtures.tenant(id = tenantId, stripeCustomerId = "cus_1")
        every { gateway.createSetupIntent("cus_1") } returns "seti_secret"

        module.createSetupIntent(tenantId)

        verify(exactly = 0) { gateway.createCustomer(any()) }
    }

    @Test
    fun `createSetupIntent uses the customer linked by a concurrent request`() {
        val tenant = TestFixtures.tenant(id = tenantId)
        every { tenantModule.getById(tenantId) } returns tenant
        every { gateway.createCustomer(tenant) } returns "cus_mine"
        every { tenantModule.linkStripeCustomer(tenantId, "cus_mine") } returns tenant.copy(stripeCustomerId = "cus_winner")
        every { gateway.createSetupIntent("cus_winner") } returns "seti_secret"

        module.createSetupIntent(tenantId)

        verify { gateway.createSetupIntent("cus_winner") }
    }

    @Test
    fun `registerCard saves card metadata from Stripe`() {
        every { dataAccess.findByStripePaymentMethodId("pm_test_1") } returns null
        every { tenantModule.getById(tenantId) } returns TestFixtures.tenant(id = tenantId, stripeCustomerId = "cus_1")
        every { gateway.getCard("pm_test_1") } returns gatewayCard
        every { dataAccess.save(any()) } answers { firstArg<com.ender.takehome.model.PaymentCard>().copy(id = 7) }

        val card = module.registerCard(tenantId, "pm_test_1")

        assertEquals(7L, card.id)
        assertEquals("visa", card.brand)
        assertEquals("4242", card.last4)
        assertEquals(tenantId, card.tenantId)
    }

    @Test
    fun `registerCard rejects a payment method attached to another customer`() {
        every { dataAccess.findByStripePaymentMethodId("pm_test_1") } returns null
        every { tenantModule.getById(tenantId) } returns TestFixtures.tenant(id = tenantId, stripeCustomerId = "cus_1")
        every { gateway.getCard("pm_test_1") } returns gatewayCard.copy(customerId = "cus_someone_else")

        assertThrows<IllegalArgumentException> { module.registerCard(tenantId, "pm_test_1") }
        verify(exactly = 0) { dataAccess.save(any()) }
    }

    @Test
    fun `registerCard requires a Stripe customer`() {
        every { dataAccess.findByStripePaymentMethodId("pm_test_1") } returns null
        every { tenantModule.getById(tenantId) } returns TestFixtures.tenant(id = tenantId)

        assertThrows<IllegalArgumentException> { module.registerCard(tenantId, "pm_test_1") }
    }

    @Test
    fun `registerCard is idempotent for the same payment method`() {
        val existing = TestFixtures.paymentCard(id = 3, tenantId = tenantId)
        every { dataAccess.findByStripePaymentMethodId("pm_test_1") } returns existing

        assertEquals(existing, module.registerCard(tenantId, "pm_test_1"))
        verify(exactly = 0) { gateway.getCard(any()) }
    }

    @Test
    fun `registerCard rejects a payment method already saved by another tenant`() {
        every { dataAccess.findByStripePaymentMethodId("pm_test_1") } returns TestFixtures.paymentCard(tenantId = 99)

        assertThrows<IllegalArgumentException> { module.registerCard(tenantId, "pm_test_1") }
    }

    @Test
    fun `getOwnedCard hides other tenants' cards`() {
        every { dataAccess.findById(5) } returns TestFixtures.paymentCard(id = 5, tenantId = 99)

        assertThrows<ResourceNotFoundException> { module.getOwnedCard(tenantId, 5) }
    }
}
