package com.lambliver.stallpos

import android.annotation.SuppressLint
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class V2ContractFixtureTest {

    @Test
    fun validSyncFixtures_roundTripWithoutLosingFields() {
        listOf("master-batch.json", "sale-batch.json", "void-batch.json").forEach { name ->
            val original = validateSyncBatch(fixture(name))
            val roundTrip = validateSyncBatch(original.toString())
            assertEquals(original.toString(), roundTrip.toString())
        }
    }

    @Test
    fun duplicateFixture_replaysTheExactSameOperation() {
        val sale = validateSyncBatch(fixture("sale-batch.json"))
        val duplicate = validateSyncBatch(fixture("duplicate-batch.json"))
        assertTrue(sale.getString("requestId") != duplicate.getString("requestId"))
        assertEquals(
            sale.getJSONArray("operations").getJSONObject(0).toString(),
            duplicate.getJSONArray("operations").getJSONObject(0).toString(),
        )
    }

    @Test
    fun invalidFixture_rejectsStringMoneyAndNonUtcTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            validateSyncBatch(fixture("invalid-batch.json"))
        }
    }

    @Test
    fun responseBootstrapAndReportFixtures_haveStableSemantics() {
        val response = JSONObject(fixture("sync-response-blocked.json"))
        val results = response.getJSONArray("results")
        assertEquals("ACK", results.getJSONObject(0).getString("status"))
        assertEquals("DEVICE_RETIRED", results.getJSONObject(1).getString("code"))

        val bootstrap = JSONObject(fixture("bootstrap.json"))
        assertEquals("TRANSACTIONS", bootstrap.getString("group"))
        requireUtc(
            bootstrap.getJSONArray("data").getJSONObject(0).getString("occurredAtUtc"),
        )

        val report = JSONObject(fixture("event-report.json"))
        assertInteger(report, "revenue")
        assertInteger(report, "knownCost")
        assertInteger(report, "grossProfit")
        assertEquals(
            report.getLong("revenue"),
            report.getLong("grossProfit") + report.getLong("knownCost"),
        )
    }

    @Test
    fun deviceAndOutboxFixtures_haveStableStateFields() {
        val device = JSONObject(fixture("device-state.json"))
        assertEquals("ACTIVE", device.getString("status"))
        assertTrue(device.getLong("cloudEpoch") >= 0)
        requireUtc(device.getString("registeredAtUtc"))
        requireUtc(device.getString("lastSeenAtUtc"))

        val outbox = JSONObject(fixture("outbox-pending.json"))
        assertEquals("PENDING", outbox.getString("status"))
        assertEquals(64, outbox.getString("payloadHash").length)
        validateOperation(outbox.getJSONObject("operation"))
        requireUtc(outbox.getString("createdAtUtc"))
        requireUtc(outbox.getString("updatedAtUtc"))
    }

    private fun validateSyncBatch(text: String): JSONObject = JSONObject(text).also { root ->
        require(root.getLong("cloudEpoch") >= 0)
        require(root.getString("requestId").isNotBlank())
        require(root.getString("deviceId").isNotBlank())
        val operations = root.getJSONArray("operations")
        require(operations.length() in 1..50)
        repeat(operations.length()) { index -> validateOperation(operations.getJSONObject(index)) }
    }

    private fun validateOperation(operation: JSONObject) {
        require(operation.getString("operationId").isNotBlank())
        require(operation.getString("entityId").isNotBlank())
        require(operation.getString("operationType") in setOf("UPSERT", "DELETE", "APPEND"))
        val category = operation.getString("category")
        val entityType = operation.getString("entityType")
        require(
            (category == "MASTER" && entityType in MASTER_TYPES) ||
                (category == "INVENTORY" && entityType == "INVENTORY_MOVEMENT") ||
                (category == "TRANSACTION" && entityType in TRANSACTION_TYPES),
        )
        val payload = operation.getJSONObject("payload")
        when (entityType) {
            "PRODUCT" -> {
                assertInteger(payload, "sellingPrice")
                if (!payload.isNull("cost")) assertInteger(payload, "cost")
                requireUtc(payload.getString("updatedAtUtc"))
            }
            "CATEGORY", "BUNDLE", "EVENT" -> requireUtc(payload.getString("updatedAtUtc"))
            "INVENTORY_MOVEMENT" -> validateMovement(payload)
            "SALE" -> {
                listOf("subtotal", "discountAmount", "netAdjustment", "finalTotal", "tipAmount")
                    .forEach { assertInteger(payload, it) }
                requireUtc(payload.getString("occurredAtUtc"))
                validateMovements(payload.getJSONArray("inventoryMovements"))
            }
            "VOID" -> {
                requireUtc(payload.getString("occurredAtUtc"))
                validateMovements(payload.getJSONArray("inventoryMovements"))
            }
        }
    }

    private fun validateMovements(rows: JSONArray) {
        repeat(rows.length()) { validateMovement(rows.getJSONObject(it)) }
    }

    private fun validateMovement(row: JSONObject) {
        assertInteger(row, "quantity")
        require(row.getInt("quantity") in 1..10_000)
        requireUtc(row.getString("occurredAtUtc"))
        require(!(row.isNull("fromLocation") && row.isNull("toLocation")))
    }

    private fun assertInteger(row: JSONObject, key: String) {
        require(row.get(key) is Number && row.get(key) !is Double && row.get(key) !is Float) {
            "$key must be a JSON integer"
        }
    }

    @SuppressLint("NewApi") // JVM contract test; this code is never packaged into the minSdk 24 app.
    private fun requireUtc(value: String) {
        require(value.endsWith("Z")) { "timestamp must be UTC" }
        Instant.parse(value)
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/v2/fixtures/$name")) { "Missing fixture $name" }
            .readText()

    private companion object {
        val MASTER_TYPES = setOf("CATEGORY", "PRODUCT", "BUNDLE", "EVENT")
        val TRANSACTION_TYPES = setOf("SALE", "VOID")
    }
}
