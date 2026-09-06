package com.lambliver.stallpos.data

/** Client-owned receipt prefix. Never emit reserved `A` (legacy devices keep it). */
internal fun receiptShortCode(deviceId: String, reserved: Set<String> = setOf("A")): String {
    val hex = deviceId.replace("-", "").uppercase()
    for (n in 5..8) {
        val code = hex.takeLast(n)
        if (code.isNotEmpty() && code !in reserved) return code
    }
    return hex.takeLast(8).ifBlank { "X" } + "1"
}
