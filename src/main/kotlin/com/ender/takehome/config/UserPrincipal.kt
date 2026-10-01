package com.ender.takehome.config

import com.ender.takehome.model.UserRole
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails

data class UserPrincipal(
    val userId: Long,
    val email: String,
    val role: UserRole,
    val tenantId: Long?,
    val pmId: Long?,
) : UserDetails {

    override fun getAuthorities(): Collection<GrantedAuthority> =
        listOf(SimpleGrantedAuthority("ROLE_${role.name}"))

    override fun getPassword(): String = ""
    override fun getUsername(): String = email

    companion object {
        fun current(): UserPrincipal =
            SecurityContextHolder.getContext().authentication.principal as UserPrincipal

        /** Tenant ID of the current user. Only call from tenant-only endpoints. */
        fun currentTenantId(): Long =
            checkNotNull(current().tenantId) { "Current user is not a tenant" }
    }
}
