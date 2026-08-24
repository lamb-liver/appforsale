package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

internal fun decodeCloudBootstrap(root: JSONObject): PosPersistSnapshot {
    val productRows = root.requiredArray("products").objects()
    val bundleRows = root.requiredArray("bundles").objects()
    val eventRows = root.requiredArray("events").objects()
    val movementRows = root.requiredArray("inventory").objects()
    val transactionRows = root.requiredArray("transactions").objects()
    val levelRows = root.requiredArray("inventoryLevels").objects()

    val categories = productRows.filter { it.has("categoryType") && it.getString("categoryType") == "PRODUCT" && it.isLive() }
        .sortedBy { it.getInt("sortOrder") }.map { Category(it.getString("id"), it.getString("name")) }
    val bundleCategories = productRows.filter { it.has("categoryType") && it.getString("categoryType") == "BUNDLE" && it.isLive() }
        .sortedBy { it.getInt("sortOrder") }.map { BundleCategory(it.getString("id"), it.getString("name")) }
    val products = productRows.filter { it.has("sellingPrice") && it.optBoolean("isActive", true) && it.isLive() }.map { row ->
        Product(
            id = row.getString("id"),
            name = row.getString("name"),
            price = row.getLong("sellingPrice"),
            categoryId = row.nullableString("categoryId").orEmpty(),
            stock = 0L.takeIf { row.getBoolean("trackInventory") },
            cost = row.nullableLong("cost"),
        )
    }
    val bundles = bundleRows.filter { it.optBoolean("isActive", true) && it.isLive() }.map { row ->
        Bundle(
            id = row.getString("id"),
            name = row.getString("name"),
            price = row.getLong("sellingPrice"),
            categoryId = row.nullableString("categoryId").orEmpty(),
            components = row.getJSONArray("components").objects().map {
                BundleComponent(it.getString("productId"), it.getLong("quantity"))
            },
        )
    }
    val events = eventRows.map { row ->
        MarketEvent(
            id = row.getString("id"),
            name = row.getString("name"),
            code = row.getString("code"),
            type = MarketEventType.valueOf(row.getString("eventType")),
            startAtMillis = utcMillis(row.getString("startAtUtc")),
            endAtMillis = utcMillis(row.getString("endAtUtc")),
            actualOpenAtMillis = row.nullableString("actualOpenAtUtc")?.let(::utcMillis),
            actualCloseAtMillis = row.nullableString("actualCloseAtUtc")?.let(::utcMillis),
            timezone = row.getString("timezone"),
            location = row.optString("location"),
            status = MarketEventStatus.valueOf(row.getString("status")),
            updatedAtMillis = utcMillis(row.getString("updatedAtUtc")),
        )
    }
    val movements = movementRows.map(::decodeMovement)
    val levels = levelRows.map { row ->
        InventoryLevel(
            productId = row.getString("productId"),
            location = if (row.getString("locationType") == "GENERAL") InventoryLocation.General
                else InventoryLocation.event(requireNotNull(row.nullableString("eventId"))),
            quantity = row.getLong("quantity"),
            updatedAtMillis = utcMillis(row.getString("updatedAtUtc")),
        )
    }
    val sales = transactionRows.filter { it.has("receiptNumber") }.map(::decodeSale)
    val reversals = transactionRows.filter { it.has("saleId") && !it.has("receiptNumber") }.map { row ->
        SaleReversal(
            id = row.getString("id"),
            saleId = row.getString("saleId"),
            tsMillis = utcMillis(row.getString("occurredAtUtc")),
            reason = ReversalReason.UNDO_LAST_CHECKOUT,
            eventId = row.nullableString("eventId"),
            deviceId = row.getString("deviceId"),
            paymentMethod = row.paymentMethod(),
        )
    }
    requireUnique(products.map { it.id })
    requireUnique(sales.map { it.id })
    requireUnique(reversals.map { it.saleId })
    require(reversals.all { reversal -> sales.any { it.id == reversal.saleId } })
    val active = activeSales(sales, reversals)
    return PosPersistSnapshot(
        products = products,
        categories = categories,
        bundleCategories = bundleCategories,
        bundles = bundles,
        totalSales = active.sumOf { it.total + it.tipAmount },
        txCount = active.size.toLong(),
        salesLog = sales,
        reversalLog = reversals,
        events = events,
        inventoryLevels = levels,
        inventoryMovements = movements,
    )
}

private fun decodeSale(row: JSONObject): SaleRecord {
    val lines = row.getJSONArray("lines").objects().map { line ->
        if (line.getString("itemType") == "PRODUCT") {
            SaleCheckoutLine.Product(line.getString("itemRefId"), line.getInt("quantity"), line.getLong("unitPrice"),
                line.getLong("originalAmount"), line.getString("displayName"))
        } else {
            SaleCheckoutLine.Bundle(line.getString("itemRefId"), line.getInt("quantity"), line.getLong("unitPrice"),
                line.getLong("originalAmount"), line.getString("displayName"))
        }
    }
    val financial = row.getJSONArray("lines").objects().map { line ->
        SaleLineFinancialSnapshot(
            line.getInt("lineIndex"), line.nullableLong("unitCostSnapshot"), line.getLong("originalAmount"), line.getLong("allocatedDiscount"),
            line.getLong("allocatedAdjustment"), line.getLong("finalAmount"),
        )
    }
    val allocations = row.getJSONArray("componentAllocations").objects().groupBy { it.getInt("lineIndex") }
        .flatMap { (_, rows) -> rows.mapIndexed { index, item ->
            BundleRevenueAllocation(
                item.getInt("lineIndex"), index, item.getString("productId"), item.getString("productNameSnapshot"),
                item.nullableLong("unitCostSnapshot"), item.getLong("quantity"), item.getLong("allocatedRevenue"),
            )
        } }
    val embeddedMovements = row.getJSONArray("inventoryMovements").objects().map(::decodeMovement)
    val productCart = lines.filterIsInstance<SaleCheckoutLine.Product>().associate { it.productId to it.qty }
    val bundleCart = lines.filterIsInstance<SaleCheckoutLine.Bundle>().associate { it.bundleId to it.qty }
    val deductions = embeddedMovements.groupBy { it.productId }.mapValues { (_, values) -> values.sumOf { it.quantity } }
    val occurred = utcMillis(row.getString("occurredAtUtc"))
    return SaleRecord(
        id = row.getString("id"),
        tsMillis = occurred,
        dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(occurred),
        subtotal = row.getLong("finalTotal"),
        discount = 0,
        total = row.getLong("finalTotal"),
        cartSnapshot = productCart,
        bundleCartSnapshot = bundleCart,
        paymentMethod = row.paymentMethod(),
        tipAmount = row.getLong("tipAmount"),
        checkoutLines = lines,
        stockDeductions = deductions,
        eventId = row.nullableString("eventId"),
        deviceId = row.getString("deviceId"),
        receiptNumber = row.getString("receiptNumber"),
        discountType = row.nullableString("discountType"),
        discountValue = row.nullableLong("discountValue"),
        discountAmount = row.getLong("discountAmount"),
        netAdjustment = row.getLong("netAdjustment"),
        lineFinancialSnapshots = financial,
        bundleComponentAllocations = allocations,
    )
}

private fun decodeMovement(row: JSONObject) = InventoryMovement(
    id = row.getString("id"),
    productId = row.getString("productId"),
    eventId = row.nullableString("eventId"),
    from = row.optJSONObject("fromLocation")?.location(),
    to = row.optJSONObject("toLocation")?.location(),
    type = InventoryMovementType.valueOf(row.getString("type")),
    quantity = row.getLong("quantity"),
    relatedTransactionId = row.nullableString("relatedTransactionId"),
    occurredAtMillis = utcMillis(row.getString("occurredAtUtc")),
)

private fun JSONObject.location() = if (getString("type") == "GENERAL") InventoryLocation.General
else InventoryLocation.event(getString("eventId"))

private fun JSONObject.paymentMethod() = if (getString("paymentMethod") == "CASH") PaymentMethod.CASH else PaymentMethod.DIGITAL
private fun JSONObject.isLive() = !has("deletedAtUtc") || isNull("deletedAtUtc")
private fun JSONObject.nullableString(key: String) = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.nullableLong(key: String) = if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.requiredArray(key: String): JSONArray = getJSONArray(key)
private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun requireUnique(ids: List<String>) = require(ids.size == ids.toSet().size && ids.none(String::isBlank))

private fun utcMillis(value: String): Long = requireNotNull(
    listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX").firstNotNullOfOrNull { pattern ->
        runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)?.time
        }.getOrNull()
    },
) { "invalid UTC timestamp" }
