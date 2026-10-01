package com.ender.takehome.tenant

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.Tenant
import org.springframework.stereotype.Service

@Service
class TenantModule(private val dataAccess: TenantDataAccess) {

    fun getAll(startAfterId: Long?, limit: Int): CursorPage<Tenant> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findAllCursor(startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    fun getById(id: Long): Tenant =
        dataAccess.findById(id)
            ?: throw ResourceNotFoundException("Tenant not found: $id")

    fun create(firstName: String, lastName: String, email: String, phone: String?): Tenant {
        val tenant = Tenant(firstName = firstName, lastName = lastName, email = email, phone = phone)
        return dataAccess.save(tenant)
    }

    fun update(id: Long, firstName: String?, lastName: String?, email: String?, phone: String?): Tenant {
        val tenant = getById(id)
        val updated = tenant.copy(
            firstName = firstName ?: tenant.firstName,
            lastName = lastName ?: tenant.lastName,
            email = email ?: tenant.email,
            phone = phone ?: tenant.phone,
        )
        return dataAccess.save(updated)
    }

    /**
     * Link [id] to [stripeCustomerId] if it has no customer yet, and return the tenant as stored.
     * If a concurrent request won, the returned tenant carries the winner's customer ID.
     */
    fun linkStripeCustomer(id: Long, stripeCustomerId: String): Tenant {
        dataAccess.setStripeCustomerIdIfAbsent(id, stripeCustomerId)
        return getById(id)
    }
}
