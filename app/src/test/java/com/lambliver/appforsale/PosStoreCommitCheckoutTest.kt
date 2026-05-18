package com.lambliver.appforsale

import com.lambliver.appforsale.data.FakePosPersistence
import com.lambliver.appforsale.domain.CheckoutWriteRequest
import com.lambliver.appforsale.domain.PaymentMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PosStoreCommitCheckoutTest {

    @Test
    fun commitCheckout_negativeTotal_throws() = runTest {
        val store = FakePosPersistence()
        try {
            store.commitCheckout(
                CheckoutWriteRequest(
                    productCart = emptyMap(),
                    bundleCart = emptyMap(),
                    subtotal = 0L,
                    discount = 0L,
                    total = -1L,
                    dateKey = "2026-05-18",
                    paymentMethod = PaymentMethod.CASH,
                ),
            )
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals(0L, store.txCountFlow.first())
        }
    }
}
