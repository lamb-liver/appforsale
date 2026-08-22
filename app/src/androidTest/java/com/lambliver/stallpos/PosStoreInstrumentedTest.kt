package com.lambliver.stallpos

import android.util.Log
import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.data.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

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
        val auditSalesBefore = store.salesLogFlow.first().size
        val reversalsBefore = store.reversalLogFlow.first().size

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
        val saleId = store.salesLogFlow.first().last().id
        assertEquals(auditSalesBefore + 1, store.salesLogFlow.first().size)
        assertEquals(saleId, store.lastCheckoutFlow.first()!!.saleId)

        store.undoLastCheckout()

        assertEquals(salesBefore, store.totalSalesFlow.first())
        assertEquals(txBefore, store.txCountFlow.first())
        assertEquals(5L, store.productsFlow.first().single { it.id == productId }.stock)
        assertEquals(PosCart(mapOf(productId to 2)), store.cartFlow.first())
        assertEquals(auditSalesBefore + 1, store.salesLogFlow.first().size)
        assertEquals(reversalsBefore + 1, store.reversalLogFlow.first().size)
        assertEquals(saleId, store.reversalLogFlow.first().last().saleId)

        val afterUndo = store.snapshot.first()
        store.undoLastCheckout()
        assertEquals(afterUndo, store.snapshot.first())

        val reopenedStore = PosStore(appCtx)
        assertEquals(saleId, reopenedStore.salesLogFlow.first().last().id)
        assertEquals(saleId, reopenedStore.reversalLogFlow.first().last().saleId)
        assertEquals(PosCart(mapOf(productId to 2)), reopenedStore.cartFlow.first())
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

    @Test
    fun largeHistory_observesCorePaths_withoutHardTimingGate() = runBlocking {
        store = PosStore(appCtx)
        val original = store.exportFullBackupJson()
        try {
            listOf(100, 1_000, 5_000).forEach { size ->
                val sales = List(size) { index ->
                    SaleRecord(
                        id = "fixture-$size-$index",
                        tsMillis = index.toLong(),
                        dateKey = "2099-01-01",
                        subtotal = 1L,
                        discount = 0L,
                        total = 1L,
                        cartSnapshot = emptyMap(),
                    )
                }
                val reversals = sales.filterIndexed { index, _ -> index % 1_000 == 0 }.mapIndexed { index, sale ->
                    SaleReversal(
                        id = "fixture-reversal-$size-$index",
                        saleId = sale.id,
                        tsMillis = size.toLong() + index,
                        reason = ReversalReason.UNDO_LAST_CHECKOUT,
                    )
                }
                val activeCount = size - reversals.size
                val payload = JSONObject()
                    .put("payloadSchema", PosStore.BACKUP_SCHEMA_VERSION)
                    .put("products_json", "[]")
                    .put("categories_json", "[]")
                    .put("bundle_categories_json", "[]")
                    .put("bundles_json", "[]")
                    .put("cart_json", encodePosCartJson(PosCart()))
                    .put("sales_log_json", encodeSalesRecordsJson(sales))
                    .put("reversal_log_json", encodeSaleReversalsJson(reversals))
                    .put("last_checkout_json", "")
                    .put("total_sales", activeCount.toLong())
                    .put("tx_count", activeCount.toLong())
                val backup = JSONObject()
                    .put("format", PosStore.BACKUP_FORMAT_ID)
                    .put("schemaVersion", PosStore.BACKUP_SCHEMA_VERSION)
                    .put("exportedAtMillis", System.currentTimeMillis())
                    .put("payload", payload)
                    .toString()

                val restoreMs = measureTimeMillis { store.restoreFullBackupJson(backup).getOrThrow() }
                lateinit var initial: PosPersistSnapshot
                val startupMs = measureTimeMillis { initial = store.snapshot.first() }
                var dashboardRevenue = 0L
                val dashboardMs = measureTimeMillis {
                    dashboardRevenue = activeSales(initial.salesLog, initial.reversalLog)
                        .sumOf { it.total + it.tipAmount }
                }
                val checkoutMs = measureTimeMillis {
                    store.commitCheckout(
                        CheckoutWriteRequest(
                            productCart = emptyMap(),
                            bundleCart = emptyMap(),
                            subtotal = 1L,
                            discount = 0L,
                            total = 1L,
                            dateKey = "2099-01-01",
                            paymentMethod = PaymentMethod.CASH,
                            checkoutLines = emptyList(),
                            stockDeductions = emptyMap(),
                            tipAmount = 0L,
                        ),
                    )
                }
                val undoMs = measureTimeMillis { store.undoLastCheckout() }
                val afterUndo = store.snapshot.first()
                lateinit var csv: String
                val csvMs = measureTimeMillis {
                    csv = buildPosSalesCsv(
                        "2099-01-01",
                        afterUndo.salesLog,
                        afterUndo.reversalLog,
                        afterUndo.products,
                        afterUndo.bundles,
                    )
                }

                assertEquals(size + 1, afterUndo.salesLog.size)
                assertEquals(reversals.size + 1, afterUndo.reversalLog.size)
                assertEquals(activeCount, activeSales(afterUndo.salesLog, afterUndo.reversalLog).size)
                assertEquals(activeCount.toLong(), dashboardRevenue)
                assertTrue(csv.contains("報表,累積筆數,$activeCount"))
                Log.i(
                    "PosPerf",
                    "sales=$size restore=${restoreMs}ms startup=${startupMs}ms checkout=${checkoutMs}ms " +
                        "undo=${undoMs}ms dashboard=${dashboardMs}ms csv=${csvMs}ms",
                )
            }
        } finally {
            store.restoreFullBackupJson(original).getOrThrow()
        }
    }
}
