package com.ender.takehome

import com.ender.takehome.billing.FakePaymentGateway
import com.ender.takehome.billing.GatewayEvent
import com.ender.takehome.model.PaymentStatus
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * End-to-end card payment flow over HTTP, against the real database and security config,
 * with Stripe replaced by [FakePaymentGateway].
 *
 * Seed data: Alice (tenant 1) owns rent charge 1 ($2500, PENDING); Bob (tenant 2) owns charge 3 ($1800, PENDING).
 */
@Tag("integration")
class CardPaymentIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var fakeGateway: FakePaymentGateway

    private fun login(email: String): String {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "password"))
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString).get("token").asText()
    }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)

    /** Full card setup as a client would do it: SetupIntent -> Stripe.js confirm -> register. Returns the card ID. */
    private fun saveCard(token: String, paymentMethodId: String): Long {
        val clientSecret = mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.json().get("clientSecret").asText()

        fakeGateway.confirmSetupIntent(clientSecret, paymentMethodId)

        return mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to paymentMethodId))
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.last4") { value("4242") }
        }.json().get("id").asLong()
    }

    private fun payCharge(token: String, chargeId: Long, cardId: Long) =
        mockMvc.post("/api/rent-charges/$chargeId/card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("cardId" to cardId))
            header("Authorization", "Bearer $token")
        }

    private fun chargeStatus(token: String, chargeId: Long): String =
        mockMvc.get("/api/rent-charges/$chargeId") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.json().get("status").asText()

    private fun sendWebhook(event: GatewayEvent, signature: String = FakePaymentGateway.VALID_SIGNATURE) =
        mockMvc.post("/api/stripe/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(event)
            header("Stripe-Signature", signature)
        }

    @Test
    fun `tenant saves a card, pays rent, and a refund webhook reopens the charge`() {
        val alice = login("alice.johnson@email.com")
        val cardId = saveCard(alice, "pm_alice_visa")

        mockMvc.get("/api/cards") {
            header("Authorization", "Bearer $alice")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[?(@.id == $cardId)].brand") { value("visa") }
        }

        val payment = payCharge(alice, 1, cardId).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("SUCCEEDED") }
            jsonPath("$.paymentMethod") { value("CARD") }
            jsonPath("$.amount") { value(2500.0) }
            jsonPath("$.paymentCardId") { value(cardId) }
        }.json()
        assertEquals("PAID", chargeStatus(alice, 1))

        // Paying an already-paid charge is a conflict, not a second charge.
        payCharge(alice, 1, cardId).andExpect { status { isConflict() } }

        // Stripe's own succeeded webhook arrives afterwards: harmless duplicate.
        val paymentIntentId = "pi_fake_${payment.get("id").asLong()}"
        sendWebhook(GatewayEvent("evt_1", paymentIntentId, null, PaymentStatus.SUCCEEDED))
            .andExpect { status { isOk() } }
        assertEquals("PAID", chargeStatus(alice, 1))

        // Refund issued in the Stripe dashboard.
        sendWebhook(GatewayEvent("evt_2", paymentIntentId, null, PaymentStatus.REFUNDED))
            .andExpect { status { isOk() } }
        assertEquals("PENDING", chargeStatus(alice, 1))

        val pm = login("admin@greenfieldproperties.com")
        mockMvc.get("/api/payments") {
            param("rentChargeId", "1")
            header("Authorization", "Bearer $pm")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[?(@.id == ${payment.get("id").asLong()})].status") { value("REFUNDED") }
        }
    }

    @Test
    fun `tenant cannot pay another tenant's rent charge`() {
        val bob = login("bob.smith@email.com")
        val bobCard = saveCard(bob, "pm_bob_visa")

        // Charge 1 belongs to Alice: reported as not found so its existence isn't leaked.
        payCharge(bob, 1, bobCard).andExpect { status { isNotFound() } }
    }

    @Test
    fun `declined card leaves the charge unpaid`() {
        val bob = login("bob.smith@email.com")
        val declinedCard = saveCard(bob, "pm_bob_declined")

        payCharge(bob, 3, declinedCard).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.failureReason") { value("Your card was declined.") }
        }
        assertEquals("PENDING", chargeStatus(bob, 3))
    }

    @Test
    fun `property managers cannot use tenant card endpoints`() {
        val pm = login("admin@greenfieldproperties.com")

        mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer $pm")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `webhook with an invalid signature is rejected`() {
        sendWebhook(GatewayEvent("evt_x", "pi_x", null, PaymentStatus.SUCCEEDED), signature = "forged")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `webhook for an unknown payment is acknowledged`() {
        sendWebhook(GatewayEvent("evt_y", "pi_unknown", null, PaymentStatus.SUCCEEDED))
            .andExpect { status { isOk() } }
    }
}
