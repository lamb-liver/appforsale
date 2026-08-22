package com.lambliver.stallpos.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lambliver.stallpos.domain.PosCart
import com.lambliver.stallpos.domain.ReversalReason
import org.json.JSONArray
import org.json.JSONObject

internal val LEGACY_PRODUCTS_JSON_KEY = stringPreferencesKey("products_json")
internal val LEGACY_CATEGORIES_JSON_KEY = stringPreferencesKey("categories_json")
internal val LEGACY_BUNDLE_CATEGORIES_JSON_KEY = stringPreferencesKey("bundle_categories_json")
internal val LEGACY_BUNDLES_JSON_KEY = stringPreferencesKey("bundles_json")
internal val LEGACY_CART_JSON_KEY = stringPreferencesKey("cart_json")
internal val LEGACY_TOTAL_SALES_KEY = longPreferencesKey("total_sales")
internal val LEGACY_TX_COUNT_KEY = longPreferencesKey("tx_count")

internal const val LEGACY_CLEANUP_STATE_KEY = "legacy_cleanup_state"
internal const val LEGACY_CLEANUP_CLEARED = "cleared_v1"
internal const val LEGACY_CLEANUP_PRESERVED_INVALID = "preserved_invalid_v1"

internal val LEGACY_BUSINESS_KEY_NAMES = setOf(
    LEGACY_PRODUCTS_JSON_KEY.name,
    LEGACY_CATEGORIES_JSON_KEY.name,
    LEGACY_BUNDLE_CATEGORIES_JSON_KEY.name,
    LEGACY_BUNDLES_JSON_KEY.name,
    LEGACY_CART_JSON_KEY.name,
    LEGACY_TOTAL_SALES_KEY.name,
    LEGACY_TX_COUNT_KEY.name,
    SALES_LOG_JSON_KEY.name,
    SALES_REVERSAL_LOG_JSON_KEY.name,
    LAST_CHECKOUT_JSON_KEY.name,
)

internal fun Preferences.hasLegacyBusinessKeys(): Boolean =
    asMap().keys.any { it.name in LEGACY_BUSINESS_KEY_NAMES }

internal fun MutablePreferences.removeLegacyBusinessKeys() {
    remove(LEGACY_PRODUCTS_JSON_KEY)
    remove(LEGACY_CATEGORIES_JSON_KEY)
    remove(LEGACY_BUNDLE_CATEGORIES_JSON_KEY)
    remove(LEGACY_BUNDLES_JSON_KEY)
    remove(LEGACY_CART_JSON_KEY)
    remove(LEGACY_TOTAL_SALES_KEY)
    remove(LEGACY_TX_COUNT_KEY)
    remove(SALES_LOG_JSON_KEY)
    remove(SALES_REVERSAL_LOG_JSON_KEY)
    remove(LAST_CHECKOUT_JSON_KEY)
}

/** 僅判斷 frozen legacy payload 是否可安全退休；不得用此結果重新匯入 Room。 */
internal fun Preferences.isLegacyBusinessDataSafeToRetire(): Boolean = runCatching {
    if (!hasLegacyBusinessKeys()) return@runCatching true

    get(LEGACY_TOTAL_SALES_KEY)?.let { require(it >= 0L) }
    get(LEGACY_TX_COUNT_KEY)?.let { require(it >= 0L) }

    val categoryObjects = jsonObjects(LEGACY_CATEGORIES_JSON_KEY)
    categoryObjects.forEach { row ->
        requireText(row, "id")
        requireText(row, "name", allowBlank = true)
    }
    val categories = decodeExact(LEGACY_CATEGORIES_JSON_KEY, categoryObjects, ::decodeCategories)
    requireUniqueNonBlank(categories.map { it.id })
    val categoryIds = categories.mapTo(hashSetOf()) { it.id }

    val productObjects = jsonObjects(LEGACY_PRODUCTS_JSON_KEY)
    productObjects.forEach { row ->
        requireText(row, "id")
        requireText(row, "name", allowBlank = true)
        require(row.getLong("price") >= 0L)
        if (row.has("stock") && !row.isNull("stock")) require(row.getLong("stock") >= 0L)
        row.optString("categoryId").takeIf { it.isNotBlank() }?.let { require(it in categoryIds) }
    }
    val products = decodeExact(LEGACY_PRODUCTS_JSON_KEY, productObjects, ::decodeProducts)
    requireUniqueNonBlank(products.map { it.id })
    val productIds = products.mapTo(hashSetOf()) { it.id }

    val bundleCategoryObjects = jsonObjects(LEGACY_BUNDLE_CATEGORIES_JSON_KEY)
    bundleCategoryObjects.forEach { row ->
        requireText(row, "id")
        requireText(row, "name", allowBlank = true)
    }
    val bundleCategories = decodeExact(
        LEGACY_BUNDLE_CATEGORIES_JSON_KEY,
        bundleCategoryObjects,
        ::decodeBundleCategories,
    )
    requireUniqueNonBlank(bundleCategories.map { it.id })
    val bundleCategoryIds = bundleCategories.mapTo(hashSetOf()) { it.id }

    val bundleObjects = jsonObjects(LEGACY_BUNDLES_JSON_KEY)
    bundleObjects.forEach { row ->
        requireText(row, "id")
        requireText(row, "name", allowBlank = true)
        require(row.getLong("price") >= 0L)
        row.optString("categoryId").takeIf { it.isNotBlank() }?.let { require(it in bundleCategoryIds) }
        val components = row.getJSONArray("components")
        repeat(components.length()) { index ->
            val component = components.getJSONObject(index)
            require(requireText(component, "productId") in productIds)
            require(component.getLong("qty") >= 1L)
        }
    }
    val bundles = decodeExact(LEGACY_BUNDLES_JSON_KEY, bundleObjects, ::decodeBundles)
    requireUniqueNonBlank(bundles.map { it.id })
    val bundleIds = bundles.mapTo(hashSetOf()) { it.id }

    val cart = validateCart(get(LEGACY_CART_JSON_KEY))
    require(cart.products.keys.all { it in productIds })
    require(cart.bundles.keys.all { it in bundleIds })

    val saleObjects = jsonObjects(SALES_LOG_JSON_KEY)
    saleObjects.forEach(::validateSale)
    val sales = decodeSalesRecordsJsonResult(get(SALES_LOG_JSON_KEY).orEmpty()).getOrThrow()
    require(sales.size == saleObjects.size)
    requireUniqueNonBlank(sales.map { it.id })
    val saleIds = sales.mapTo(hashSetOf()) { it.id }

    val reversalObjects = jsonObjects(SALES_REVERSAL_LOG_JSON_KEY)
    reversalObjects.forEach { row ->
        requireText(row, "id")
        requireText(row, "saleId")
        row.getLong("ts")
        ReversalReason.valueOf(requireText(row, "reason"))
    }
    val reversals = decodeExact(
        SALES_REVERSAL_LOG_JSON_KEY,
        reversalObjects,
        ::decodeSaleReversalsJson,
    )
    requireUniqueNonBlank(reversals.map { it.id })
    requireUniqueNonBlank(reversals.map { it.saleId })
    require(reversals.all { it.saleId in saleIds })

    get(LAST_CHECKOUT_JSON_KEY).orEmpty().takeIf { it.isNotBlank() }?.let { raw ->
        val lastObject = JSONObject(raw)
        requireText(lastObject, "saleId")
        lastObject.getLong("ts")
        require(lastObject.getLong("total") >= 0L)
        validateQuantityObject(
            lastObject.getJSONObject("cart"),
            positiveOnly = false,
            maxValue = Int.MAX_VALUE.toLong(),
        )
        lastObject.optJSONObject("bundles")?.let {
            validateQuantityObject(it, positiveOnly = false, maxValue = Int.MAX_VALUE.toLong())
        }
        lastObject.optJSONObject("stockDeductions")?.let { validateQuantityObject(it, positiveOnly = false) }
        val last = requireNotNull(decodeLastCheckoutJson(raw))
        require(last.saleId in saleIds)
        require(reversals.none { it.saleId == last.saleId })
    }
}.isSuccess

private fun Preferences.jsonObjects(key: Preferences.Key<String>): List<JSONObject> {
    val raw = get(key).orEmpty()
    if (raw.isBlank()) return emptyList()
    val array = JSONArray(raw)
    return List(array.length()) { array.getJSONObject(it) }
}

private fun <T> Preferences.decodeExact(
    key: Preferences.Key<String>,
    objects: List<JSONObject>,
    decode: (String) -> List<T>,
): List<T> = decode(get(key).orEmpty()).also { require(it.size == objects.size) }

private fun validateCart(raw: String?): PosCart {
    if (raw.isNullOrBlank()) return PosCart()
    val cart = JSONObject(raw)
    val version = if (cart.has("v")) {
        require(cart.get("v") is Number)
        cart.getInt("v")
    } else {
        0
    }
    when (version) {
        2 -> {
            require(cart.opt("p") == null || cart.opt("p") is JSONObject)
            require(cart.opt("b") == null || cart.opt("b") is JSONObject)
            cart.optJSONObject("p")?.let {
                validateQuantityObject(it, positiveOnly = true, maxValue = Int.MAX_VALUE.toLong())
            }
            cart.optJSONObject("b")?.let {
                validateQuantityObject(it, positiveOnly = true, maxValue = Int.MAX_VALUE.toLong())
            }
        }
        0 -> {
            require(!cart.has("p") && !cart.has("b"))
            validateQuantityObject(cart, positiveOnly = true, maxValue = Int.MAX_VALUE.toLong())
        }
        else -> error("unsupported legacy cart schema")
    }
    return decodePosCartJson(raw)
}

private fun validateSale(row: JSONObject) {
    requireText(row, "id")
    row.getLong("ts")
    requireText(row, "date", allowBlank = true)
    require(row.getLong("subtotal") >= 0L)
    require(row.getLong("discount") >= 0L)
    require(row.getLong("total") >= 0L)
    require(row.getLong("tipAmount") >= 0L)
    require(row.getString("paymentMethod") in setOf("CASH", "DIGITAL"))
    validateQuantityObject(
        row.getJSONObject("cart"),
        positiveOnly = false,
        maxValue = Int.MAX_VALUE.toLong(),
    )
    validateQuantityObject(
        row.getJSONObject("bundles"),
        positiveOnly = false,
        maxValue = Int.MAX_VALUE.toLong(),
    )
    validateQuantityObject(row.getJSONObject("stockDeductions"), positiveOnly = false)
    val lines = row.getJSONArray("lines")
    repeat(lines.length()) { index ->
        val line = lines.getJSONObject(index)
        when (line.getString("kind")) {
            "product" -> requireText(line, "productId")
            "bundle" -> requireText(line, "bundleId")
            else -> error("unknown checkout line kind")
        }
        require(line.getLong("qty") in 0..Int.MAX_VALUE.toLong())
        require(line.getLong("unitPrice") >= 0L)
        require(line.getLong("lineSubtotal") >= 0L)
    }
}

private fun validateQuantityObject(
    value: JSONObject,
    positiveOnly: Boolean,
    maxValue: Long = Long.MAX_VALUE,
) {
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        require(key.isNotBlank())
        require(value.get(key) is Number)
        val quantity = value.getLong(key)
        require(if (positiveOnly) quantity > 0L else quantity >= 0L)
        require(quantity <= maxValue)
    }
}

private fun requireText(value: JSONObject, key: String, allowBlank: Boolean = false): String =
    value.getString(key).also { if (!allowBlank) require(it.isNotBlank()) }

private fun requireUniqueNonBlank(ids: List<String>) {
    require(ids.all { it.isNotBlank() })
    require(ids.size == ids.toSet().size)
}
