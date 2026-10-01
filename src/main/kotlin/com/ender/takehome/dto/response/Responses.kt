package com.ender.takehome.dto.response

import com.ender.takehome.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class PropertyManagerResponse(
    val id: Long,
    val name: String,
    val email: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(pm: PropertyManager) = PropertyManagerResponse(pm.id, pm.name, pm.email, pm.createdAt)
    }
}

data class PropertyResponse(
    val id: Long,
    val pmId: Long,
    val name: String,
    val address: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(p: Property) = PropertyResponse(p.id, p.pmId, p.name, p.address, p.createdAt)
    }
}

data class UnitResponse(
    val id: Long,
    val propertyId: Long,
    val unitNumber: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(u: PropertyUnit) = UnitResponse(u.id, u.propertyId, u.unitNumber, u.createdAt)
    }
}

data class TenantResponse(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(t: Tenant) = TenantResponse(t.id, t.firstName, t.lastName, t.email, t.phone, t.createdAt)
    }
}

data class LeaseResponse(
    val id: Long,
    val tenantId: Long,
    val unitId: Long,
    val rentAmount: BigDecimal,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: LeaseStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(l: Lease) = LeaseResponse(
            l.id, l.tenantId, l.unitId, l.rentAmount, l.startDate, l.endDate, l.status, l.createdAt
        )
    }
}

data class RentChargeResponse(
    val id: Long,
    val leaseId: Long,
    val amount: BigDecimal,
    val dueDate: LocalDate,
    val status: RentChargeStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(rc: RentCharge) = RentChargeResponse(
            rc.id, rc.leaseId, rc.amount, rc.dueDate, rc.status, rc.createdAt
        )
    }
}

data class PaymentResponse(
    val id: Long,
    val rentChargeId: Long,
    val amount: BigDecimal,
    val paymentMethod: PaymentMethod,
    val status: PaymentStatus,
    val paymentCardId: Long?,
    val failureReason: String?,
    val notes: String?,
    val recordedBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(p: Payment) = PaymentResponse(
            p.id, p.rentChargeId, p.amount, p.paymentMethod, p.status, p.paymentCardId, p.failureReason,
            p.notes, p.recordedBy, p.createdAt, p.updatedAt,
        )
    }
}

data class SetupIntentResponse(
    val clientSecret: String,
)

data class PaymentCardResponse(
    val id: Long,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int,
    val createdAt: Instant,
) {
    companion object {
        fun from(c: PaymentCard) = PaymentCardResponse(c.id, c.brand, c.last4, c.expMonth, c.expYear, c.createdAt)
    }
}
