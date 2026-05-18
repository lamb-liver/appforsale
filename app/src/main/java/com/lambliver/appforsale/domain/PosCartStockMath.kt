package com.lambliver.appforsale.domain

/**
 * 購物車／套組可售上限與結帳扣庫計算（純函式，便于與 [PosViewModel] 分離閱讀）。
 */

internal fun posRemainingAfterSingles(products: List<Product>, pc: Map<String, Int>): MutableMap<String, Long> {
    val rem = mutableMapOf<String, Long>()
    for (p in products) {
        val s = p.stock ?: continue
        val used = pc[p.id]?.toLong() ?: 0L
        rem[p.id] = (s - used).coerceAtLeast(0L)
    }
    return rem
}

internal fun posSubtractBundleConsumption(
    rem: MutableMap<String, Long>,
    bundle: Bundle,
    productsById: Map<String, Product>,
    sets: Int,
) {
    if (sets <= 0) return
    for (c in bundle.components) {
        val p = productsById[c.productId] ?: continue
        if (p.stock == null) continue
        val cur = rem[c.productId] ?: 0L
        rem[c.productId] = cur - c.qty * sets.toLong()
    }
}

internal fun posMaxSetsForBundle(bundle: Bundle, productsById: Map<String, Product>, rem: Map<String, Long>): Int {
    var max = Int.MAX_VALUE
    for (c in bundle.components) {
        val p = productsById[c.productId] ?: return 0
        if (p.stock == null) continue
        if (c.qty <= 0L) continue
        val r = rem[c.productId] ?: 0L
        val sets = (r / c.qty).toInt().coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE)
        max = minOf(max, sets)
    }
    return max.coerceAtLeast(0)
}

internal fun posClampBundleCart(
    pc: Map<String, Int>,
    bc: Map<String, Int>,
    products: List<Product>,
    bundles: List<Bundle>,
): Map<String, Int> {
    val bundleMap = bundles.associateBy { it.id }
    val productsById = products.associateBy { it.id }
    val out = bc.filterKeys { bundleMap.containsKey(it) }.filterValues { it > 0 }.toMutableMap()
    var changed = true
    while (changed) {
        changed = false
        val rem = posRemainingAfterSingles(products, pc)
        val sortedIds = out.keys.sorted()
        for (bid in sortedIds) {
            val q = out[bid] ?: continue
            val b = bundleMap[bid] ?: continue
            val maxS = posMaxSetsForBundle(b, productsById, rem)
            val clamped = q.coerceAtMost(maxS)
            if (clamped != q) {
                out[bid] = clamped
                changed = true
            }
            posSubtractBundleConsumption(rem, b, productsById, clamped)
        }
    }
    return out.filterValues { it > 0 }
}

internal fun posMaxQtyForBundle(
    bundleId: String,
    pc: Map<String, Int>,
    bc: Map<String, Int>,
    products: List<Product>,
    bundles: List<Bundle>,
): Int {
    val productsById = products.associateBy { it.id }
    val bundleMap = bundles.associateBy { it.id }
    val b = bundleMap[bundleId] ?: return 0
    val rem = posRemainingAfterSingles(products, pc)
    for ((otherId, q) in bc) {
        if (otherId == bundleId) continue
        val ob = bundleMap[otherId] ?: continue
        posSubtractBundleConsumption(rem, ob, productsById, q)
    }
    return posMaxSetsForBundle(b, productsById, rem)
}

internal fun posMaxProductQtyAllowedInSingles(
    productId: String,
    pc: Map<String, Int>,
    bc: Map<String, Int>,
    products: List<Product>,
    bundles: List<Bundle>,
): Int {
    val productsById = products.associateBy { it.id }
    val bundleMap = bundles.associateBy { it.id }
    val p = productsById[productId] ?: return 0
    if (p.stock == null) return Int.MAX_VALUE
    val rem = posRemainingAfterSingles(products, pc)
    for ((bid, q) in bc) {
        val b = bundleMap[bid] ?: continue
        posSubtractBundleConsumption(rem, b, productsById, q)
    }
    return (rem[productId] ?: 0L).toInt().coerceIn(0, Int.MAX_VALUE)
}

internal fun posExpandBundleCart(bc: Map<String, Int>, bundles: List<Bundle>): Map<String, Long> {
    val bm = bundles.associateBy { it.id }
    val acc = mutableMapOf<String, Long>()
    for ((bid, sets) in bc) {
        val b = bm[bid] ?: continue
        if (sets <= 0) continue
        for (c in b.components) {
            acc[c.productId] = (acc[c.productId] ?: 0L) + c.qty * sets.toLong()
        }
    }
    return acc
}

internal fun posMergeStockDeductions(pc: Map<String, Int>, bundleExpanded: Map<String, Long>): Map<String, Long> {
    val m = bundleExpanded.toMutableMap()
    pc.forEach { (pid, q) ->
        if (q <= 0) return@forEach
        m[pid] = (m[pid] ?: 0L) + q.toLong()
    }
    return m
}

internal fun posBuildCheckoutLines(
    pc: Map<String, Int>,
    bc: Map<String, Int>,
    products: List<Product>,
    bundles: List<Bundle>,
): List<SaleCheckoutLine> {
    val pm = products.associateBy { it.id }
    val bm = bundles.associateBy { it.id }
    val lines = mutableListOf<SaleCheckoutLine>()
    pc.forEach { (id, qty) ->
        val p = pm[id] ?: return@forEach
        if (qty <= 0) return@forEach
        lines.add(
            SaleCheckoutLine.Product(
                productId = id,
                qty = qty,
                unitPrice = p.price,
                lineSubtotal = (p.price * qty.toLong()).coerceAtLeast(0L),
            ),
        )
    }
    bc.forEach { (id, qty) ->
        val b = bm[id] ?: return@forEach
        if (qty <= 0) return@forEach
        lines.add(
            SaleCheckoutLine.Bundle(
                bundleId = id,
                qty = qty,
                unitPrice = b.price,
                lineSubtotal = (b.price * qty.toLong()).coerceAtLeast(0L),
            ),
        )
    }
    return lines
}

internal fun posValidateStockForCheckout(deductions: Map<String, Long>, products: List<Product>): Boolean {
    val pm = products.associateBy { it.id }
    for ((pid, need) in deductions) {
        val p = pm[pid] ?: return false
        val s = p.stock ?: continue
        if (need > s) return false
    }
    return true
}

internal fun posNormalizeComponents(products: List<Product>, components: List<BundleComponent>): List<BundleComponent>? {
    val ids = products.map { it.id }.toSet()
    if (components.isEmpty()) return null
    val out = mutableListOf<BundleComponent>()
    for (c in components) {
        if (c.productId !in ids) return null
        out.add(BundleComponent(c.productId, c.qty.coerceAtLeast(1L)))
    }
    return out
}

internal fun posProductReferencedByBundles(productId: String, bundles: List<Bundle>): Boolean =
    bundles.any { b -> b.components.any { it.productId == productId } }

internal fun posValidateBundleComponents(products: List<Product>, bundle: Bundle): Boolean =
    posNormalizeComponents(products, bundle.components) != null
