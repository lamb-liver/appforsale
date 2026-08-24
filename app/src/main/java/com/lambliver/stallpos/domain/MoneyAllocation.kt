package com.lambliver.stallpos.domain

/** Deterministic largest-remainder allocation; output order matches [weights]. */
internal fun allocateLargestRemainder(total: Long, weights: List<Long>): List<Long> {
    require(total >= 0 && weights.all { it >= 0 })
    if (weights.isEmpty()) return emptyList()
    if (total == 0L) return List(weights.size) { 0L }
    val weightTotal = weights.sum()
    if (weightTotal == 0L) return List(weights.size) { index -> if (index == 0) total else 0L }
    val base = weights.map { total * it / weightTotal }.toMutableList()
    var remaining = total - base.sum()
    val order = weights.indices.sortedWith(
        compareByDescending<Int> { (total * weights[it]) % weightTotal }.thenBy { it },
    )
    var index = 0
    while (remaining-- > 0) {
        base[order[index++ % order.size]]++
    }
    return base
}
