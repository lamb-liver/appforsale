package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.Bundle
import com.lambliver.stallpos.domain.BundleCategory
import com.lambliver.stallpos.domain.Category
import com.lambliver.stallpos.domain.InventoryLocation
import com.lambliver.stallpos.domain.PaymentMethod
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.domain.SaleCheckoutLine
import com.lambliver.stallpos.domain.SaleRecord
import com.lambliver.stallpos.domain.SaleReversal
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun V2RoomDao.enqueueSyncOperation(
    category: String,
    entityType: String,
    entityId: String,
    operationType: String,
    payload: JSONObject,
    nowMillis: Long,
) {
    val operationId = UUID.randomUUID().toString()
    val operation = JSONObject()
        .put("operationId", operationId)
        .put("category", category)
        .put("entityType", entityType)
        .put("entityId", entityId)
        .put("operationType", operationType)
        .put("payload", payload)
    insertOutbox(
        SyncOutboxEntity(
            operationId = operationId,
            category = category,
            entityType = entityType,
            entityId = entityId,
            operationType = operationType,
            payloadJson = payload.toString(),
            payloadHash = sha256Hex(canonicalJson(operation)),
            status = "PENDING",
            attemptCount = 0,
            lastErrorCode = null,
            lastErrorMessage = null,
            nextAttemptAtMillis = null,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            syncedAtMillis = null,
        ),
    )
}

internal suspend fun V2RoomDao.enqueueCatalogOperations(
    plan: CatalogPersistPlan,
    previousProducts: List<Product>,
    previousCategories: List<Category>,
    previousBundleCategories: List<BundleCategory>,
    previousBundles: List<Bundle>,
    inventoryMovements: List<InventoryMovementEntity>,
    nowMillis: Long,
) {
    plan.categories?.let { rows ->
        enqueueReplacedRows(
            previousCategories,
            rows,
            id = Category::id,
            upsertPayload = { row, index -> row.toSyncJson("PRODUCT", index, nowMillis) },
            deletePayload = { row, index -> row.toSyncJson("PRODUCT", index, nowMillis, nowMillis) },
            unchanged = { old, new, oldIndex, newIndex -> old == new && oldIndex == newIndex },
            entityType = "CATEGORY",
            nowMillis = nowMillis,
        )
    }
    plan.bundleCategories?.let { rows ->
        enqueueReplacedRows(
            previousBundleCategories,
            rows,
            id = BundleCategory::id,
            upsertPayload = { row, index -> row.toSyncJson(index, nowMillis) },
            deletePayload = { row, index -> row.toSyncJson(index, nowMillis, nowMillis) },
            unchanged = { old, new, oldIndex, newIndex -> old == new && oldIndex == newIndex },
            entityType = "CATEGORY",
            nowMillis = nowMillis,
        )
    }
    plan.products?.let { rows ->
        enqueueReplacedRows(
            previousProducts,
            rows,
            id = Product::id,
            upsertPayload = { row, _ -> row.toSyncJson(nowMillis) },
            deletePayload = { row, _ -> row.toSyncJson(nowMillis, nowMillis) },
            entityType = "PRODUCT",
            nowMillis = nowMillis,
        )
    }
    plan.bundles?.let { rows ->
        enqueueReplacedRows(
            previousBundles,
            rows,
            id = Bundle::id,
            upsertPayload = { row, _ -> row.toSyncJson(nowMillis) },
            deletePayload = { row, _ -> row.toSyncJson(nowMillis, nowMillis) },
            entityType = "BUNDLE",
            nowMillis = nowMillis,
        )
    }
    inventoryMovements.forEach { row ->
        enqueueSyncOperation("INVENTORY", "INVENTORY_MOVEMENT", row.id, "APPEND", row.toSyncJson(), nowMillis)
    }
}

private suspend fun <T> V2RoomDao.enqueueReplacedRows(
    previous: List<T>,
    current: List<T>,
    id: (T) -> String,
    upsertPayload: (T, Int) -> JSONObject,
    deletePayload: (T, Int) -> JSONObject,
    unchanged: (T, T, Int, Int) -> Boolean = { old, new, _, _ -> old == new },
    entityType: String,
    nowMillis: Long,
) {
    val previousById = previous.withIndex().associateBy { id(it.value) }
    current.forEachIndexed { index, row ->
        val old = previousById[id(row)]
        if (old == null || !unchanged(old.value, row, old.index, index)) {
            enqueueSyncOperation("MASTER", entityType, id(row), "UPSERT", upsertPayload(row, index), nowMillis)
        }
    }
    val currentIds = current.mapTo(hashSetOf(), id)
    previous.forEachIndexed { index, row ->
        if (id(row) !in currentIds) {
            enqueueSyncOperation("MASTER", entityType, id(row), "DELETE", deletePayload(row, index), nowMillis)
        }
    }
}

internal fun Category.toSyncJson(type: String, sortOrder: Int, updatedAtMillis: Long, deletedAtMillis: Long? = null) =
    JSONObject()
        .put("id", id)
        .put("categoryType", type)
        .put("name", name)
        .put("sortOrder", sortOrder)
        .put("updatedAtUtc", utc(updatedAtMillis))
        .putNullable("deletedAtUtc", deletedAtMillis?.let(::utc))

internal fun BundleCategory.toSyncJson(sortOrder: Int, updatedAtMillis: Long, deletedAtMillis: Long? = null) =
    Category(id, name).toSyncJson("BUNDLE", sortOrder, updatedAtMillis, deletedAtMillis)

internal fun Product.toSyncJson(updatedAtMillis: Long, deletedAtMillis: Long? = null) = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("sellingPrice", price)
    .putNullable("cost", cost)
    .putNullable("categoryId", categoryId.takeIf { it.isNotBlank() })
    .put("trackInventory", stock != null)
    .put("isActive", deletedAtMillis == null)
    .put("updatedAtUtc", utc(updatedAtMillis))
    .putNullable("deletedAtUtc", deletedAtMillis?.let(::utc))

internal fun Bundle.toSyncJson(updatedAtMillis: Long, deletedAtMillis: Long? = null) = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("sellingPrice", price)
    .putNullable("categoryId", categoryId.takeIf { it.isNotBlank() })
    .put("isActive", deletedAtMillis == null)
    .put("components", components.fold(JSONArray()) { rows, row ->
        rows.put(JSONObject().put("productId", row.productId).put("quantity", row.qty))
    })
    .put("updatedAtUtc", utc(updatedAtMillis))
    .putNullable("deletedAtUtc", deletedAtMillis?.let(::utc))

internal fun EventEntity.toSyncJson() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("code", code)
    .put("eventType", eventType)
    .put("startAtUtc", utc(startAtMillis))
    .put("endAtUtc", utc(endAtMillis))
    .putNullable("actualOpenAtUtc", actualOpenAtMillis?.let(::utc))
    .putNullable("actualCloseAtUtc", actualCloseAtMillis?.let(::utc))
    .put("timezone", timezone)
    .put("location", location)
    .put("status", status)
    .put("updatedAtUtc", utc(updatedAtMillis))

internal fun InventoryMovementEntity.toSyncJson() = JSONObject()
    .put("id", id)
    .put("productId", productId)
    .putNullable("eventId", eventId)
    .putNullable("fromLocation", fromLocationKey?.let { locationJson(it, eventId) })
    .putNullable("toLocation", toLocationKey?.let { locationJson(it, eventId) })
    .put("type", movementType)
    .put("quantity", quantity)
    .putNullable("relatedTransactionId", relatedTransactionId)
    .put("occurredAtUtc", utc(occurredAtMillis))

internal fun SaleRecord.toSyncJson(movements: List<InventoryMovementEntity>) = JSONObject()
    .put("id", id)
    .put("deviceId", requireNotNull(deviceId))
    .putNullable("eventId", eventId)
    .put("receiptNumber", requireNotNull(receiptNumber))
    .put("occurredAtUtc", utc(tsMillis))
    .put("subtotal", checkoutLines.sumOf { it.lineSubtotal })
    .putNullable("discountType", discountType)
    .putNullable("discountValue", discountValue)
    .put("discountAmount", discountAmount)
    .put("netAdjustment", netAdjustment)
    .put("finalTotal", total)
    .put("tipAmount", tipAmount)
    .put("paymentMethod", paymentMethod.toWirePayment())
    .put("lines", checkoutLines.mapIndexed { index, line ->
        val financial = lineFinancialSnapshots.first { it.lineIndex == index }
        JSONObject()
            .put("lineIndex", index)
            .put("itemType", if (line is SaleCheckoutLine.Product) "PRODUCT" else "BUNDLE")
            .put("itemRefId", if (line is SaleCheckoutLine.Product) line.productId else (line as SaleCheckoutLine.Bundle).bundleId)
            .put("displayName", when (line) {
                is SaleCheckoutLine.Product -> requireNotNull(line.displayName)
                is SaleCheckoutLine.Bundle -> requireNotNull(line.displayName)
            })
            .put("quantity", line.qty)
            .put("unitPrice", line.unitPrice)
            .put("originalAmount", financial.originalAmount)
            .put("allocatedDiscount", financial.allocatedDiscount)
            .put("allocatedAdjustment", financial.allocatedAdjustment)
            .put("finalAmount", financial.finalAmount)
    }.toJsonArray())
    .put("componentAllocations", bundleComponentAllocations.map { row ->
        JSONObject()
            .put("lineIndex", row.lineIndex)
            .put("productId", row.productId)
            .put("productNameSnapshot", row.productNameSnapshot)
            .putNullable("unitCostSnapshot", row.unitCostSnapshot)
            .put("quantity", row.quantity)
            .put("allocatedRevenue", row.allocatedRevenue)
    }.toJsonArray())
    .put("inventoryMovements", movements.map { it.toSyncJson() }.toJsonArray())

internal fun SaleReversal.toSyncJson(movements: List<InventoryMovementEntity>) = JSONObject()
    .put("id", id)
    .put("saleId", saleId)
    .put("deviceId", requireNotNull(deviceId))
    .putNullable("eventId", eventId)
    .put("occurredAtUtc", utc(tsMillis))
    .put("reason", reason.name)
    .put("paymentMethod", paymentMethod.toWirePayment())
    .put("inventoryMovements", movements.map { it.toSyncJson() }.toJsonArray())

internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted()
        .joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
        }
    is JSONArray -> (0 until value.length()).joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> error("unsupported JSON value: ${value::class.java.name}")
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun utc(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))

private fun locationJson(key: String, eventId: String?) = if (key == InventoryLocation.GENERAL_KEY) {
    JSONObject().put("type", "GENERAL").putNullable("eventId", null)
} else {
    JSONObject().put("type", "EVENT").put("eventId", requireNotNull(eventId))
}

private fun PaymentMethod.toWirePayment() = when (this) {
    PaymentMethod.CASH -> "CASH"
    PaymentMethod.DIGITAL -> "OTHER"
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun List<JSONObject>.toJsonArray() = fold(JSONArray()) { array, row -> array.put(row) }
