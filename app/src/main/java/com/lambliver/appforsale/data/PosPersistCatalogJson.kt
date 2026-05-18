package com.lambliver.appforsale.data

import com.lambliver.appforsale.domain.*

import org.json.JSONArray
import org.json.JSONObject

/**
 * 商品目錄／分類／套組之 JSON（與 [PosStore] 交易語意分檔，便于維護與單元測試延伸）。
 */

internal fun encodeProducts(list: List<Product>): String = JSONArray().apply {
    list.forEach { p ->
        put(
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("price", p.price)
                .put("categoryId", p.categoryId)
                .apply { if (p.stock != null) put("stock", p.stock!!) },
        )
    }
}.toString()

internal fun decodeProducts(json: String): List<Product> = runCatching {
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val stock = when {
            !o.has("stock") || o.isNull("stock") -> null
            else -> o.getLong("stock").coerceAtLeast(0L)
        }
        Product(o.getString("id"), o.getString("name"), o.getLong("price"), o.optString("categoryId", ""), stock)
    }
}.getOrElse { emptyList() }

internal fun encodeCategories(list: List<Category>): String = JSONArray().apply {
    list.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name)) }
}.toString()

internal fun decodeCategories(json: String): List<Category> = runCatching {
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        Category(o.getString("id"), o.getString("name"))
    }
}.getOrElse { emptyList() }

internal fun encodeBundleCategories(list: List<BundleCategory>): String = JSONArray().apply {
    list.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name)) }
}.toString()

internal fun decodeBundleCategories(json: String): List<BundleCategory> = runCatching {
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        BundleCategory(o.getString("id"), o.getString("name"))
    }
}.getOrElse { emptyList() }

internal fun encodeBundles(list: List<Bundle>): String = JSONArray().apply {
    list.forEach { b ->
        put(
            JSONObject()
                .put("id", b.id)
                .put("name", b.name)
                .put("price", b.price)
                .put("categoryId", b.categoryId)
                .put(
                    "components",
                    JSONArray().apply {
                        b.components.forEach { c ->
                            put(JSONObject().put("productId", c.productId).put("qty", c.qty))
                        }
                    },
                ),
        )
    }
}.toString()

internal fun decodeBundles(json: String): List<Bundle> = runCatching {
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val compArr = o.getJSONArray("components")
        val components = List(compArr.length()) { j ->
            val co = compArr.getJSONObject(j)
            BundleComponent(co.getString("productId"), co.getLong("qty").coerceAtLeast(1L))
        }
        Bundle(
            id = o.getString("id"),
            name = o.getString("name"),
            price = o.getLong("price").coerceAtLeast(0L),
            categoryId = o.optString("categoryId", ""),
            components = components,
        )
    }
}.getOrElse { emptyList() }

/** 無結帳列時由購物車與目錄推算一般品行（供 [PosStore.checkout]）。 */
internal fun buildProductLinesFromCart(cart: Map<String, Int>, products: List<Product>): List<SaleCheckoutLine> =
    cart.mapNotNull { (id, qty) ->
        val p = products.find { it.id == id } ?: return@mapNotNull null
        val q = qty.coerceAtLeast(0)
        if (q == 0) return@mapNotNull null
        val sub = (p.price * q.toLong()).coerceAtLeast(0L)
        SaleCheckoutLine.Product(productId = p.id, qty = q, unitPrice = p.price, lineSubtotal = sub)
    }
