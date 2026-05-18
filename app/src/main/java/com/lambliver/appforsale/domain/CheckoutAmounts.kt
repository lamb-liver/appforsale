package com.lambliver.appforsale.domain

/**
 * 結帳金額單一模組：以型別區分「目錄小計／淨調整／應收款」，避免裸 [Long] 與多義 `total`。
 *
 * 詞彙對照見 [CONTEXT.md]「結帳金額型別」；流程見 `docs/phase-a-checkout-money-flow.md`。
 */
object CheckoutAmounts {

    /** 購物車目錄小計（僅 Σ 行內售價×數量；不含加價／折讓）。 */
    @JvmInline
    value class CatalogSubtotal(val cents: Long)

    /**
     * 加價／折讓淨調整（可為負）。
     * 來源：`customAmount − discountApplied`（折扣已折進此淨值，不落 [SaleRecord.discount]）。
     */
    @JvmInline
    value class NetPaymentAdjustment(val cents: Long)

    /** 客人應付（無小費）；建立時即 clamp 為 non-negative。 */
    @JvmInline
    value class AmountDue private constructor(val cents: Long) {
        companion object {
            fun clamped(raw: Long): AmountDue = AmountDue(raw.coerceAtLeast(0L))
        }
    }

    /** 三金額之組合事實；[amountDue] 由公式唯一派生。 */
    data class Breakdown(
        val catalogSubtotal: CatalogSubtotal,
        val netAdjustment: NetPaymentAdjustment,
    ) {
        val amountDue: AmountDue =
            amountDue(catalogSubtotal, netAdjustment)

        /**
         * 寫入 [SaleRecord] 之三欄。
         * 持久化 [SaleRecord.subtotal] 鍵名保留歷史相容，值為 [amountDue]（非 [catalogSubtotal]）。
         */
        fun toSaleRecordAmountFields(): SaleRecordAmountFields {
            val dueCents = amountDue(this).cents
            return SaleRecordAmountFields(
                persistedSubtotal = dueCents,
                discount = 0L,
                total = dueCents,
            )
        }
    }

    /** BottomSheet／[PosEvent.ConfirmCheckout] 鎖定之定價快照（型別化）。 */
    data class ConfirmationSnapshot(
        val catalogSubtotal: CatalogSubtotal,
        val netAdjustment: NetPaymentAdjustment,
        val surfaceAmountDue: AmountDue,
    ) {
        val breakdown: Breakdown =
            Breakdown(catalogSubtotal, netAdjustment)
    }

    sealed interface ReconcileResult {
        data class Accepted(val breakdown: Breakdown) : ReconcileResult {
            val amountDue: AmountDue get() = breakdown.amountDue
        }

        data class Rejected(
            val reason: ReconcileReason,
            /** 給使用者看的簡短訊息（Toast）。 */
            val message: String,
        ) : ReconcileResult
    }

    enum class ReconcileReason {
        /** 確認當下即時目錄小計 ≠ 快照目錄小計（競態／購物車已變）。 */
        CartChanged,
        /** 快照應收 ≠ `catalog + net` 公式結果。 */
        SurfaceMismatch,
    }

    fun amountDue(
        catalogSubtotal: CatalogSubtotal,
        netAdjustment: NetPaymentAdjustment,
    ): AmountDue = AmountDue.clamped(catalogSubtotal.cents + netAdjustment.cents)

    fun amountDue(breakdown: Breakdown): AmountDue = breakdown.amountDue

    /**
     * 對帳入口：驗證即時目錄小計與快照一致，且快照應收與公式交叉驗證。
     *
     * @param liveCatalogSubtotal 確認當下 VM 聚合之目錄小計（如 [PosUiState.subtotal]）。
     * @param confirmation 事件／BottomSheet 送出之快照三數。
     */
    fun reconcile(
        liveCatalogSubtotal: CatalogSubtotal,
        confirmation: ConfirmationSnapshot,
    ): ReconcileResult {
        if (confirmation.catalogSubtotal != liveCatalogSubtotal) {
            return ReconcileResult.Rejected(
                reason = ReconcileReason.CartChanged,
                message = "購物車內容已變更，請關閉後再結帳",
            )
        }
        val computed = amountDue(confirmation.catalogSubtotal, confirmation.netAdjustment)
        if (computed != confirmation.surfaceAmountDue) {
            return ReconcileResult.Rejected(
                reason = ReconcileReason.SurfaceMismatch,
                message = "金額資料不一致，請返回後再試",
            )
        }
        return ReconcileResult.Accepted(
            Breakdown(confirmation.catalogSubtotal, confirmation.netAdjustment),
        )
    }
}

/**
 * 對帳通過後寫入 [SaleRecord] 之三金額欄（本通路 `discount` 恒為 0）。
 *
 * @param persistedSubtotal 對應 JSON／[SaleRecord.subtotal]；值為應收款（= [total]）。
 */
data class SaleRecordAmountFields(
    val persistedSubtotal: Long,
    val discount: Long,
    val total: Long,
)
