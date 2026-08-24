package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.*

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * 購物車／銷售紀錄／上一筆結帳／備份封包驗證等 JSON 縫（與目錄 JSON [PosPersistCatalogJson]、網域模型 [PosPersistModels] 分檔）。
 */

internal fun encodeIntQtyObjectMap(map: Map<String, Int>): JSONObject =
    JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }

internal fun decodeIntQtyObjectMap(o: JSONObject?): Map<String, Int> {
    if (o == null) return emptyMap()
    return buildMap { o.keys().forEach { k -> put(k, o.getInt(k)) } }
}

internal fun decodeLegacyFlatCartMap(o: JSONObject): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    val skip = setOf("v", "p", "b")
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        if (k in skip) continue
        map[k] = o.getInt(k)
    }
    return map
}

internal fun encodePosCartJson(cart: PosCart): String =
    JSONObject()
        .put("v", 2)
        .put("p", encodeIntQtyObjectMap(cart.products))
        .put("b", encodeIntQtyObjectMap(cart.bundles))
        .toString()

internal fun decodePosCartJson(json: String): PosCart = runCatching {
    if (json.isBlank()) return@runCatching PosCart()
    val o = JSONObject(json)
    when (o.optInt("v", 0)) {
        2 -> PosCart(
            products = decodeIntQtyObjectMap(o.optJSONObject("p")),
            bundles = decodeIntQtyObjectMap(o.optJSONObject("b")),
        )
        else -> PosCart(products = decodeLegacyFlatCartMap(o), bundles = emptyMap())
    }
}.getOrElse { PosCart() }

internal fun decodeCartFlatJson(json: String): Map<String, Int> = runCatching {
    val o = JSONObject(json)
    buildMap { o.keys().forEach { k -> put(k, o.getInt(k)) } }
}.getOrElse { emptyMap() }

internal fun encodeCartFlatJson(cart: Map<String, Int>): String =
    JSONObject().apply { cart.forEach { (id, qty) -> put(id, qty) } }.toString()

internal fun decodePaymentMethodPersist(raw: String): PaymentMethod =
    when (raw.uppercase(Locale.ROOT)) {
        "DIGITAL" -> PaymentMethod.DIGITAL
        else -> PaymentMethod.CASH
    }

internal fun decodeLongQtyMapPersist(o: JSONObject?): Map<String, Long> {
    if (o == null) return emptyMap()
    return buildMap {
        o.keys().forEach { k -> put(k, o.getLong(k).coerceAtLeast(0L)) }
    }
}

internal fun encodeLongQtyMapPersist(map: Map<String, Long>): JSONObject =
    JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }

internal fun decodeCheckoutLinesPersist(arr: JSONArray?): List<SaleCheckoutLine> {
    if (arr == null) return emptyList()
    return List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        when (o.optString("kind", "product")) {
            "bundle" ->
                SaleCheckoutLine.Bundle(
                    bundleId = o.getString("bundleId"),
                    qty = o.getInt("qty").coerceAtLeast(0),
                    unitPrice = o.optLong("unitPrice", 0L).coerceAtLeast(0L),
                    lineSubtotal = o.optLong("lineSubtotal", 0L).coerceAtLeast(0L),
                    displayName = o.optString("displayName", "").trim().takeIf { it.isNotEmpty() },
                )
            else ->
                SaleCheckoutLine.Product(
                    productId = o.getString("productId"),
                    qty = o.getInt("qty").coerceAtLeast(0),
                    unitPrice = o.optLong("unitPrice", 0L).coerceAtLeast(0L),
                    lineSubtotal = o.optLong("lineSubtotal", 0L).coerceAtLeast(0L),
                    displayName = o.optString("displayName", "").trim().takeIf { it.isNotEmpty() },
                )
        }
    }
}

internal fun encodeCheckoutLinesPersist(lines: List<SaleCheckoutLine>): JSONArray =
    JSONArray().apply {
        lines.forEach { line ->
            put(
                when (line) {
                    is SaleCheckoutLine.Product ->
                        JSONObject()
                            .put("kind", "product")
                            .put("productId", line.productId)
                            .put("qty", line.qty)
                            .put("unitPrice", line.unitPrice)
                            .put("lineSubtotal", line.lineSubtotal)
                            .apply { line.displayName?.let { put("displayName", it) } }
                    is SaleCheckoutLine.Bundle ->
                        JSONObject()
                            .put("kind", "bundle")
                            .put("bundleId", line.bundleId)
                            .put("qty", line.qty)
                            .put("unitPrice", line.unitPrice)
                            .put("lineSubtotal", line.lineSubtotal)
                            .apply { line.displayName?.let { put("displayName", it) } }
                },
            )
        }
    }

internal fun encodeSalesRecordsJson(log: List<SaleRecord>): String =
    JSONArray().apply {
        log.forEach { r ->
            put(
                JSONObject()
                    .put("id", r.id)
                    .put("ts", r.tsMillis)
                    .put("date", r.dateKey)
                    .put("subtotal", r.subtotal)
                    .put("discount", r.discount)
                    .put("total", r.total)
                    .put("cart", JSONObject(encodeCartFlatJson(r.cartSnapshot)))
                    .put("bundles", encodeIntQtyObjectMap(r.bundleCartSnapshot))
                    .put("paymentMethod", r.paymentMethod.name)
                    .put("tipAmount", r.tipAmount)
                    .put("lines", encodeCheckoutLinesPersist(r.checkoutLines))
                    .put("stockDeductions", encodeLongQtyMapPersist(r.stockDeductions))
                    .put("discountAmount", r.discountAmount)
                    .put("netAdjustment", r.netAdjustment)
                    .put("lineFinancialSnapshots", encodeLineFinancialSnapshots(r.lineFinancialSnapshots))
                    .put("bundleComponentAllocations", encodeBundleRevenueAllocations(r.bundleComponentAllocations))
                    .apply {
                        r.eventId?.let { put("eventId", it) }
                        r.deviceId?.let { put("deviceId", it) }
                        r.receiptNumber?.let { put("receiptNumber", it) }
                        r.discountType?.let { put("discountType", it) }
                        r.discountValue?.let { put("discountValue", it) }
                    },
            )
        }
    }.toString()

internal fun encodeLastCheckoutJson(l: LastCheckout): String =
    JSONObject()
        .put("saleId", l.saleId)
        .put("ts", l.tsMillis)
        .put("total", l.total)
        .put("cart", JSONObject(encodeCartFlatJson(l.productCart)))
        .put("bundles", encodeIntQtyObjectMap(l.bundleCart))
        .put("stockDeductions", encodeLongQtyMapPersist(l.stockDeductions))
        .toString()

internal fun decodeLastCheckoutJson(json: String): LastCheckout? = runCatching {
    if (json.isBlank()) return@runCatching null
    val o = JSONObject(json)
    LastCheckout(
        saleId = o.optString("saleId", "").trim(),
        tsMillis = o.getLong("ts"),
        total = o.getLong("total"),
        productCart = decodeCartFlatJson(o.getJSONObject("cart").toString()),
        bundleCart = decodeIntQtyObjectMap(o.optJSONObject("bundles")),
        stockDeductions = decodeLongQtyMapPersist(o.optJSONObject("stockDeductions")),
    )
}.getOrNull()

internal fun decodeSalesRecordsJsonResult(json: String): Result<List<SaleRecord>> = runCatching {
    if (json.isBlank()) return@runCatching emptyList()
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val cartJson = o.optJSONObject("cart")?.toString() ?: "{}"
        val record = SaleRecord(
            id = o.optString("id", "").trim(),
            tsMillis = o.getLong("ts"),
            dateKey = o.getString("date"),
            subtotal = o.getLong("subtotal"),
            discount = o.getLong("discount"),
            total = o.getLong("total"),
            cartSnapshot = decodeCartFlatJson(cartJson),
            bundleCartSnapshot = decodeIntQtyObjectMap(o.optJSONObject("bundles")),
            paymentMethod = decodePaymentMethodPersist(o.optString("paymentMethod", "CASH")),
            tipAmount = o.optLong("tipAmount", 0L).coerceAtLeast(0L),
            checkoutLines = decodeCheckoutLinesPersist(o.optJSONArray("lines")),
            stockDeductions = decodeLongQtyMapPersist(o.optJSONObject("stockDeductions")),
            eventId = o.optString("eventId", "").takeIf { it.isNotBlank() },
            deviceId = o.optString("deviceId", "").takeIf { it.isNotBlank() },
            receiptNumber = o.optString("receiptNumber", "").takeIf { it.isNotBlank() },
            discountType = o.optString("discountType", "").takeIf { it.isNotBlank() },
            discountValue = if (o.has("discountValue") && !o.isNull("discountValue")) o.getLong("discountValue") else null,
            discountAmount = o.optLong("discountAmount", o.getLong("discount")),
            netAdjustment = o.optLong("netAdjustment", 0),
            lineFinancialSnapshots = decodeLineFinancialSnapshots(o.optJSONArray("lineFinancialSnapshots")),
            bundleComponentAllocations = decodeBundleRevenueAllocations(o.optJSONArray("bundleComponentAllocations")),
        )
        if (record.id.isNotEmpty()) record else record.copy(id = legacySaleId(i, record))
    }
}

internal fun decodeSalesRecordsJson(json: String): List<SaleRecord> =
    decodeSalesRecordsJsonResult(json).getOrElse { emptyList() }

internal fun encodeSaleReversalsJson(log: List<SaleReversal>): String =
    JSONArray().apply {
        log.forEach { reversal ->
            put(
                JSONObject()
                    .put("id", reversal.id)
                    .put("saleId", reversal.saleId)
                    .put("ts", reversal.tsMillis)
                    .put("reason", reversal.reason.name)
                    .put("paymentMethod", reversal.paymentMethod.name)
                    .apply {
                        reversal.eventId?.let { put("eventId", it) }
                        reversal.deviceId?.let { put("deviceId", it) }
                    },
            )
        }
    }.toString()

internal fun decodeSaleReversalsJson(json: String): List<SaleReversal> = runCatching {
    if (json.isBlank()) return@runCatching emptyList()
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        SaleReversal(
            id = o.getString("id"),
            saleId = o.getString("saleId"),
            tsMillis = o.getLong("ts"),
            reason = ReversalReason.valueOf(o.getString("reason")),
            eventId = o.optString("eventId", "").takeIf { it.isNotBlank() },
            deviceId = o.optString("deviceId", "").takeIf { it.isNotBlank() },
            paymentMethod = decodePaymentMethodPersist(o.optString("paymentMethod", "CASH")),
        )
    }
}.getOrElse { emptyList() }

private fun encodeLineFinancialSnapshots(rows: List<SaleLineFinancialSnapshot>) = JSONArray().apply {
    rows.forEach { row ->
        put(
            JSONObject()
                .put("lineIndex", row.lineIndex)
                .put("originalAmount", row.originalAmount)
                .put("allocatedDiscount", row.allocatedDiscount)
                .put("allocatedAdjustment", row.allocatedAdjustment)
                .put("finalAmount", row.finalAmount)
                .apply { row.unitCostSnapshot?.let { put("unitCostSnapshot", it) } },
        )
    }
}

private fun decodeLineFinancialSnapshots(rows: JSONArray?): List<SaleLineFinancialSnapshot> =
    if (rows == null) emptyList() else List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        SaleLineFinancialSnapshot(
            lineIndex = row.getInt("lineIndex"),
            unitCostSnapshot = if (row.has("unitCostSnapshot") && !row.isNull("unitCostSnapshot")) {
                row.getLong("unitCostSnapshot")
            } else null,
            originalAmount = row.getLong("originalAmount"),
            allocatedDiscount = row.getLong("allocatedDiscount"),
            allocatedAdjustment = row.getLong("allocatedAdjustment"),
            finalAmount = row.getLong("finalAmount"),
        )
    }

private fun encodeBundleRevenueAllocations(rows: List<BundleRevenueAllocation>) = JSONArray().apply {
    rows.forEach { row ->
        put(
            JSONObject()
                .put("lineIndex", row.lineIndex)
                .put("allocationIndex", row.allocationIndex)
                .put("productId", row.productId)
                .put("productNameSnapshot", row.productNameSnapshot)
                .put("quantity", row.quantity)
                .put("allocatedRevenue", row.allocatedRevenue)
                .apply { row.unitCostSnapshot?.let { put("unitCostSnapshot", it) } },
        )
    }
}

private fun decodeBundleRevenueAllocations(rows: JSONArray?): List<BundleRevenueAllocation> =
    if (rows == null) emptyList() else List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        BundleRevenueAllocation(
            lineIndex = row.getInt("lineIndex"),
            allocationIndex = row.getInt("allocationIndex"),
            productId = row.getString("productId"),
            productNameSnapshot = row.getString("productNameSnapshot"),
            unitCostSnapshot = if (row.has("unitCostSnapshot") && !row.isNull("unitCostSnapshot")) {
                row.getLong("unitCostSnapshot")
            } else null,
            quantity = row.getLong("quantity"),
            allocatedRevenue = row.getLong("allocatedRevenue"),
        )
    }

/** Legacy LastCheckout 只在唯一匹配時補 saleId；不猜測 destructive Undo 目標。 */
internal fun linkLastCheckoutToSales(
    lastCheckout: LastCheckout?,
    sales: List<SaleRecord>,
): LastCheckout? {
    val last = lastCheckout ?: return null
    if (last.saleId.isNotBlank()) {
        return last.takeIf { candidate -> sales.any { it.id == candidate.saleId } }
    }
    val matches = sales.filter { sale ->
        sale.tsMillis == last.tsMillis &&
            sale.total + sale.tipAmount == last.total &&
            sale.cartSnapshot == last.productCart &&
            sale.bundleCartSnapshot == last.bundleCart &&
            (last.stockDeductions.isEmpty() || sale.stockDeductions == last.stockDeductions)
    }
    return matches.singleOrNull()?.let { last.copy(saleId = it.id) }
}

internal data class MigratedTransactionJson(
    val salesJson: String,
    val lastCheckoutJson: String,
)

/** 一次性 v2→v3 正規化；失敗由 caller 保留原 Sales 並停用 Undo。 */
internal fun migrateLegacyTransactionJson(
    salesJson: String,
    lastCheckoutJson: String,
): Result<MigratedTransactionJson> = decodeSalesRecordsJsonResult(salesJson).map { sales ->
    val linkedLast = linkLastCheckoutToSales(decodeLastCheckoutJson(lastCheckoutJson), sales)
    MigratedTransactionJson(
        salesJson = encodeSalesRecordsJson(sales),
        lastCheckoutJson = linkedLast?.let(::encodeLastCheckoutJson).orEmpty(),
    )
}

private fun legacySaleId(index: Int, sale: SaleRecord): String {
    val canonical = buildString {
        append("stallpos:legacy-sale:v1|")
        appendLegacyToken(index)
        appendLegacyToken(sale.tsMillis)
        appendLegacyToken(sale.dateKey)
        appendLegacyToken(sale.subtotal)
        appendLegacyToken(sale.discount)
        appendLegacyToken(sale.total)
        appendLegacyToken(sale.paymentMethod.name)
        appendLegacyToken(sale.tipAmount)
        appendLegacyToken("productCart")
        sale.cartSnapshot.toSortedMap().forEach { (id, qty) ->
            appendLegacyToken(id)
            appendLegacyToken(qty)
        }
        appendLegacyToken("bundleCart")
        sale.bundleCartSnapshot.toSortedMap().forEach { (id, qty) ->
            appendLegacyToken(id)
            appendLegacyToken(qty)
        }
        appendLegacyToken("checkoutLines")
        sale.checkoutLines.forEach { line ->
            when (line) {
                is SaleCheckoutLine.Product -> {
                    appendLegacyToken("product")
                    appendLegacyToken(line.productId)
                }
                is SaleCheckoutLine.Bundle -> {
                    appendLegacyToken("bundle")
                    appendLegacyToken(line.bundleId)
                }
            }
            appendLegacyToken(line.qty)
            appendLegacyToken(line.unitPrice)
            appendLegacyToken(line.lineSubtotal)
        }
        appendLegacyToken("stockDeductions")
        sale.stockDeductions.toSortedMap().forEach { (id, qty) ->
            appendLegacyToken(id)
            appendLegacyToken(qty)
        }
    }
    return UUID.nameUUIDFromBytes(canonical.toByteArray(Charsets.UTF_8)).toString()
}

private fun StringBuilder.appendLegacyToken(value: Any) {
    val text = value.toString()
    append(text.length).append(':').append(text).append('|')
}

internal fun parseValidatedBackupPayload(jsonText: String): JSONObject {
    val envelope = parseBackupEnvelope(jsonText)
    val payload = JSONObject(envelope.payloadJson)
    return BackupMigration.migratePayloadToCurrent(envelope.schemaVersion, payload)
}

/** Backup schema 3→4：只回填可由同一 payload Catalog 證明的交易名稱。 */
internal fun backfillSaleLineDisplayNames(
    salesJson: String,
    productsJson: String,
    bundlesJson: String,
): Result<String> = decodeSalesRecordsJsonResult(salesJson).map { sales ->
    val productNames = decodeProducts(productsJson).associate { it.id to it.name }
    val bundleNames = decodeBundles(bundlesJson).associate { it.id to it.name }
    encodeSalesRecordsJson(
        sales.map { sale ->
            sale.copy(
                checkoutLines = sale.checkoutLines.map { line ->
                    when (line) {
                        is SaleCheckoutLine.Product ->
                            if (!line.displayName.isNullOrBlank()) line
                            else line.copy(displayName = productNames[line.productId])
                        is SaleCheckoutLine.Bundle ->
                            if (!line.displayName.isNullOrBlank()) line
                            else line.copy(displayName = bundleNames[line.bundleId])
                    }
                },
            )
        },
    )
}
