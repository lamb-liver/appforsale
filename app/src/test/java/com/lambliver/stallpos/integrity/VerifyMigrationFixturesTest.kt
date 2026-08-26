package com.lambliver.stallpos.integrity

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lambliver.stallpos.data.BackupMigration
import com.lambliver.stallpos.data.IntegritySnapshots
import com.lambliver.stallpos.data.LAST_CHECKOUT_JSON_KEY
import com.lambliver.stallpos.data.LEGACY_PRODUCTS_JSON_KEY
import com.lambliver.stallpos.data.PosStore
import com.lambliver.stallpos.data.ProductEntity
import com.lambliver.stallpos.data.ReversalEntity
import com.lambliver.stallpos.data.ROOM_ITEM_PRODUCT
import com.lambliver.stallpos.data.SALES_LOG_JSON_KEY
import com.lambliver.stallpos.data.SALES_REVERSAL_LOG_JSON_KEY
import com.lambliver.stallpos.data.SaleEntity
import com.lambliver.stallpos.data.SaleLineEntity
import com.lambliver.stallpos.data.SaleStockDeductionEntity
import com.lambliver.stallpos.data.StallPosDatabase
import com.lambliver.stallpos.data.StallPosV2Database
import com.lambliver.stallpos.data.V2Migration
import com.lambliver.stallpos.data.migrateLegacyTransactionJson
import com.lambliver.stallpos.data.parseValidatedBackupPayload
import com.lambliver.stallpos.domain.IntegrityAudit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VerifyMigrationFixturesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseNames = mutableListOf<String>()

    @After
    fun cleanup() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun backupSchema1Through4_migrateThenAudit() {
        val sale =
            """{"ts":10,"date":"2026-05-13","subtotal":100,"discount":0,"total":100,"cart":{"p1":1},"bundles":{},"paymentMethod":"CASH","tipAmount":20,"lines":[],"stockDeductions":{"p1":1}}"""
        val last =
            """{"ts":10,"total":120,"cart":{"p1":1},"bundles":{},"stockDeductions":{"p1":1}}"""
        val payload = JSONObject()
            .put("products_json", """[{"id":"p1","name":"徽章","price":100,"categoryId":"","stock":3}]""")
            .put("cart_json", "{}")
            .put("sales_log_json", "[$sale]")
            .put("last_checkout_json", last)
        listOf(1, 2, 3, 4).forEach { schema ->
            val source = JSONObject(payload.toString()).apply {
                if (schema >= 2) put("payloadSchema", schema)
                if (schema >= 3) put("reversal_log_json", "[]")
            }
            val envelope = JSONObject()
                .put("format", PosStore.BACKUP_FORMAT_ID)
                .put("schemaVersion", schema)
                .put("payload", source)
                .toString()
            val migrated = parseValidatedBackupPayload(envelope)
            val report = IntegrityAudit.audit(IntegritySnapshots.fromBackupPayload(migrated))
            assertTrue("schema $schema: ${report.userMessage()}", report.ok)
        }
    }

    @Test
    fun dataStoreV12V13V14_migrateThenAudit() {
        val v12Sale =
            """{"ts":10,"date":"2026-08-22","subtotal":100,"discount":0,"total":100,"cart":{"p1":2},"bundles":{},"paymentMethod":"CASH","tipAmount":20,"lines":[],"stockDeductions":{"p1":2}}"""
        val v12Last =
            """{"ts":10,"total":120,"cart":{"p1":2},"bundles":{},"stockDeductions":{"p1":2}}"""
        val products = """[{"id":"p1","name":"A","price":50,"categoryId":"","stock":3}]"""

        auditDataStore(
            mutablePreferencesOf(
                LEGACY_PRODUCTS_JSON_KEY to products,
                SALES_LOG_JSON_KEY to "[$v12Sale]",
                LAST_CHECKOUT_JSON_KEY to v12Last,
            ),
        )
        auditDataStore(
            mutablePreferencesOf(
                LEGACY_PRODUCTS_JSON_KEY to products,
                SALES_LOG_JSON_KEY to "[$v12Sale]",
                SALES_REVERSAL_LOG_JSON_KEY to "[]",
                LAST_CHECKOUT_JSON_KEY to v12Last,
            ),
        )
        val v3 = migrateLegacyTransactionJson("[$v12Sale]", v12Last).getOrThrow()
        auditPayload(
            JSONObject()
                .put("products_json", products)
                .put("sales_log_json", v3.salesJson)
                .put("last_checkout_json", v3.lastCheckoutJson)
                .put("reversal_log_json", "[]"),
            fromSchema = 3,
        )
    }

    @Test
    fun plaintextRoomV1_migratesToV2ThenAudit() = runBlocking {
        val name = "audit-${UUID.randomUUID()}.db".also(databaseNames::add)
        val legacy = Room.databaseBuilder(context, StallPosDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val dao = legacy.posDao()
        dao.upsertProducts(listOf(ProductEntity("p-stock", "徽章", 100, "", 20, 0)))
        dao.insertSale(SaleEntity("sale", 0, 1_700_000_000_000, "2023-11-15", 100, 0, 100, "CASH", 10))
        dao.insertSaleLines(
            listOf(SaleLineEntity("sale", 0, ROOM_ITEM_PRODUCT, "p-stock", 1, 1, 100, 100, "徽章")),
        )
        dao.insertStockDeductions(listOf(SaleStockDeductionEntity("sale", "p-stock", 1)))
        dao.insertReversal(ReversalEntity("void", 0, "sale", 1_700_000_100_000, "UNDO_LAST_CHECKOUT"))
        legacy.close()

        val migrated = Room.databaseBuilder(context, StallPosV2Database::class.java, name)
            .addMigrations(V2Migration.FROM_1_TO_2)
            .allowMainThreadQueries()
            .build()
        migrated.openHelper.writableDatabase
        val report = IntegrityAudit.audit(IntegritySnapshots.fromV2(migrated.posDao(), migrated.v2Dao()))
        migrated.close()
        assertTrue(report.userMessage(), report.ok)
    }

    private fun auditDataStore(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val sales = prefs[SALES_LOG_JSON_KEY].orEmpty()
        val last = prefs[LAST_CHECKOUT_JSON_KEY].orEmpty()
        val migratedTx = migrateLegacyTransactionJson(sales, last).getOrThrow()
        auditPayload(
            JSONObject()
                .put("products_json", prefs[LEGACY_PRODUCTS_JSON_KEY] ?: "[]")
                .put("sales_log_json", migratedTx.salesJson)
                .put("last_checkout_json", migratedTx.lastCheckoutJson)
                .put("reversal_log_json", prefs[SALES_REVERSAL_LOG_JSON_KEY] ?: "[]"),
            fromSchema = 3,
        )
    }

    private fun auditPayload(payload: JSONObject, fromSchema: Int) {
        val migrated = BackupMigration.migratePayloadToCurrent(fromSchema, payload)
        val report = IntegrityAudit.audit(IntegritySnapshots.fromBackupPayload(migrated))
        assertTrue(report.userMessage(), report.ok)
    }
}
