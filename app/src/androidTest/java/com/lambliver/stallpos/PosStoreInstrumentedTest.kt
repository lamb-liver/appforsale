package com.lambliver.stallpos

import android.util.Log
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lambliver.stallpos.data.*
import com.lambliver.stallpos.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/** Room runtime 端到端交易、rollback、關聯與大量歷史檢查。 */
@RunWith(AndroidJUnit4::class)
class PosStoreInstrumentedTest {

    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var database: StallPosDatabase
    private lateinit var store: RoomPosPersistence

    @Before
    fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder<StallPosDatabase>(appCtx)
            .setDriver(BundledSQLiteDriver())
            .build()
        database.posDao().putMeta(AppMetaEntity(LEGACY_IMPORT_VERSION_KEY, "3"))
        store = RoomPosPersistence(appCtx, database)
    }

    @After
    fun cleanup() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun checkout_then_undo_restores_stock_sales_tx_cart_and_persists_identity() = runBlocking {
        val productId = "itest-" + UUID.randomUUID().toString().take(8)
        store.applyCatalog(
            CatalogPersistPlan(products = listOf(Product(productId, "Instrumented 測試品", 50L, stock = 5L))),
        )
        store.saveCart(PosCart(mapOf(productId to 2)))

        store.commitCheckout(checkout(productId, quantity = 2, total = 100L, tip = 20L))

        val checkedOut = store.snapshot.first()
        assertEquals(120L, checkedOut.totalSales)
        assertEquals(1L, checkedOut.txCount)
        assertEquals(PosCart(), checkedOut.cart)
        assertEquals(3L, checkedOut.products.single().stock)
        val sale = checkedOut.salesLog.single()
        assertEquals(sale.id, checkedOut.lastCheckout!!.saleId)
        assertEquals("Instrumented 測試品", (sale.checkoutLines.single() as SaleCheckoutLine.Product).displayName)

        store.undoLastCheckout()

        val undone = store.snapshot.first()
        assertEquals(0L, undone.totalSales)
        assertEquals(0L, undone.txCount)
        assertEquals(5L, undone.products.single().stock)
        assertEquals(PosCart(mapOf(productId to 2)), undone.cart)
        assertEquals(1, undone.salesLog.size)
        assertEquals(sale.id, undone.reversalLog.single().saleId)
        assertEquals(null, undone.lastCheckout)

        store.undoLastCheckout()
        assertEquals(undone, store.snapshot.first())
        val reopened = RoomPosPersistence(appCtx, database).snapshot.first()
        assertEquals(sale.id, reopened.salesLog.single().id)
        assertEquals(sale.id, reopened.reversalLog.single().saleId)
    }

    @Test
    fun identicalSales_haveDifferentIds_andOnlyLatestIsReversed() = runBlocking {
        val productId = "same"
        store.applyCatalog(CatalogPersistPlan(products = listOf(Product(productId, "A", 100L, stock = 10L))))
        val request = checkout(productId, quantity = 1, total = 100L)
        store.commitCheckout(request)
        store.commitCheckout(request)
        val sales = store.salesLogFlow.first()
        assertNotEquals(sales[0].id, sales[1].id)

        store.undoLastCheckout()
        val snap = store.snapshot.first()
        assertEquals(sales[1].id, snap.reversalLog.single().saleId)
        assertEquals(listOf(sales[0].id), activeSales(snap.salesLog, snap.reversalLog).map { it.id })
    }

    @Test
    fun databaseAbort_rollsBack_sale_stock_cart_and_lastCheckout() = runBlocking {
        val productId = "rollback"
        store.applyCatalog(CatalogPersistPlan(products = listOf(Product(productId, "A", 100L, stock = 2L))))
        store.saveCart(PosCart(mapOf(productId to 1)))
        val before = store.snapshot.first()
        database.useWriterConnection {
            it.usePrepared(
                "CREATE TRIGGER fail_sale_line BEFORE INSERT ON sale_lines " +
                    "BEGIN SELECT RAISE(ABORT, 'forced test failure'); END",
            ) { statement -> statement.step() }
        }
        try {
            assertTrue(runCatching { store.commitCheckout(checkout(productId, 1, 100L)) }.isFailure)
        } finally {
            database.useWriterConnection {
                it.usePrepared("DROP TRIGGER fail_sale_line") { statement -> statement.step() }
            }
        }
        assertEquals(before, store.snapshot.first())
    }

    @Test
    fun duplicateBundleComponents_and_nameSnapshot_roundTrip() = runBlocking {
        val product = Product("p", "舊名", 10L, stock = 20L)
        val bundle = Bundle(
            id = "b",
            name = "舊套組名",
            price = 15L,
            components = listOf(BundleComponent("p", 1L), BundleComponent("p", 2L)),
        )
        store.applyCatalog(CatalogPersistPlan(products = listOf(product), bundles = listOf(bundle)))
        assertEquals(bundle.components, store.bundlesFlow.first().single().components)
        store.commitCheckout(
            CheckoutWriteRequest(
                productCart = emptyMap(),
                bundleCart = mapOf("b" to 1),
                subtotal = 15L,
                discount = 0L,
                total = 15L,
                dateKey = "2099-01-01",
                paymentMethod = PaymentMethod.CASH,
                checkoutLines = listOf(SaleCheckoutLine.Bundle("b", 1, 15L, 15L)),
                stockDeductions = mapOf("p" to 3L),
                tipAmount = 0L,
            ),
        )
        store.applyCatalog(CatalogPersistPlan(bundles = listOf(bundle.copy(name = "新套組名"))))
        val line = store.salesLogFlow.first().single().checkoutLines.single() as SaleCheckoutLine.Bundle
        assertEquals("舊套組名", line.displayName)
    }

    @Test
    fun legacyDataStore_importsOnce_thenMarkerPreventsReread() = runBlocking {
        val legacy = PosStore(appCtx)
        val original = legacy.exportFullBackupJson()
        val importDatabase = Room.inMemoryDatabaseBuilder<StallPosDatabase>(appCtx)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            legacy.restoreFullBackupJson(backup(emptyList(), emptyList(), 0)).getOrThrow()
            val product = Product("legacy", "Legacy 名稱", 80L, stock = 4L)
            legacy.applyCatalog(CatalogPersistPlan(products = listOf(product)))
            legacy.saveCart(PosCart(mapOf(product.id to 1)))
            legacy.commitCheckout(checkout(product.id, 1, 80L))
            val expected = legacy.snapshot.first()

            val imported = RoomPosPersistence(appCtx, importDatabase).snapshot.first()
            assertEquals(expected.products, imported.products)
            assertEquals(expected.salesLog, imported.salesLog)
            assertEquals(expected.lastCheckout, imported.lastCheckout)
            assertEquals(80L, imported.totalSales)

            legacy.applyCatalog(CatalogPersistPlan(products = listOf(product.copy(name = "不得重讀"))))
            val reopened = RoomPosPersistence(appCtx, importDatabase).snapshot.first()
            assertEquals("Legacy 名稱", reopened.products.single().name)
            assertEquals(imported.salesLog.single().id, reopened.salesLog.single().id)
        } finally {
            importDatabase.close()
            legacy.restoreFullBackupJson(original).getOrThrow()
        }
    }

    @Test
    fun backupRestore_abort_keepsOriginalDatabaseIntact() = runBlocking {
        store.applyCatalog(CatalogPersistPlan(products = listOf(Product("before", "Before", 10L, stock = 2L))))
        store.commitCheckout(checkout("before", 1, 10L))
        val before = store.snapshot.first()
        val replacementSale = SaleRecord(
            id = "replacement",
            tsMillis = 1L,
            dateKey = "2099-01-01",
            subtotal = 99L,
            discount = 0L,
            total = 99L,
            cartSnapshot = emptyMap(),
        )
        database.useWriterConnection {
            it.usePrepared(
                "CREATE TRIGGER fail_restore BEFORE INSERT ON sales " +
                    "BEGIN SELECT RAISE(ABORT, 'forced restore failure'); END",
            ) { statement -> statement.step() }
        }
        try {
            assertTrue(runCatching {
                store.restoreFullBackupJson(backup(listOf(replacementSale), emptyList(), 1)).getOrThrow()
            }.isFailure)
        } finally {
            database.useWriterConnection {
                it.usePrepared("DROP TRIGGER fail_restore") { statement -> statement.step() }
            }
        }
        assertEquals(before, store.snapshot.first())
    }

    @Test
    fun backupRestore_missingBusinessKey_keepsOriginalDatabaseIntact() = runBlocking {
        store.applyCatalog(CatalogPersistPlan(products = listOf(Product("before", "Before", 10L, stock = 2L))))
        val before = store.snapshot.first()
        val corrupted = JSONObject(backup(emptyList(), emptyList(), 0)).apply {
            getJSONObject("payload").remove("sales_log_json")
        }.toString()

        assertTrue(store.restoreFullBackupJson(corrupted).isFailure)
        assertEquals(before, store.snapshot.first())
    }

    @Test
    fun backupRestore_duplicateCatalogIds_keepsOriginalDatabaseIntact() = runBlocking {
        store.applyCatalog(CatalogPersistPlan(products = listOf(Product("before", "Before", 10L, stock = 2L))))
        val before = store.snapshot.first()
        val duplicateProducts = encodeProducts(
            listOf(
                Product("duplicate", "First", 10L),
                Product("duplicate", "Second", 20L),
            ),
        )
        val corrupted = JSONObject(backup(emptyList(), emptyList(), 0)).apply {
            getJSONObject("payload").put("products_json", duplicateProducts)
        }.toString()

        assertTrue(store.restoreFullBackupJson(corrupted).isFailure)
        assertEquals(before, store.snapshot.first())
    }

    @Test
    fun largeHistory_observesCorePaths_withoutHardTimingGate() = runBlocking {
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
            val backup = backup(sales, reversals, activeCount)

            val restoreMs = measureTimeMillis { store.restoreFullBackupJson(backup).getOrThrow() }
            lateinit var initial: PosPersistSnapshot
            val startupMs = measureTimeMillis { initial = store.snapshot.first() }
            val dashboardMs = measureTimeMillis {
                assertEquals(activeCount.toLong(), initial.totalSales)
                assertEquals(activeCount.toLong(), initial.txCount)
            }
            lateinit var checkedOut: PosPersistSnapshot
            val checkoutMs = measureTimeMillis {
                store.commitCheckout(emptyCheckout())
                checkedOut = store.snapshot.first()
            }
            lateinit var afterUndo: PosPersistSnapshot
            val undoMs = measureTimeMillis {
                store.undoLastCheckout()
                afterUndo = store.snapshot.first()
            }
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

            assertEquals(size + 1, checkedOut.salesLog.size)
            assertEquals(size + 1, afterUndo.salesLog.size)
            assertEquals(reversals.size + 1, afterUndo.reversalLog.size)
            assertEquals(activeCount, activeSales(afterUndo.salesLog, afterUndo.reversalLog).size)
            assertTrue(csv.contains("報表,累積筆數,$activeCount"))
            Log.i(
                "PosPerf",
                "sales=$size restore=${restoreMs}ms startup=${startupMs}ms checkout=${checkoutMs}ms " +
                    "undo=${undoMs}ms dashboard=${dashboardMs}ms csv=${csvMs}ms",
            )
        }
    }

    private fun checkout(productId: String, quantity: Int, total: Long, tip: Long = 0L) =
        CheckoutWriteRequest(
            productCart = mapOf(productId to quantity),
            bundleCart = emptyMap(),
            subtotal = total,
            discount = 0L,
            total = total,
            dateKey = "2099-01-01",
            paymentMethod = PaymentMethod.CASH,
            checkoutLines = emptyList(),
            stockDeductions = mapOf(productId to quantity.toLong()),
            tipAmount = tip,
        )

    private fun emptyCheckout() = CheckoutWriteRequest(
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
    )

    private fun backup(sales: List<SaleRecord>, reversals: List<SaleReversal>, activeCount: Int): String {
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
        return JSONObject()
            .put("format", PosStore.BACKUP_FORMAT_ID)
            .put("schemaVersion", PosStore.BACKUP_SCHEMA_VERSION)
            .put("exportedAtMillis", System.currentTimeMillis())
            .put("payload", payload)
            .toString()
    }
}
