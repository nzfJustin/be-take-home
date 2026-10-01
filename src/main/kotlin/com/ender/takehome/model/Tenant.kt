package com.ender.takehome.model

import java.time.Instant

data class Tenant(
    val id: Long = 0,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String? = null,
    val stripeCustomerId: String? = null,
    val createdAt: Instant = Instant.now(),
)
