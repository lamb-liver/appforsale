package com.lambliver.stallpos

import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lambliver.stallpos.data.*
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyRetirementInstrumentedTest {
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var database: StallPosV2Database

    @Before
    fun setup() = runBlocking {
        resetLegacyDataStore()
        database = Room.inMemoryDatabaseBuilder<StallPosV2Database>(appCtx).build()
    }

    @After
    fun cleanup() = runBlocking {
        if (::database.isInitialized) database.close()
        resetLegacyDataStore()
    }

    @Test
    fun v12Fixture_imports_retiresLegacy_andRunsEntirelyFromRoom() = runBlocking {
        createLegacyFixture(withReversal = false)
        convertFixtureToV12ThenMigrate()
        assertUpgradeCompletes(PosStore(appCtx).snapshot.first())
    }

    @Test
    fun v13Fixture_imports_retiresLegacy_andRunsEntirelyFromRoom() = runBlocking {
        val expected = createLegacyFixture(withReversal = true)
        assertUpgradeCompletes(expected)
    }

    @Test
    fun v14Fixture_retiresFrozenLegacy_withoutOverwritingNewerRoomData() = runBlocking {
        database.posDao().putMeta(AppMetaEntity(LEGACY_IMPORT_VERSION_KEY, "3"))
        database.posDao().putMeta(AppMetaEntity(LEGACY_CLEANUP_STATE_KEY, LEGACY_CLEANUP_CLEARED))
        val store = RoomPosPersistence(appCtx, database)
        val roomProduct = Product("room-product", "Room 最新資料", 90L, stock = 8L)
        store.applyCatalog(CatalogPersistPlan(products = listOf(roomProduct)))
        store.commitCheckout(checkout(roomProduct.id, 1, 90L))
        store.undoLastCheckout()
        store.commitCheckout(checkout(roomProduct.id, 1, 90L))
        val expected = store.snapshot.first()
        assertEquals(1, expected.reversalLog.size)

        createLegacyFixture(withReversal = true)
        database.posDao().putMeta(AppMetaEntity(LEGACY_CLEANUP_STATE_KEY, "pending"))

        val reopened = RoomPosPersistence(appCtx, database)
        assertEquals(expected, reopened.snapshot.first())
        assertLegacyClearedAndPreferencesKept()
        assertEquals(LEGACY_CLEANUP_CLEARED, database.posDao().metaValue(LEGACY_CLEANUP_STATE_KEY))
    }

    @Test
    fun cleanupCrashAfterDataStoreEdit_restartsAndWritesClearedState() = runBlocking {
        database.posDao().putMeta(AppMetaEntity(LEGACY_IMPORT_VERSION_KEY, "3"))
        database.posDao().putMeta(AppMetaEntity(LEGACY_CLEANUP_STATE_KEY, "pending"))
        appCtx.posPreferencesDataStore.edit { prefs ->
            prefs.removeLegacyBusinessKeys()
            prefs[AppUiPreferences.HAPTIC_ENABLED] = false
        }

        RoomPosPersistence(appCtx, database).snapshot.first()

        assertEquals(LEGACY_CLEANUP_CLEARED, database.posDao().metaValue(LEGACY_CLEANUP_STATE_KEY))
        assertFalse(appCtx.posPreferencesDataStore.data.first().hasLegacyBusinessKeys())
        assertFalse(AppUiPreferences(appCtx).hapticEnabledFlow.first())
    }

    @Test
    fun malformedSales_preservesAllLegacy_andKeepsRoomOperational() = runBlocking {
        assertMalformedLegacyPreserved(SALES_LOG_JSON_KEY.name, "not-json")
    }

    @Test
    fun malformedCatalog_preservesAllLegacy_andKeepsRoomOperational() = runBlocking {
        assertMalformedLegacyPreserved(LEGACY_PRODUCTS_JSON_KEY.name, "not-json")
    }

    @Test
    fun malformedCart_preservesAllLegacy_andKeepsRoomOperational() = runBlocking {
        assertMalformedLegacyPreserved(LEGACY_CART_JSON_KEY.name, "{bad")
    }

    private suspend fun assertUpgradeCompletes(expected: PosPersistSnapshot) {
        val store = RoomPosPersistence(appCtx, database)
        val imported = store.snapshot.first()
        assertEquals(expected.products, imported.products)
        assertEquals(expected.categories, imported.categories)
        assertEquals(expected.bundleCategories, imported.bundleCategories)
        assertEquals(expected.bundles, imported.bundles)
        assertEquals(expected.cart, imported.cart)
        assertEquals(expected.salesLog, imported.salesLog)
        assertEquals(expected.reversalLog, imported.reversalLog)
        assertEquals(expected.lastCheckout, imported.lastCheckout)
        assertEquals(expected.totalSales, imported.totalSales)
        assertEquals(expected.txCount, imported.txCount)
        assertLegacyClearedAndPreferencesKept()

        val reopened = RoomPosPersistence(appCtx, database)
        val beforeCheckout = reopened.snapshot.first()
        reopened.commitCheckout(checkout("p1", 1, 100L))
        reopened.undoLastCheckout()
        val afterUndo = reopened.snapshot.first()
        assertEquals(beforeCheckout.products, afterUndo.products)
        assertEquals(beforeCheckout.totalSales, afterUndo.totalSales)
        assertEquals(beforeCheckout.txCount, afterUndo.txCount)
        assertEquals(beforeCheckout.salesLog.size + 1, afterUndo.salesLog.size)
        assertEquals(beforeCheckout.reversalLog.size + 1, afterUndo.reversalLog.size)
        assertTrue(
            buildPosSalesCsv(
                "2099-01-01",
                afterUndo.salesLog,
                afterUndo.reversalLog,
                afterUndo.products,
                afterUndo.bundles,
            ).contains("報表,累積筆數,${afterUndo.txCount}"),
        )

        val exported = reopened.exportFullBackupJson()
        reopened.restoreFullBackupJson(emptyBackup()).getOrThrow()
        reopened.restoreFullBackupJson(exported).getOrThrow()
        assertEquals(afterUndo, RoomPosPersistence(appCtx, database).snapshot.first())
        assertLegacyClearedAndPreferencesKept()
    }

    private suspend fun assertMalformedLegacyPreserved(keyName: String, malformed: String) {
        database.posDao().putMeta(AppMetaEntity(LEGACY_IMPORT_VERSION_KEY, "3"))
        database.posDao().putMeta(AppMetaEntity(LEGACY_CLEANUP_STATE_KEY, LEGACY_CLEANUP_CLEARED))
        val store = RoomPosPersistence(appCtx, database)
        val product = Product("room", "Room 保留資料", 100L, stock = 5L)
        store.applyCatalog(CatalogPersistPlan(products = listOf(product)))
        store.commitCheckout(checkout(product.id, 1, 100L))
        val before = store.snapshot.first()

        PosStore(appCtx).restoreFullBackupJson(emptyBackup()).getOrThrow()
        appCtx.posPreferencesDataStore.edit { prefs ->
            when (keyName) {
                SALES_LOG_JSON_KEY.name -> prefs[SALES_LOG_JSON_KEY] = malformed
                LEGACY_PRODUCTS_JSON_KEY.name -> prefs[LEGACY_PRODUCTS_JSON_KEY] = malformed
                LEGACY_CART_JSON_KEY.name -> prefs[LEGACY_CART_JSON_KEY] = malformed
            }
        }
        val keysBefore = appCtx.posPreferencesDataStore.data.first().asMap().keys
            .mapTo(hashSetOf()) { it.name }
            .intersect(LEGACY_BUSINESS_KEY_NAMES)
        assertEquals(LEGACY_BUSINESS_KEY_NAMES, keysBefore)
        database.posDao().putMeta(AppMetaEntity(LEGACY_CLEANUP_STATE_KEY, "pending"))

        val reopened = RoomPosPersistence(appCtx, database)
        assertEquals(before, reopened.snapshot.first())
        assertEquals(
            LEGACY_CLEANUP_PRESERVED_INVALID,
            database.posDao().metaValue(LEGACY_CLEANUP_STATE_KEY),
        )
        val keysAfter = appCtx.posPreferencesDataStore.data.first().asMap().keys
            .mapTo(hashSetOf()) { it.name }
            .intersect(LEGACY_BUSINESS_KEY_NAMES)
        assertEquals(LEGACY_BUSINESS_KEY_NAMES, keysAfter)
        assertEquals(before, RoomPosPersistence(appCtx, database).snapshot.first())

        reopened.commitCheckout(checkout(product.id, 1, 100L))
        reopened.undoLastCheckout()
        val operational = reopened.snapshot.first()
        assertTrue(
            buildPosSalesCsv(
                "2099-01-01",
                operational.salesLog,
                operational.reversalLog,
                operational.products,
                operational.bundles,
            ).isNotBlank(),
        )
        assertEquals(4, JSONObject(reopened.exportFullBackupJson()).getInt("schemaVersion"))
    }

    private suspend fun createLegacyFixture(withReversal: Boolean): PosPersistSnapshot {
        val legacy = PosStore(appCtx)
        legacy.restoreFullBackupJson(emptyBackup()).getOrThrow()
        val products = listOf(
            Product("p1", "徽章", 100L, "c1", stock = 10L),
            Product("p2", "貼紙", 40L, "c1", stock = null),
        )
        legacy.applyCatalog(
            CatalogPersistPlan(
                products = products,
                categories = listOf(Category("c1", "商品")),
                bundleCategories = listOf(BundleCategory("bc1", "套組")),
                bundles = listOf(
                    Bundle(
                        "b1",
                        "入門套組",
                        130L,
                        "bc1",
                        listOf(BundleComponent("p1", 1L), BundleComponent("p2", 1L)),
                    ),
                ),
            ),
        )
        if (withReversal) {
            legacy.commitCheckout(checkout("p1", 1, 100L))
            legacy.undoLastCheckout()
        }
        legacy.commitCheckout(checkout("p1", 2, 200L))
        legacy.saveCart(PosCart(products = mapOf("p2" to 1), bundles = mapOf("b1" to 1)))
        AppUiPreferences(appCtx).setHapticEnabled(false)
        AppUiPreferences(appCtx).setSoundEnabled(false)
        return legacy.snapshot.first()
    }

    private suspend fun convertFixtureToV12ThenMigrate() {
        appCtx.posPreferencesDataStore.edit { prefs ->
            val sales = JSONArray(prefs[SALES_LOG_JSON_KEY].orEmpty())
            repeat(sales.length()) { sales.getJSONObject(it).remove("id") }
            val last = JSONObject(prefs[LAST_CHECKOUT_JSON_KEY].orEmpty()).apply { remove("saleId") }
            val migrated = migrateLegacyTransactionJson(sales.toString(), last.toString()).getOrThrow()
            prefs[SALES_LOG_JSON_KEY] = migrated.salesJson
            prefs[LAST_CHECKOUT_JSON_KEY] = migrated.lastCheckoutJson
            prefs[SALES_REVERSAL_LOG_JSON_KEY] = "[]"
            prefs[TRANSACTION_SCHEMA_VERSION_KEY] = TRANSACTION_SCHEMA_VERSION
        }
    }

    private suspend fun assertLegacyClearedAndPreferencesKept() {
        val prefs = appCtx.posPreferencesDataStore.data.first()
        assertFalse(prefs.hasLegacyBusinessKeys())
        assertEquals(TRANSACTION_SCHEMA_VERSION, prefs[TRANSACTION_SCHEMA_VERSION_KEY])
        assertFalse(AppUiPreferences(appCtx).hapticEnabledFlow.first())
        assertFalse(AppUiPreferences(appCtx).soundEnabledFlow.first())
    }

    private suspend fun resetLegacyDataStore() {
        appCtx.posPreferencesDataStore.edit { prefs ->
            prefs.removeLegacyBusinessKeys()
            prefs[TRANSACTION_SCHEMA_VERSION_KEY] = TRANSACTION_SCHEMA_VERSION
            prefs.remove(AppUiPreferences.HAPTIC_ENABLED)
            prefs.remove(AppUiPreferences.SOUND_ENABLED)
        }
    }

    private fun checkout(productId: String, quantity: Int, total: Long) = CheckoutWriteRequest(
        productCart = mapOf(productId to quantity),
        bundleCart = emptyMap(),
        subtotal = total,
        discount = 0L,
        total = total,
        dateKey = "2099-01-01",
        paymentMethod = PaymentMethod.CASH,
        checkoutLines = emptyList(),
        stockDeductions = mapOf(productId to quantity.toLong()),
        tipAmount = 0L,
    )

    private fun emptyBackup(): String {
        val payload = JSONObject()
            .put("payloadSchema", 4)
            .put("products_json", "[]")
            .put("categories_json", "[]")
            .put("bundle_categories_json", "[]")
            .put("bundles_json", "[]")
            .put("cart_json", encodePosCartJson(PosCart()))
            .put("sales_log_json", "[]")
            .put("reversal_log_json", "[]")
            .put("last_checkout_json", "")
            .put("total_sales", 0L)
            .put("tx_count", 0L)
        return JSONObject()
            .put("format", PosStore.BACKUP_FORMAT_ID)
            .put("schemaVersion", 4)
            .put("exportedAtMillis", 1L)
            .put("payload", payload)
            .toString()
    }
}
