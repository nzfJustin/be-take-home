package com.ender.takehome.model

import com.ender.takehome.model.PaymentStatus.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaymentStatusTest {

    private val allowed = setOf(
        INITIATED to SUCCEEDED,
        INITIATED to FAILED,
        SUCCEEDED to REFUNDED,
    )

    @Test
    fun `only the documented lifecycle transitions are allowed`() {
        for (from in PaymentStatus.entries) {
            for (to in PaymentStatus.entries) {
                assertEquals(
                    (from to to) in allowed,
                    from.canTransitionTo(to),
                    "$from -> $to",
                )
            }
        }
    }

    @Test
    fun `active statuses are initiated and succeeded`() {
        assertEquals(setOf(INITIATED, SUCCEEDED), PaymentStatus.ACTIVE)
    }
}
