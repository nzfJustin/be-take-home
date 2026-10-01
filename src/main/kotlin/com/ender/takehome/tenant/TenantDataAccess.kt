package com.ender.takehome.tenant

import com.ender.takehome.generated.tables.Tenants.TENANTS
import com.ender.takehome.generated.tables.records.TenantsRecord
import com.ender.takehome.model.Tenant
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class TenantDataAccess(private val dsl: DSLContext) {

    fun findById(id: Long): Tenant? =
        dsl.selectFrom(TENANTS)
            .where(TENANTS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findByEmail(email: String): Tenant? =
        dsl.selectFrom(TENANTS)
            .where(TENANTS.EMAIL.eq(email))
            .fetchOne()
            ?.toModel()

    fun findAllCursor(startAfterId: Long?, limit: Int): List<Tenant> =
        dsl.selectFrom(TENANTS)
            .where(cursorCondition(startAfterId))
            .orderBy(TENANTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun save(tenant: Tenant): Tenant {
        if (tenant.id == 0L) {
            val record = dsl.newRecord(TENANTS).apply {
                firstName = tenant.firstName
                lastName = tenant.lastName
                email = tenant.email
                phone = tenant.phone
            }
            record.store()
            return tenant.copy(id = record.id!!)
        }
        dsl.update(TENANTS)
            .set(TENANTS.FIRST_NAME, tenant.firstName)
            .set(TENANTS.LAST_NAME, tenant.lastName)
            .set(TENANTS.EMAIL, tenant.email)
            .set(TENANTS.PHONE, tenant.phone)
            .where(TENANTS.ID.eq(tenant.id))
            .execute()
        return tenant
    }

    private fun cursorCondition(startAfterId: Long?) =
        if (startAfterId != null) TENANTS.ID.gt(startAfterId) else DSL.noCondition()

    private fun TenantsRecord.toModel() = Tenant(
        id = id!!,
        firstName = firstName!!,
        lastName = lastName!!,
        email = email!!,
        phone = phone,
        stripeCustomerId = stripeCustomerId,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
