package com.ender.takehome.billing

import com.ender.takehome.TestFixtures
import com.ender.takehome.config.JwtAuthenticationFilter
import com.ender.takehome.config.JwtService
import com.ender.takehome.dto.request.RegisterCardRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.model.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(CardApi::class)
@Import(CardApiTest.TestSecurityConfig::class)
class CardApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var cardModule: CardModule

    @Autowired
    private lateinit var jwtService: JwtService

    @TestConfiguration
    @EnableMethodSecurity
    class TestSecurityConfig {
        @Bean
        fun jwtService(): JwtService = JwtService(
            secret = "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256",
            expirationMs = 86400000L,
        )

        @Bean
        fun jwtAuthenticationFilter(jwtService: JwtService) = JwtAuthenticationFilter(jwtService)

        @Bean
        fun securityFilterChain(http: HttpSecurity, jwtFilter: JwtAuthenticationFilter): SecurityFilterChain = http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/**").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()

        @Bean
        fun cardModule(): CardModule = mockk()
    }

    private fun tenantToken(tenantId: Long = 1L): String = jwtService.generateToken(
        userId = 2L, email = "tenant@test.com", role = UserRole.TENANT, tenantId = tenantId, pmId = null
    )

    private fun pmToken(): String = jwtService.generateToken(
        userId = 1L, email = "pm@test.com", role = UserRole.PROPERTY_MANAGER, tenantId = null, pmId = 1L
    )

    @Test
    fun `POST setup-intent returns client secret for the caller's tenant`() {
        every { cardModule.createSetupIntent(1L) } returns "seti_secret"

        mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.clientSecret") { value("seti_secret") }
        }
    }

    @Test
    fun `POST cards registers a card and does not expose Stripe IDs`() {
        every { cardModule.registerCard(1L, "pm_test_1") } returns TestFixtures.paymentCard(id = 5)

        mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RegisterCardRequest("pm_test_1"))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(5) }
            jsonPath("$.last4") { value("4242") }
            jsonPath("$.stripePaymentMethodId") { doesNotExist() }
        }
    }

    @Test
    fun `POST cards rejects a blank payment method`() {
        mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"stripePaymentMethodId": ""}"""
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `GET cards lists the caller's cards`() {
        every { cardModule.listCards(1L, null, 20) } returns
            CursorPage(listOf(TestFixtures.paymentCard()), hasMore = false)

        mockMvc.get("/api/cards") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].brand") { value("visa") }
        }
    }

    @Test
    fun `card endpoints are tenant-only`() {
        mockMvc.get("/api/cards") {
            header("Authorization", "Bearer ${pmToken()}")
        }.andExpect {
            status { isForbidden() }
        }
    }
}
