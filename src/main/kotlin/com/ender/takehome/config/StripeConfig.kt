package com.ender.takehome.config

import com.stripe.StripeClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class StripeConfig(
    @Value("\${stripe.secret-key}") private val secretKey: String,
) {

    @Bean
    fun stripeClient(): StripeClient = StripeClient(secretKey)
}
