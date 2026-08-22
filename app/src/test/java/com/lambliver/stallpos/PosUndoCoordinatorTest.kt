package com.lambliver.stallpos

import com.lambliver.stallpos.data.FakePosPersistence
import com.lambliver.stallpos.data.PosPersistSnapshot
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosUndoCoordinatorTest {

    private val p1 = Product("p1", "品", 20L, stock = 5L)
    private val pNoStock = Product("p2", "無庫存", 10L, stock = null)

    private fun sampleSale(id: String = "sale-1") = SaleRecord(
        id = id,
        tsMillis = 1L,
        dateKey = "2026-05-01",
        subtotal = 100L,
        discount = 0L,
        total = 100L,
        cartSnapshot = mapOf("p1" to 2),
        paymentMethod = PaymentMethod.CASH,
        tipAmount = 20L,
        stockDeductions = mapOf("p1" to 2L),
    )

    private fun sampleLastCheckout(saleId: String = "sale-1") = LastCheckout(
        saleId = saleId,
        tsMillis = 1L,
        total = 120L,
        productCart = mapOf("p1" to 2),
        stockDeductions = mapOf("p1" to 2L),
    )

    private fun undoState(
        products: List<Product> = listOf(p1),
        totalSales: Long = 120L,
        txCount: Long = 1L,
        salesLog: List<SaleRecord> = listOf(sampleSale()),
        reversalLog: List<SaleReversal> = emptyList(),
        lastCheckout: LastCheckout? = sampleLastCheckout(),
    ) = PosUndoCoordinator.UndoState(
        products = products,
        totalSales = totalSales,
        txCount = txCount,
        salesLog = salesLog,
        reversalLog = reversalLog,
        lastCheckout = lastCheckout,
    )

    private fun compute(state: PosUndoCoordinator.UndoState) =
        PosUndoCoordinator.computeUndo(state, reversalId = "reversal-1", reversedAtMillis = 9L)

    @Test
    fun computeUndo_nothingToUndo_whenLastCheckoutMissing() {
        assertTrue(compute(undoState(lastCheckout = null)) is PosUndoCoordinator.UndoResult.NothingToUndo)
    }

    @Test
    fun computeUndo_ready_restoresStockCartCachesAndAppendsReversal() {
        val r = compute(undoState()) as PosUndoCoordinator.UndoResult.Ready
        assertEquals(7L, r.effects.products.single().stock)
        assertEquals(PosCart(mapOf("p1" to 2)), r.effects.cart)
        assertEquals(0L, r.effects.totalSales)
        assertEquals(0L, r.effects.txCount)
        assertEquals(
            SaleReversal("reversal-1", "sale-1", 9L, ReversalReason.UNDO_LAST_CHECKOUT),
            r.effects.reversalLog.single(),
        )
    }

    @Test
    fun computeUndo_usesProductCartWhenStockDeductionsEmpty() {
        val last = sampleLastCheckout().copy(
            total = 50L,
            productCart = mapOf("p1" to 3),
            stockDeductions = emptyMap(),
        )
        val r = compute(undoState(lastCheckout = last)) as PosUndoCoordinator.UndoResult.Ready
        assertEquals(8L, r.effects.products.single().stock)
    }

    @Test
    fun computeUndo_skipsProductsWithoutTrackedStock() {
        val last = sampleLastCheckout().copy(
            total = 10L,
            productCart = mapOf("p2" to 1),
            stockDeductions = mapOf("p2" to 1L),
        )
        val r = compute(
            undoState(products = listOf(pNoStock), lastCheckout = last, totalSales = 10L),
        ) as PosUndoCoordinator.UndoResult.Ready
        assertNull(r.effects.products.single().stock)
    }

    @Test
    fun computeUndo_nothingToUndo_whenSaleIdIsOrphan() {
        assertTrue(
            compute(undoState(salesLog = emptyList())) is PosUndoCoordinator.UndoResult.NothingToUndo,
        )
    }

    @Test
    fun computeUndo_nothingToUndo_whenSaleAlreadyReversed() {
        val reversal = SaleReversal("r0", "sale-1", 8L, ReversalReason.UNDO_LAST_CHECKOUT)
        assertTrue(
            compute(undoState(reversalLog = listOf(reversal))) is PosUndoCoordinator.UndoResult.NothingToUndo,
        )
    }

    @Test
    fun applyUndoIfPossible_keepsSaleAndPersistsSingleReversal() = runBlocking {
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
        persist.undoLastCheckout()

        assertEquals(0L, persist.totalSalesFlow.first())
        assertEquals(0L, persist.txCountFlow.first())
        assertEquals(1, persist.salesLogFlow.first().size)
        assertEquals("sale-1", persist.reversalLogFlow.first().single().saleId)
        assertEquals(PosCart(mapOf("p1" to 2)), persist.cartFlow.first())
        assertEquals(7L, persist.productsFlow.first().single().stock)
        assertNull(persist.lastCheckoutFlow.first())
    }
}
