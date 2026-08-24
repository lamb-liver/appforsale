package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal fun encodeMarketEvents(rows: List<MarketEvent>): String = JSONArray().apply {
    rows.forEach { row ->
        put(
            JSONObject()
                .put("id", row.id)
                .put("name", row.name)
                .put("code", row.code)
                .put("type", row.type.name)
                .put("startAtMillis", row.startAtMillis)
                .put("endAtMillis", row.endAtMillis)
                .put("timezone", row.timezone)
                .put("location", row.location)
                .put("status", row.status.name)
                .put("updatedAtMillis", row.updatedAtMillis)
                .apply {
                    row.actualOpenAtMillis?.let { put("actualOpenAtMillis", it) }
                    row.actualCloseAtMillis?.let { put("actualCloseAtMillis", it) }
                    row.deletedAtMillis?.let { put("deletedAtMillis", it) }
                },
        )
    }
}.toString()

internal fun decodeMarketEvents(json: String): List<MarketEvent> = runCatching {
    val rows = JSONArray(json)
    List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        MarketEvent(
            id = row.getString("id"),
            name = row.getString("name"),
            code = row.getString("code"),
            type = MarketEventType.valueOf(row.getString("type")),
            startAtMillis = row.getLong("startAtMillis"),
            endAtMillis = row.getLong("endAtMillis"),
            actualOpenAtMillis = row.optNullableLong("actualOpenAtMillis"),
            actualCloseAtMillis = row.optNullableLong("actualCloseAtMillis"),
            timezone = row.getString("timezone"),
            location = row.optString("location", ""),
            status = MarketEventStatus.valueOf(row.getString("status")),
            updatedAtMillis = row.getLong("updatedAtMillis"),
            deletedAtMillis = row.optNullableLong("deletedAtMillis"),
        )
    }
}.getOrElse { emptyList() }

internal fun encodeInventoryLevels(rows: List<InventoryLevel>): String = JSONArray().apply {
    rows.forEach { row ->
        put(
            JSONObject()
                .put("productId", row.productId)
                .put("locationType", row.location.type.name)
                .put("quantity", row.quantity)
                .put("updatedAtMillis", row.updatedAtMillis)
                .apply { row.location.eventId?.let { put("eventId", it) } },
        )
    }
}.toString()

internal fun decodeInventoryLevels(json: String): List<InventoryLevel> = runCatching {
    val rows = JSONArray(json)
    List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        InventoryLevel(
            productId = row.getString("productId"),
            location = InventoryLocation(
                InventoryLocationType.valueOf(row.getString("locationType")),
                row.optString("eventId", "").takeIf { it.isNotBlank() },
            ),
            quantity = row.getLong("quantity"),
            updatedAtMillis = row.getLong("updatedAtMillis"),
        )
    }
}.getOrElse { emptyList() }

internal fun encodeInventoryMovements(rows: List<InventoryMovement>): String = JSONArray().apply {
    rows.forEach { row ->
        put(
            JSONObject()
                .put("id", row.id)
                .put("productId", row.productId)
                .put("type", row.type.name)
                .put("quantity", row.quantity)
                .put("occurredAtMillis", row.occurredAtMillis)
                .apply {
                    row.eventId?.let { put("eventId", it) }
                    row.from?.let { put("from", encodeLocation(it)) }
                    row.to?.let { put("to", encodeLocation(it)) }
                    row.relatedTransactionId?.let { put("relatedTransactionId", it) }
                },
        )
    }
}.toString()

internal fun decodeInventoryMovements(json: String): List<InventoryMovement> = runCatching {
    val rows = JSONArray(json)
    List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        InventoryMovement(
            id = row.getString("id"),
            productId = row.getString("productId"),
            eventId = row.optString("eventId", "").takeIf { it.isNotBlank() },
            from = row.optJSONObject("from")?.decodeLocation(),
            to = row.optJSONObject("to")?.decodeLocation(),
            type = InventoryMovementType.valueOf(row.getString("type")),
            quantity = row.getLong("quantity"),
            relatedTransactionId = row.optString("relatedTransactionId", "").takeIf { it.isNotBlank() },
            occurredAtMillis = row.getLong("occurredAtMillis"),
        )
    }
}.getOrElse { emptyList() }

private fun encodeLocation(location: InventoryLocation) = JSONObject()
    .put("type", location.type.name)
    .apply { location.eventId?.let { put("eventId", it) } }

private fun JSONObject.decodeLocation() = InventoryLocation(
    InventoryLocationType.valueOf(getString("type")),
    optString("eventId", "").takeIf { it.isNotBlank() },
)

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null
