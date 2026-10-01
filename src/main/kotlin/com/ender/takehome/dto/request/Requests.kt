package com.ender.takehome.dto.request

import com.ender.takehome.model.PaymentMethod
import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.time.LocalDate

data class CreateTenantRequest(
    @field:NotBlank val firstName: String,
    @field:NotBlank val lastName: String,
    @field:NotBlank @field:Email val email: String,
    val phone: String? = null,
)

data class UpdateTenantRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
)

data class CreatePropertyRequest(
    @field:NotNull val pmId: Long,
    @field:NotBlank val name: String,
    @field:NotBlank val address: String,
)

data class CreateUnitRequest(
    @field:NotNull val propertyId: Long,
    @field:NotBlank val unitNumber: String,
)

data class CreateLeaseRequest(
    @field:NotNull val tenantId: Long,
    @field:NotNull val unitId: Long,
    @field:NotNull @field:DecimalMin("0.01") val rentAmount: BigDecimal,
    @field:NotNull val startDate: LocalDate,
    @field:NotNull val endDate: LocalDate,
)

data class RecordPaymentRequest(
    @field:NotNull val rentChargeId: Long,
    @field:NotNull @field:DecimalMin("0.01") val amount: BigDecimal,
    @field:NotNull val paymentMethod: PaymentMethod,
    val notes: String? = null,
    @field:NotBlank val recordedBy: String,
)

data class GenerateRentChargesRequest(
    val dueDate: LocalDate? = null,
)

data class RegisterCardRequest(
    @field:NotBlank val stripePaymentMethodId: String,
)

data class CardPaymentRequest(
    @field:NotNull val cardId: Long,
)
