package com.lambliver.stallpos

import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.data.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DataStore 端到端：`checkout`／`undoLastCheckout`／購物車持久化（需在裝置／模擬器執行）。
 */
@RunWith(AndroidJUnit4::class)
class PosStoreInstrumentedTest {

    private val appCtx =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private lateinit var store: PosStore
    private lateinit var productId: String

    @After
    fun cleanup() {
        if (!::productId.isInitialized || !::store.isInitialized) return
        runBlocking {
            val products = store.productsFlow.first().filter { it.id != productId }
            store.applyCatalog(CatalogPersistPlan(products = products))
            store.clearCart()
        }
    }

    @Test
    fun checkout_then_undo_restores_stock_sales_tx_and_cart() = runBlocking {
        store = PosStore(appCtx)
        productId = "itest-" + UUID.randomUUID().toString().take(8)

        store.applyCatalog(
            CatalogPersistPlan(products = listOf(Product(productId, "Instrumented 測試品", 50L, "", stock = 5L))),
        )
        store.saveCart(PosCart(mapOf(productId to 2)))

        val salesBefore = store.totalSalesFlow.first()
        val txBefore = store.txCountFlow.first()

        store.commitCheckout(
            CheckoutWriteRequest(
                productCart = mapOf(productId to 2),
                bundleCart = emptyMap(),
                subtotal = 100L,
                discount = 0L,
                total = 100L,
                dateKey = "2099-01-01",
                paymentMethod = PaymentMethod.CASH,
                checkoutLines = emptyList(),
                stockDeductions = mapOf(productId to 2L),
                tipAmount = 0L,
            ),
        )

        assertEquals(salesBefore + 100L, store.totalSalesFlow.first())
        assertEquals(txBefore + 1L, store.txCountFlow.first())
        assertEquals(PosCart(), store.cartFlow.first())
        assertEquals(3L, store.productsFlow.first().single { it.id == productId }.stock)

        store.undoLastCheckout()

        assertEquals(salesBefore, store.totalSalesFlow.first())
        assertEquals(txBefore, store.txCountFlow.first())
        assertEquals(5L, store.productsFlow.first().single { it.id == productId }.stock)
        assertEquals(PosCart(mapOf(productId to 2)), store.cartFlow.first())
    }

    @Test
    fun saveCart_roundTripsThroughDataStore() = runBlocking {
        val s = PosStore(appCtx)
        val cart = PosCart(products = mapOf("onlyProd" to 3), bundles = emptyMap())
        s.saveCart(cart)
        assertEquals(cart, s.cartFlow.first())
        s.clearCart()
        assertEquals(PosCart(), s.cartFlow.first())
    }
}
