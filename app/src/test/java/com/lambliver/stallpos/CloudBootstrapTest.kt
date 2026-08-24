package com.lambliver.stallpos

import com.lambliver.stallpos.data.decodeCloudBootstrap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudBootstrapTest {
    @Test
    fun `decodes cloud wire payloads into an atomic local snapshot`() {
        val master = fixture("master-batch.json")
        val sale = fixture("sale-batch.json").getJSONArray("operations").getJSONObject(0).getJSONObject("payload")
        val void = fixture("void-batch.json").getJSONArray("operations").getJSONObject(0).getJSONObject("payload")
        val operations = master.getJSONArray("operations")
        val payloads = List(operations.length()) { operations.getJSONObject(it) }
        val productPayloads = payloads.filter { it.getString("entityType") in setOf("CATEGORY", "PRODUCT") }
            .map { it.getJSONObject("payload") }
        val bundlePayloads = payloads.filter { it.getString("entityType") == "BUNDLE" }.map { it.getJSONObject("payload") }
        val eventPayloads = payloads.filter { it.getString("entityType") == "EVENT" }.map { it.getJSONObject("payload") }
        val initialMovements = payloads.filter { it.getString("entityType") == "INVENTORY_MOVEMENT" }.map { it.getJSONObject("payload") }
        val allMovements = initialMovements + sale.getJSONArray("inventoryMovements").objects() + void.getJSONArray("inventoryMovements").objects()
        val snapshot = decodeCloudBootstrap(
            JSONObject()
                .put("products", JSONArray(productPayloads))
                .put("bundles", JSONArray(bundlePayloads))
                .put("events", JSONArray(eventPayloads))
                .put("inventory", JSONArray(allMovements))
                .put("inventoryLevels", JSONArray().put(JSONObject()
                    .put("productId", "50000000-0000-4000-8000-000000000001")
                    .put("locationType", "EVENT")
                    .put("eventId", "70000000-0000-4000-8000-000000000001")
                    .put("quantity", 10)
                    .put("updatedAtUtc", "2026-08-29T03:20:00Z")))
                .put("transactions", JSONArray().put(sale).put(void)),
        )

        assertEquals(1, snapshot.products.size)
        assertEquals(1, snapshot.salesLog.size)
        assertEquals(1, snapshot.reversalLog.size)
        assertEquals(3, snapshot.inventoryMovements.size)
        assertEquals(10, snapshot.inventoryLevels.single().quantity)
        assertEquals(0, snapshot.totalSales)
        assertEquals(25L, snapshot.products.single().cost)
    }

    private fun fixture(name: String): JSONObject = JSONObject(
        requireNotNull(javaClass.getResource("/v2/fixtures/$name")).readText(),
    )

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
}
