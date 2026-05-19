package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.*

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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
                )
            else ->
                SaleCheckoutLine.Product(
                    productId = o.getString("productId"),
                    qty = o.getInt("qty").coerceAtLeast(0),
                    unitPrice = o.optLong("unitPrice", 0L).coerceAtLeast(0L),
                    lineSubtotal = o.optLong("lineSubtotal", 0L).coerceAtLeast(0L),
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
                    is SaleCheckoutLine.Bundle ->
                        JSONObject()
                            .put("kind", "bundle")
                            .put("bundleId", line.bundleId)
                            .put("qty", line.qty)
                            .put("unitPrice", line.unitPrice)
                            .put("lineSubtotal", line.lineSubtotal)
                },
            )
        }
    }

internal fun encodeSalesRecordsJson(log: List<SaleRecord>): String =
    JSONArray().apply {
        log.forEach { r ->
            put(
                JSONObject()
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
                    .put("stockDeductions", encodeLongQtyMapPersist(r.stockDeductions)),
            )
        }
    }.toString()

internal fun encodeLastCheckoutJson(l: LastCheckout): String =
    JSONObject()
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
        tsMillis = o.getLong("ts"),
        total = o.getLong("total"),
        productCart = decodeCartFlatJson(o.getJSONObject("cart").toString()),
        bundleCart = decodeIntQtyObjectMap(o.optJSONObject("bundles")),
        stockDeductions = decodeLongQtyMapPersist(o.optJSONObject("stockDeductions")),
    )
}.getOrNull()

internal fun decodeSalesRecordsJson(json: String): List<SaleRecord> = runCatching {
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val cartJson = o.optJSONObject("cart")?.toString() ?: "{}"
        SaleRecord(
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
        )
    }
}.getOrElse { emptyList() }

internal fun parseValidatedBackupPayload(jsonText: String): JSONObject {
    val envelope = parseBackupEnvelope(jsonText)
    val payload = JSONObject(envelope.payloadJson)
    return BackupMigration.migratePayloadToCurrent(envelope.schemaVersion, payload)
}
