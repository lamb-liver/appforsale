package com.lambliver.stallpos

import com.lambliver.stallpos.data.FakePosPersistence
import com.lambliver.stallpos.domain.CheckoutWriteRequest
import com.lambliver.stallpos.domain.PaymentMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PosStoreCommitCheckoutTest {

    private fun request(total: Long = 10L) = CheckoutWriteRequest(
        productCart = emptyMap(),
        bundleCart = emptyMap(),
        subtotal = total,
        discount = 0L,
        total = total,
        dateKey = "2026-05-18",
        paymentMethod = PaymentMethod.CASH,
        checkoutLines = emptyList(),
        stockDeductions = emptyMap(),
        tipAmount = 0L,
    )

    @Test
    fun commitCheckout_negativeTotal_throws() = runTest {
        val store = FakePosPersistence()
        try {
            store.commitCheckout(request(total = -1L))
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals(0L, store.txCountFlow.first())
        }
    }

    @Test
    fun commitCheckout_generatesUniqueSaleIdsAndLinksLastCheckout() = runTest {
        val store = FakePosPersistence()
        store.commitCheckout(request())
        store.commitCheckout(request())

        val sales = store.salesLogFlow.first()
        assertEquals(2, sales.map { it.id }.toSet().size)
        sales.forEach { assertEquals(it.id, UUID.fromString(it.id).toString()) }
        assertEquals(sales.last().id, store.lastCheckoutFlow.first()!!.saleId)
    }
}
