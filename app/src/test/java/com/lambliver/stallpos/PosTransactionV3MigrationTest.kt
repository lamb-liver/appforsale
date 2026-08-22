package com.lambliver.stallpos

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lambliver.stallpos.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PosTransactionV3MigrationTest {

    private val legacySales =
        """[{"ts":10,"date":"d","subtotal":1,"discount":0,"total":1,"cart":{},"bundles":{},"paymentMethod":"CASH","tipAmount":0,"lines":[],"stockDeductions":{}}]"""

    private val linkedLegacySale =
        """{"ts":10,"date":"2026-08-22","subtotal":100,"discount":0,"total":100,"cart":{"p1":2},"bundles":{},"paymentMethod":"CASH","tipAmount":20,"lines":[],"stockDeductions":{"p1":2}}"""

    private val linkedLegacyLast =
        """{"ts":10,"total":120,"cart":{"p1":2},"bundles":{},"stockDeductions":{"p1":2}}"""

    @Test
    fun migration_runsOnceAndPersistsGeneratedId() = runTest {
        val current = mutablePreferencesOf(SALES_LOG_JSON_KEY to legacySales)
        assertTrue(PosTransactionV3Migration.shouldMigrate(current))

        val migrated = PosTransactionV3Migration.migrate(current)
        val saleId = decodeSalesRecordsJson(migrated[SALES_LOG_JSON_KEY].orEmpty()).single().id

        assertTrue(saleId.isNotBlank())
        assertEquals("[]", migrated[SALES_REVERSAL_LOG_JSON_KEY])
        assertEquals(TRANSACTION_SCHEMA_VERSION, migrated[TRANSACTION_SCHEMA_VERSION_KEY])
        assertFalse(PosTransactionV3Migration.shouldMigrate(migrated))
    }

    @Test
    fun malformedSales_keepsOriginalAndClearsUnsafeLastCheckout() = runTest {
        val current = mutablePreferencesOf(
            SALES_LOG_JSON_KEY to "not-json",
            LAST_CHECKOUT_JSON_KEY to "{\"ts\":1}",
        )
        val migrated = PosTransactionV3Migration.migrate(current)
        assertEquals("not-json", migrated[SALES_LOG_JSON_KEY])
        assertEquals("", migrated[LAST_CHECKOUT_JSON_KEY])
    }

    @Test
    fun v12State_firstUpgrade_preservesCatalogCachesAndStableSaleIdentity() = runTest {
        val productsKey = stringPreferencesKey("products_json")
        val totalSalesKey = longPreferencesKey("total_sales")
        val txCountKey = longPreferencesKey("tx_count")
        val productsJson = """[{"id":"p1","name":"A","price":50,"categoryId":"","stock":3}]"""
        val current = mutablePreferencesOf(
            productsKey to productsJson,
            totalSalesKey to 120L,
            txCountKey to 1L,
            SALES_LOG_JSON_KEY to "[$linkedLegacySale]",
            LAST_CHECKOUT_JSON_KEY to linkedLegacyLast,
        )

        val first = PosTransactionV3Migration.migrate(current)
        val second = PosTransactionV3Migration.migrate(first)
        val firstSale = decodeSalesRecordsJson(first[SALES_LOG_JSON_KEY].orEmpty()).single()
        val secondSale = decodeSalesRecordsJson(second[SALES_LOG_JSON_KEY].orEmpty()).single()

        assertEquals(productsJson, first[productsKey])
        assertEquals(120L, first[totalSalesKey])
        assertEquals(1L, first[txCountKey])
        assertEquals(firstSale.id, decodeLastCheckoutJson(first[LAST_CHECKOUT_JSON_KEY].orEmpty())!!.saleId)
        assertEquals(firstSale.id, secondSale.id)
        assertFalse(PosTransactionV3Migration.shouldMigrate(first))
    }

    @Test
    fun ambiguousLegacyLastCheckout_keepsSalesWithDistinctIdsAndClearsUndo() = runTest {
        val migrated = PosTransactionV3Migration.migrate(
            mutablePreferencesOf(
                SALES_LOG_JSON_KEY to "[$linkedLegacySale,$linkedLegacySale]",
                LAST_CHECKOUT_JSON_KEY to linkedLegacyLast,
            ),
        )
        val sales = decodeSalesRecordsJson(migrated[SALES_LOG_JSON_KEY].orEmpty())

        assertEquals(2, sales.size)
        assertEquals(2, sales.map { it.id }.distinct().size)
        assertEquals("", migrated[LAST_CHECKOUT_JSON_KEY])
    }
}
