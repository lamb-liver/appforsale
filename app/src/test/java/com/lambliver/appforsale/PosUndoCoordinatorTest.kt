package com.lambliver.appforsale

import com.lambliver.appforsale.data.FakePosPersistence
import com.lambliver.appforsale.data.PosPersistSnapshot
import com.lambliver.appforsale.domain.LastCheckout
import com.lambliver.appforsale.domain.PaymentMethod
import com.lambliver.appforsale.domain.PosCart
import com.lambliver.appforsale.domain.PosUndoCoordinator
import com.lambliver.appforsale.domain.Product
import com.lambliver.appforsale.domain.SaleRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosUndoCoordinatorTest {

    private val p1 = Product("p1", "品", 20L, stock = 5L)
    private val pNoStock = Product("p2", "無庫存", 10L, stock = null)

    private fun undoState(
        products: List<Product> = listOf(p1),
        totalSales: Long = 120L,
        txCount: Long = 1L,
        salesLog: List<SaleRecord> = listOf(sampleSale()),
        lastCheckout: LastCheckout? = sampleLastCheckout(),
    ) = PosUndoCoordinator.UndoState(
        products = products,
        totalSales = totalSales,
        txCount = txCount,
        salesLog = salesLog,
        lastCheckout = lastCheckout,
    )

    private fun sampleSale() = SaleRecord(
        tsMillis = 1L,
        dateKey = "2026-05-01",
        subtotal = 100L,
        discount = 0L,
        total = 100L,
        cartSnapshot = mapOf("p1" to 2),
        paymentMethod = PaymentMethod.CASH,
        stockDeductions = mapOf("p1" to 2L),
    )

    private fun sampleLastCheckout() = LastCheckout(
        tsMillis = 1L,
        total = 120L,
        productCart = mapOf("p1" to 2),
        stockDeductions = mapOf("p1" to 2L),
    )

    @Test
    fun computeUndo_nothingToUndo_whenLastCheckoutMissing() {
        val r = PosUndoCoordinator.computeUndo(undoState(lastCheckout = null))
        assertTrue(r is PosUndoCoordinator.UndoResult.NothingToUndo)
    }

    @Test
    fun computeUndo_ready_restoresStockCartSalesAndTotals() {
        val r = PosUndoCoordinator.computeUndo(undoState()) as PosUndoCoordinator.UndoResult.Ready
        assertEquals(7L, r.effects.products.single().stock)
        assertEquals(PosCart(mapOf("p1" to 2)), r.effects.cart)
        assertEquals(0L, r.effects.totalSales)
        assertEquals(0L, r.effects.txCount)
        assertTrue(r.effects.salesLog.isEmpty())
    }

    @Test
    fun computeUndo_usesProductCartWhenStockDeductionsEmpty() {
        val last = LastCheckout(
            tsMillis = 1L,
            total = 50L,
            productCart = mapOf("p1" to 3),
            stockDeductions = emptyMap(),
        )
        val r = PosUndoCoordinator.computeUndo(undoState(lastCheckout = last)) as PosUndoCoordinator.UndoResult.Ready
        assertEquals(8L, r.effects.products.single().stock)
    }

    @Test
    fun computeUndo_skipsProductsWithoutTrackedStock() {
        val last = LastCheckout(
            tsMillis = 1L,
            total = 10L,
            productCart = mapOf("p2" to 1),
            stockDeductions = mapOf("p2" to 1L),
        )
        val r = PosUndoCoordinator.computeUndo(
            undoState(
                products = listOf(pNoStock),
                lastCheckout = last,
                salesLog = listOf(sampleSale()),
                totalSales = 10L,
            ),
        ) as PosUndoCoordinator.UndoResult.Ready
        assertNull(r.effects.products.single().stock)
    }

    /**
     * 決策：有 lastCheckout 但 salesLog 為空時仍 Ready（best-effort），不拋錯、不回 NothingToUndo。
     * 正常結帳不變量下不應發生；防禦損壞備份／手動編輯 JSON。
     */
    @Test
    fun computeUndo_ready_whenSalesLogEmpty_bestEffortWithoutRemoveAt() {
        val r = PosUndoCoordinator.computeUndo(
            undoState(salesLog = emptyList(), lastCheckout = sampleLastCheckout()),
        ) as PosUndoCoordinator.UndoResult.Ready
        assertTrue(r.effects.salesLog.isEmpty())
        assertEquals(PosCart(mapOf("p1" to 2)), r.effects.cart)
        assertEquals(0L, r.effects.totalSales)
        assertEquals(0L, r.effects.txCount)
        assertEquals(7L, r.effects.products.single().stock)
    }

    @Test
    fun applyUndoIfPossible_persistsViaFakePosPersistence() = runBlocking {
        val persist = FakePosPersistence(
            PosPersistSnapshot(
                products = listOf(p1),
                cart = PosCart(),
                totalSales = 120L,
                txCount = 1L,
                salesLog = listOf(sampleSale()),
                lastCheckout = sampleLastCheckout(),
            ),
        )
        persist.undoLastCheckout()

        assertEquals(0L, persist.totalSalesFlow.first())
        assertEquals(0L, persist.txCountFlow.first())
        assertEquals(PosCart(mapOf("p1" to 2)), persist.cartFlow.first())
        assertEquals(7L, persist.productsFlow.first().single().stock)
        assertNull(persist.lastCheckoutFlow.first())
    }
}
