package com.lambliver.stallpos

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lambliver.stallpos.data.AppMetaEntity
import com.lambliver.stallpos.data.CatalogPersistPlan
import com.lambliver.stallpos.data.LEGACY_IMPORT_VERSION_KEY
import com.lambliver.stallpos.data.RoomPosPersistence
import com.lambliver.stallpos.data.StallPosV2Database
import com.lambliver.stallpos.data.SyncEngine
import com.lambliver.stallpos.data.SyncHttpResponse
import com.lambliver.stallpos.data.SyncRunResult
import com.lambliver.stallpos.data.SyncTransport
import com.lambliver.stallpos.data.configureSyncSession
import com.lambliver.stallpos.domain.CheckoutWriteRequest
import com.lambliver.stallpos.domain.InventoryLocation
import com.lambliver.stallpos.domain.InventoryMovementType
import com.lambliver.stallpos.domain.MarketEvent
import com.lambliver.stallpos.domain.MarketEventStatus
import com.lambliver.stallpos.domain.MarketEventType
import com.lambliver.stallpos.domain.PaymentMethod
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.domain.SaleCheckoutLine
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncOutboxInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var database: StallPosV2Database
    private lateinit var store: RoomPosPersistence

    @Before
    fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder<StallPosV2Database>(context).build()
        database.posDao().putMeta(AppMetaEntity(LEGACY_IMPORT_VERSION_KEY, "3"))
        store = RoomPosPersistence(context, database)
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun offlineSaleVoid_retryKeepsOperationIdsAndFixedOrder_thenAckSyncs() = runBlocking {
        val categoryId = UUID.randomUUID().toString()
        val productId = UUID.randomUUID().toString()
        val event = MarketEvent.create("M5", MarketEventType.MARKET, 1, 2, "Asia/Taipei")
        store.applyCatalog(
            CatalogPersistPlan(
                categories = listOf(com.lambliver.stallpos.domain.Category(categoryId, "分類")),
                products = listOf(Product(productId, "商品", 100, categoryId, stock = 5, cost = 40)),
            ),
        )
        store.saveEvent(event)
        store.moveInventory(productId, 5, InventoryLocation.General, InventoryLocation.event(event.id), InventoryMovementType.ALLOCATE_TO_EVENT)
        store.changeEventStatus(event.id, MarketEventStatus.ACTIVE)
        store.commitCheckout(checkout(productId))
        store.undoLastCheckout()

        val pending = database.v2Dao().pendingOutbox(Long.MAX_VALUE, 50)
        val ranks = pending.map { row ->
            when (row.entityType) {
                "CATEGORY" -> 0
                "PRODUCT" -> 1
                "BUNDLE" -> 2
                "EVENT" -> 3
                "INVENTORY_MOVEMENT" -> 4
                else -> 5
            }
        }
        assertEquals(ranks.sorted(), ranks)
        val sale = pending.single { it.entityType == "SALE" }
        val void = pending.single { it.entityType == "VOID" }
        assertEquals(JSONObject(sale.payloadJson).getString("id"), JSONObject(void.payloadJson).getString("saleId"))
        val voidMovement = JSONObject(void.payloadJson).getJSONArray("inventoryMovements").getJSONObject(0)
        assertEquals(JSONObject(sale.payloadJson).getString("id"), voidMovement.getString("relatedTransactionId"))

        configureSyncSession(database, "https://stallpos.test", "test-token", UUID.randomUUID().toString(), 1)
        var clock = 1_000_000L
        val requests = mutableListOf<JSONObject>()
        var attempt = 0
        val transport = SyncTransport { _, _, body ->
            requests += JSONObject(body)
            if (attempt++ == 0) throw IOException("offline")
            val request = requests.last()
            val results = JSONArray()
            val operations = request.getJSONArray("operations")
            repeat(operations.length()) { index ->
                results.put(
                    JSONObject()
                        .put("operationId", operations.getJSONObject(index).getString("operationId"))
                        .put("status", "ACK")
                        .put("code", JSONObject.NULL)
                        .put("message", JSONObject.NULL),
                )
            }
            SyncHttpResponse(200, JSONObject().put("requestId", request.getString("requestId")).put("results", results).toString())
        }
        val engine = SyncEngine(database, transport) { clock }
        assertEquals(SyncRunResult.RETRY, engine.runOnce())
        clock += 31_000
        assertEquals(SyncRunResult.SUCCESS, engine.runOnce())
        assertEquals(operationIds(requests[0]), operationIds(requests[1]))
        assertTrue(database.v2Dao().outboxRows().all { it.status == "SYNCED" })
    }

    @Test
    fun outboxInsertFailure_rollsBackSaleAndInventory() = runBlocking {
        val categoryId = UUID.randomUUID().toString()
        val productId = UUID.randomUUID().toString()
        store.applyCatalog(
            CatalogPersistPlan(
                categories = listOf(com.lambliver.stallpos.domain.Category(categoryId, "分類")),
                products = listOf(Product(productId, "商品", 100, categoryId, stock = 2)),
            ),
        )
        val beforeOutbox = database.v2Dao().outboxRows().size
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_outbox BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'forced outbox failure'); END",
        )
        try {
            assertTrue(runCatching { store.commitCheckout(checkout(productId)) }.isFailure)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_outbox")
        }
        assertEquals(2L, store.productsFlow.first().single().stock)
        assertEquals(0, database.posDao().sales().size)
        assertEquals(beforeOutbox, database.v2Dao().outboxRows().size)
    }

    @Test
    fun customAmountOnlySale_serializesWithoutFakeItemLine() = runBlocking {
        store.commitCheckout(
            CheckoutWriteRequest(
                productCart = emptyMap(),
                bundleCart = emptyMap(),
                subtotal = 50,
                discount = 0,
                total = 50,
                dateKey = "2026-08-24",
                paymentMethod = PaymentMethod.CASH,
                checkoutLines = emptyList(),
                stockDeductions = emptyMap(),
                tipAmount = 0,
            ),
        )
        val payload = JSONObject(database.v2Dao().outboxRows().single { it.entityType == "SALE" }.payloadJson)
        assertEquals(0, payload.getInt("subtotal"))
        assertEquals(50, payload.getInt("netAdjustment"))
        assertEquals(0, payload.getJSONArray("lines").length())
        assertTrue(payload.isNull("eventId"))
    }

    @Test
    fun expiredAccessToken_rotatesOnceBeforeRetryingTheSameBatch() = runBlocking {
        val productId = UUID.randomUUID().toString()
        store.applyCatalog(CatalogPersistPlan(products = listOf(Product(productId, "Refresh", 10))))
        configureSyncSession(database, "https://stallpos.test", "expired", UUID.randomUUID().toString(), 1)
        val tokens = mutableListOf<String>()
        val transport = SyncTransport { _, token, body ->
            tokens += token
            if (token == "expired") SyncHttpResponse(401, "{}") else {
                val request = JSONObject(body)
                val operations = request.getJSONArray("operations")
                val results = JSONArray()
                repeat(operations.length()) { index ->
                    results.put(JSONObject()
                        .put("operationId", operations.getJSONObject(index).getString("operationId"))
                        .put("status", "ACK"))
                }
                SyncHttpResponse(200, JSONObject().put("requestId", request.getString("requestId")).put("results", results).toString())
            }
        }
        val engine = SyncEngine(database, transport, refreshAccessToken = { "rotated" })
        assertEquals(SyncRunResult.SUCCESS, engine.runOnce())
        assertEquals(listOf("expired", "rotated"), tokens)
    }

    private fun checkout(productId: String) = CheckoutWriteRequest(
        productCart = mapOf(productId to 1),
        bundleCart = emptyMap(),
        subtotal = 100,
        discount = 0,
        total = 100,
        dateKey = "2026-08-24",
        paymentMethod = PaymentMethod.CASH,
        checkoutLines = listOf(SaleCheckoutLine.Product(productId, 1, 100, 100, "商品")),
        stockDeductions = mapOf(productId to 1),
        tipAmount = 0,
    )

    private fun operationIds(request: JSONObject): List<String> {
        val rows = request.getJSONArray("operations")
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("operationId") }
    }
}
