package com.lambliver.stallpos.ui.theme

import androidx.compose.ui.graphics.Color

// ── Linear-inspired tokens, tuned for outdoor stall contrast ───────────────
val LinearCanvas = Color(0xFF0B0C10)
val LinearSurface1 = Color(0xFF141518)
val LinearSurface2 = Color(0xFF1C1D22)
val LinearSurface3 = Color(0xFF24252B)
val LinearSurface4 = Color(0xFF2A2B32)
val LinearHairline = Color(0xFF3E4048)
val LinearHairlineStrong = Color(0xFF5A5C66)
val LinearInk = Color(0xFFF7F8F8)
val LinearInkMuted = Color(0xFFD0D6E0)
val LinearInkSubtle = Color(0xFFB4B8C0)
val LinearPrimary = Color(0xFF7B85F0)
val LinearOnPrimary = Color(0xFFFFFFFF)
val LinearPrimaryHover = Color(0xFF828FFF)
val LinearPrimaryFocus = Color(0xFF5E69D1)
val LinearBrandSecure = Color(0xFF7A7FAD)
val LinearSemanticSuccess = Color(0xFF27A644)
val LinearPrimaryContainerDark = Color(0xFF252942)
val LinearOnPrimaryContainer = Color(0xFFDEE0FF)
val LinearTertiaryContainer = Color(0xFF343A5C)
val LinearOnTertiaryContainer = Color(0xFFE0E4FF)

/** 商品磁磚：深色塊 + 白字，戶外可讀 */
val ProductTilePalette = listOf(
    Color(0xFFB71C1C), Color(0xFF880E4F), Color(0xFF4A148C), Color(0xFF1A237E),
    Color(0xFF004D40), Color(0xFFE65100), Color(0xFF33691E), Color(0xFFAD1457),
)

/** 套組磁磚：同樣深度，避免螢光色把白字洗掉 */
val BundleTilePalette = listOf(
    Color(0xFFB71C1C), Color(0xFF880E4F), Color(0xFF6A1B9A), Color(0xFF0D47A1),
    Color(0xFF00695C), Color(0xFFE65100), Color(0xFF2E7D32), Color(0xFFC2185B),
)

/** 主畫面收款按鈕（與商品卡暖色調一致） */
val PosCheckoutOrange = Color(0xFFF97316)
