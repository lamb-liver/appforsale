package com.lambliver.stallpos

import android.util.Log
import androidx.room.Room
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
    private lateinit var database: StallPosV2Database
    private lateinit var store: RoomPosPersistence

    @Before
    fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder<StallPosV2Database>(appCtx).build()
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
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_sale_line BEFORE INSERT ON sale_lines " +
                "BEGIN SELECT RAISE(ABORT, 'forced test failure'); END",
        )
        try {
            assertTrue(runCatching { store.commitCheckout(checkout(productId, 1, 100L)) }.isFailure)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_sale_line")
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
    fun activeEventSaleAndVoid_useOnlyV2LevelsMovementsAndSnapshots() = runBlocking {
        val product = Product("event-product", "徽章", 100L, stock = 10L, cost = 30L)
        store.applyCatalog(CatalogPersistPlan(products = listOf(product)))
        val event = MarketEvent.create(
            name = "CWT",
            type = MarketEventType.CONVENTION,
            startAtMillis = 1_000,
            endAtMillis = 2_000,
            timezone = "Asia/Taipei",
        )
        store.saveEvent(event)
        store.moveInventory(
            product.id,
            8,
            InventoryLocation.General,
            InventoryLocation.event(event.id),
            InventoryMovementType.ALLOCATE_TO_EVENT,
        )
        store.changeEventStatus(event.id, MarketEventStatus.ACTIVE)
        store.commitCheckout(
            CheckoutWriteRequest(
                productCart = mapOf(product.id to 3),
                bundleCart = emptyMap(),
                subtotal = 250,
                discount = 0,
                total = 250,
                dateKey = "2099-01-01",
                paymentMethod = PaymentMethod.CASH,
                checkoutLines = listOf(SaleCheckoutLine.Product(product.id, 3, 100, 300, product.name)),
                stockDeductions = mapOf(product.id to 3),
                tipAmount = 20,
            ),
        )

        val sold = store.snapshot.first()
        assertEquals(5L, sold.products.single().stock)
        assertEquals(30L, sold.products.single().cost)
        assertEquals(270L, sold.totalSales)
        assertEquals(null, database.posDao().products().single().stock)
        val levels = sold.inventoryLevels.associateBy { it.location.key }
        assertEquals(2L, levels.getValue(InventoryLocation.GENERAL_KEY).quantity)
        assertEquals(5L, levels.getValue(InventoryLocation.event(event.id).key).quantity)
        val sale = sold.salesLog.single()
        val meta = database.v2Dao().saleMeta(sale.id)!!
        assertEquals(event.id, meta.eventId)
        assertEquals(50L, meta.discountAmount)
        assertEquals(0L, meta.netAdjustment)
        assertTrue(
            "receipt ${meta.receiptNumber}",
            meta.receiptNumber.matches(Regex("^${Regex.escape(event.code)}-[A-Z0-9]{5,8}-\\d{4}$")),
        )
        val snapshot = database.v2Dao().saleLineSnapshots(sale.id).single()
        assertEquals(30L, snapshot.unitCostSnapshot)
        assertEquals(300L, snapshot.originalAmount)
        assertEquals(50L, snapshot.allocatedDiscount)
        assertEquals(250L, snapshot.finalAmount)
        assertEquals(
            listOf("ADJUSTMENT", "ALLOCATE_TO_EVENT", "SALE"),
            database.v2Dao().inventoryMovements().map { it.movementType },
        )
        assertTrue(runCatching { store.saveEvent(event.copy(code = "EDIT-01")) }.isFailure)
        assertTrue(runCatching { store.saveEvent(event.copy(timezone = "UTC")) }.isFailure)

        store.undoLastCheckout()
        store.undoLastCheckout()
        val voided = store.snapshot.first()
        assertEquals(8L, voided.products.single().stock)
        assertEquals(0L, voided.totalSales)
        assertEquals(1, voided.reversalLog.size)
        assertEquals(1, database.v2Dao().inventoryMovements().count { it.movementType == "VOID" })
        assertEquals(null, database.posDao().products().single().stock)
    }

    @Test
    fun bundleSale_allocatesRevenueByLargestRemainder_andOnlyOneEventCanBeActive() = runBlocking {
        val first = Product("p1", "A", 60, stock = 5, cost = 20)
        val second = Product("p2", "B", 40, stock = 5, cost = null)
        val bundle = Bundle("bundle", "AB", 99, components = listOf(BundleComponent("p1", 1), BundleComponent("p2", 1)))
        store.applyCatalog(CatalogPersistPlan(products = listOf(first, second), bundles = listOf(bundle)))
        val eventOne = MarketEvent.create("One", MarketEventType.MARKET, 1, 2, "Asia/Taipei")
        val eventTwo = MarketEvent.create("Two", MarketEventType.MARKET, 3, 4, "Asia/Taipei")
        store.saveEvent(eventOne)
        store.saveEvent(eventTwo)
        store.changeEventStatus(eventOne.id, MarketEventStatus.ACTIVE)
        assertTrue(runCatching { store.changeEventStatus(eventTwo.id, MarketEventStatus.ACTIVE) }.isFailure)
        store.moveInventory("p1", 2, InventoryLocation.General, InventoryLocation.event(eventOne.id), InventoryMovementType.ALLOCATE_TO_EVENT)
        store.moveInventory("p2", 2, InventoryLocation.General, InventoryLocation.event(eventOne.id), InventoryMovementType.ALLOCATE_TO_EVENT)
        store.commitCheckout(
            CheckoutWriteRequest(
                productCart = emptyMap(),
                bundleCart = mapOf(bundle.id to 1),
                subtotal = 99,
                discount = 0,
                total = 99,
                dateKey = "2099-01-01",
                paymentMethod = PaymentMethod.CASH,
                checkoutLines = listOf(SaleCheckoutLine.Bundle(bundle.id, 1, 99, 99, bundle.name)),
                stockDeductions = mapOf("p1" to 1, "p2" to 1),
                tipAmount = 0,
            ),
        )
        val saleId = store.salesLogFlow.first().single().id
        val allocations = database.v2Dao().bundleAllocations(saleId)
        assertEquals(listOf(59L, 40L), allocations.map { it.allocatedRevenue })
        assertEquals(listOf(20L, null), allocations.map { it.unitCostSnapshot })
        assertEquals(99L, allocations.sumOf { it.allocatedRevenue })
    }

    @Test
    fun closeEvent_returnsAllRemainingInventoryAtomically() = runBlocking {
        val products = listOf(
            Product("close-a", "A", 10, stock = 8),
            Product("close-b", "B", 10, stock = 5),
        )
        store.applyCatalog(CatalogPersistPlan(products = products))
        val event = MarketEvent.create("Close", MarketEventType.MARKET, 1, 2, "Asia/Taipei")
        store.saveEvent(event)
        store.moveInventory("close-a", 6, InventoryLocation.General, InventoryLocation.event(event.id), InventoryMovementType.ALLOCATE_TO_EVENT)
        store.moveInventory("close-b", 4, InventoryLocation.General, InventoryLocation.event(event.id), InventoryMovementType.ALLOCATE_TO_EVENT)
        store.changeEventStatus(event.id, MarketEventStatus.ACTIVE)

        store.closeEventAndReturnInventory(event.id)

        val closed = store.snapshot.first()
        assertEquals(MarketEventStatus.CLOSED, closed.events.single().status)
        assertEquals(listOf(8L, 5L), closed.products.map { it.stock })
        assertEquals(0L, closed.inventoryLevels.first { it.productId == "close-a" && it.location.eventId == event.id }.quantity)
        assertEquals(0L, closed.inventoryLevels.first { it.productId == "close-b" && it.location.eventId == event.id }.quantity)
        assertEquals(2, closed.inventoryMovements.count { it.type == InventoryMovementType.RETURN_FROM_EVENT })
    }

    @Test
    fun v2JsonBackup_roundTripsEventsLevelsMovementsCostsSnapshotsAndVoid() = runBlocking {
        val product = Product("backup-v2", "Backup", 80, stock = 6, cost = 25)
        store.applyCatalog(CatalogPersistPlan(products = listOf(product)))
        val event = MarketEvent.create("Backup Event", MarketEventType.POPUP, 1, 2, "Asia/Taipei")
        store.saveEvent(event)
        store.moveInventory(
            product.id,
            4,
            InventoryLocation.General,
            InventoryLocation.event(event.id),
            InventoryMovementType.ALLOCATE_TO_EVENT,
        )
        store.changeEventStatus(event.id, MarketEventStatus.ACTIVE)
        store.commitCheckout(checkout(product.id, 1, 80))
        store.undoLastCheckout()
        val before = store.snapshot.first()

        val backup = store.exportFullBackupJson()
        assertEquals(PosStore.BACKUP_SCHEMA_VERSION, JSONObject(backup).getInt("schemaVersion"))
        store.restoreFullBackupJson(backup).getOrThrow()

        assertEquals(before, store.snapshot.first())
    }

    @Test
    fun legacyDataStore_importsOnce_thenMarkerPreventsReread() = runBlocking {
        val legacy = PosStore(appCtx)
        val original = legacy.exportFullBackupJson()
        val importDatabase = Room.inMemoryDatabaseBuilder<StallPosV2Database>(appCtx).build()
        try {
            legacy.restoreFullBackupJson(backup(emptyList(), emptyList(), 0)).getOrThrow()
            val product = Product("legacy", "Legacy 名稱", 80L, stock = 4L)
            legacy.applyCatalog(CatalogPersistPlan(products = listOf(product)))
            legacy.saveCart(PosCart(mapOf(product.id to 1)))
            legacy.commitCheckout(checkout(product.id, 1, 80L))
            val expected = legacy.snapshot.first()

            val imported = RoomPosPersistence(appCtx, importDatabase).snapshot.first()
            assertEquals(expected.products, imported.products)
            assertEquals(
                expected.salesLog,
                imported.salesLog.map { it.copy(receiptNumber = null, lineFinancialSnapshots = emptyList()) },
            )
            assertEquals("LEGACY-A-0001", imported.salesLog.single().receiptNumber)
            assertEquals(80L, imported.salesLog.single().lineFinancialSnapshots.single().finalAmount)
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
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_restore BEFORE INSERT ON sales " +
                "BEGIN SELECT RAISE(ABORT, 'forced restore failure'); END",
        )
        try {
            assertTrue(runCatching {
                store.restoreFullBackupJson(backup(listOf(replacementSale), emptyList(), 1)).getOrThrow()
            }.isFailure)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restore")
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
            .put("events_json", "[]")
            .put("inventory_levels_json", "[]")
            .put("inventory_movements_json", "[]")
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
