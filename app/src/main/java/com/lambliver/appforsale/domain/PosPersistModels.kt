package com.lambliver.appforsale.domain

import androidx.compose.runtime.Immutable

@Immutable
data class Category(
    val id: String,
    val name: String,
)

@Immutable
data class Product(
    val id: String,
    val name: String,
    val price: Long,
    val categoryId: String = "",
    /** `null` = 不追蹤庫存（無上限）；非 null = 目前可售數量 */
    val stock: Long? = null,
)

/** 套組專用分類（與一般商品 [Category] 分開） */
@Immutable
data class BundleCategory(
    val id: String,
    val name: String,
)

/**
 * 套組內單一成分（同一 [productId] 可出現多行，結帳展開時各自累加）。
 * [qty] = 每售出 **1 套** 所需該商品數量，須 ≥ 1。
 */
@Immutable
data class BundleComponent(
    val productId: String,
    val qty: Long,
)

/**
 * 套組方案 B：獨立於 [Product]。售價 [price] 允許為 0。
 * 成分僅能指向一般商品，禁止巢狀套組（由 UI／VM 防呆）。
 */
data class Bundle(
    val id: String,
    val name: String,
    val price: Long,
    val categoryId: String = "",
    val components: List<BundleComponent>,
)

/** 購物車：一般品與套組分開；舊版平面 JSON 視為僅 [products] */
data class PosCart(
    val products: Map<String, Int> = emptyMap(),
    val bundles: Map<String, Int> = emptyMap(),
)

/** 付款方式：對帳與報表用；預設 [CASH] 以相容舊版銷售紀錄 */
enum class PaymentMethod {
    CASH,
    DIGITAL,
}

/** 結帳列：一般品或套組（套組 [unitPrice] 可為 0） */
sealed class SaleCheckoutLine {
    abstract val qty: Int
    abstract val unitPrice: Long
    abstract val lineSubtotal: Long

    data class Product(
        val productId: String,
        override val qty: Int,
        override val unitPrice: Long,
        override val lineSubtotal: Long,
    ) : SaleCheckoutLine()

    data class Bundle(
        val bundleId: String,
        override val qty: Int,
        override val unitPrice: Long,
        override val lineSubtotal: Long,
    ) : SaleCheckoutLine()
}

/**
 * 單筆結帳成功之持久化紀錄（追加語意）。
 *
 * **金額欄位（本通路）** — 與 [CONTEXT.md]「結帳金額型別」對照：
 * - [subtotal]／[total]：皆為 **應收款**（`AmountDue`，不含小費）；**不是**購物車目錄小計。
 * - [discount]：恒為 `0`；加價／折讓已折進結帳當下之淨調整，不落此欄。
 * - 目錄小計僅存於結帳列 [checkoutLines] 加總語意，無獨立持久化鍵（避免 JSON 遷移）。
 *
 * 讀取語意化別名：[amountDue]（= [total]）。
 */
data class SaleRecord(
    val tsMillis: Long,
    val dateKey: String,
  /** 持久化鍵名 `subtotal`；值 = 應收款（與 [total] 相同）。≠ `PosUiState.subtotal`（目錄小計）。 */
    val subtotal: Long,
    /** 本通路恒為 0；折讓已併入結帳淨調整。 */
    val discount: Long,
    /** 應收款（不含小費）；與 [subtotal] 同值。 */
    val total: Long,
    val cartSnapshot: Map<String, Int>,
    val bundleCartSnapshot: Map<String, Int> = emptyMap(),
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val tipAmount: Long = 0L,
    val checkoutLines: List<SaleCheckoutLine> = emptyList(),
    val stockDeductions: Map<String, Long> = emptyMap(),
)

/** 應收款（不含小費）；與 [SaleRecord.total] 相同，供報表／對帳閱讀。 */
val SaleRecord.amountDue: Long get() = total

fun SaleRecord.productQtySoldForReport(): Map<String, Long> =
    if (stockDeductions.isNotEmpty()) stockDeductions
    else cartSnapshot.mapValues { it.value.toLong() }

data class LastCheckout(
    val tsMillis: Long,
    val total: Long,
    val productCart: Map<String, Int>,
    val bundleCart: Map<String, Int> = emptyMap(),
    val stockDeductions: Map<String, Long> = emptyMap(),
)
