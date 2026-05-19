package com.lambliver.stallpos.domain

/** SAF 匯出／匯入目標（平台無關，Android 端以 `Uri.parse(value)` 使用）。 */
@JvmInline
value class DocumentTarget(val value: String)
