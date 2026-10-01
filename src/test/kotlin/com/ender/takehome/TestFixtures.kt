package com.ender.takehome

import com.ender.takehome.model.*
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Test data builders for creating entities in tests.
 */
object TestFixtures {

    fun propertyManager(
        id: Long = 1L,
        name: String = "Test PM",
        email: String = "pm@test.com",
    ) = PropertyManager(id = id, name = name, email = email)

    fun property(
        id: Long = 1L,
        pmId: Long = 1L,
        name: String = "Test Property",
        address: String = "123 Test St",
    ) = Property(id = id, pmId = pmId, name = name, address = address)

    fun unit(
        id: Long = 1L,
        propertyId: Long = 1L,
        unitNumber: String = "101",
    ) = PropertyUnit(id = id, propertyId = propertyId, unitNumber = unitNumber)

    fun tenant(
        id: Long = 1L,
        firstName: String = "Test",
        lastName: String = "Tenant",
        email: String = "test@tenant.com",
        phone: String? = "555-0100",
        stripeCustomerId: String? = null,
    ) = Tenant(
        id = id,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        stripeCustomerId = stripeCustomerId,
    )

    fun lease(
        id: Long = 1L,
        tenantId: Long = 1L,
        unitId: Long = 1L,
        rentAmount: BigDecimal = BigDecimal("2000.00"),
        startDate: LocalDate = LocalDate.now().minusMonths(6),
        endDate: LocalDate = LocalDate.now().plusMonths(6),
        status: LeaseStatus = LeaseStatus.ACTIVE,
    ) = Lease(
        id = id,
        tenantId = tenantId,
        unitId = unitId,
        rentAmount = rentAmount,
        startDate = startDate,
        endDate = endDate,
        status = status,
    )

    fun rentCharge(
        id: Long = 1L,
        leaseId: Long = 1L,
        amount: BigDecimal = BigDecimal("2000.00"),
        dueDate: LocalDate = LocalDate.now().withDayOfMonth(1),
        status: RentChargeStatus = RentChargeStatus.PENDING,
    ) = RentCharge(
        id = id,
        leaseId = leaseId,
        amount = amount,
        dueDate = dueDate,
        status = status,
    )

    fun paymentCard(
        id: Long = 1L,
        tenantId: Long = 1L,
        stripePaymentMethodId: String = "pm_test_1",
        brand: String = "visa",
        last4: String = "4242",
    ) = PaymentCard(
        id = id,
        tenantId = tenantId,
        stripePaymentMethodId = stripePaymentMethodId,
        brand = brand,
        last4 = last4,
        expMonth = 12,
        expYear = 2030,
    )
}
