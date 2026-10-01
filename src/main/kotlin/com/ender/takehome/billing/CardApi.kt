package com.ender.takehome.billing

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.RegisterCardRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.PaymentCardResponse
import com.ender.takehome.dto.response.SetupIntentResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

/** Saved cards for the authenticated tenant. The tenant always comes from the token, never the request. */
@RestController
@RequestMapping("/api/cards")
@PreAuthorize("hasRole('TENANT')")
class CardApi(private val cardModule: CardModule) {

    @PostMapping("/setup-intent")
    fun createSetupIntent(): SetupIntentResponse =
        SetupIntentResponse(cardModule.createSetupIntent(UserPrincipal.currentTenantId()))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterCardRequest): PaymentCardResponse =
        PaymentCardResponse.from(cardModule.registerCard(UserPrincipal.currentTenantId(), request.stripePaymentMethodId))

    @GetMapping
    fun list(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<PaymentCardResponse> {
        val page = cardModule.listCards(UserPrincipal.currentTenantId(), startAfterId, limit)
        return CursorPage(page.content.map { PaymentCardResponse.from(it) }, page.hasMore)
    }
}
