package com.lambliver.appforsale.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

// ════════════════════════════════════════════════════════════════════════════
// Dialog 有限狀態機（替代多重 Boolean 旗標）
// ════════════════════════════════════════════════════════════════════════════

/**
 * BottomSheet／確認結帳唯一使用的定價快照（目錄小計 + 加價／折讓淨調整）。
 * [surfaceReceivable] 僅由公式派生，避免呼叫端拼錯應收款。
 */
@Immutable
data class CheckoutSheetPricingSnapshot(
    val catalogSubtotal: Long,
    val paymentAdjustment: Long,
) {
    val surfaceReceivable: Long get() =
        CheckoutAmounts.amountDue(
            CheckoutAmounts.CatalogSubtotal(catalogSubtotal),
            CheckoutAmounts.NetPaymentAdjustment(paymentAdjustment),
        ).cents

    /** 對帳層 [CheckoutAmounts.ConfirmationSnapshot]；[surfaceAmountDue] 使用目前 [surfaceReceivable]。 */
    fun toConfirmationSnapshot(): CheckoutAmounts.ConfirmationSnapshot =
        CheckoutAmounts.ConfirmationSnapshot(
            catalogSubtotal = CheckoutAmounts.CatalogSubtotal(catalogSubtotal),
            netAdjustment = CheckoutAmounts.NetPaymentAdjustment(paymentAdjustment),
            surfaceAmountDue = CheckoutAmounts.AmountDue.clamped(surfaceReceivable),
        )

    companion object {
        /** 主畫面按「結帳」當下，鎖定目錄小計與淨調整（與 [PosUiState.checkoutSurfaceReceivablePreview] 同源公式）。 */
        fun lockedFrom(ui: PosUiState): CheckoutSheetPricingSnapshot =
            CheckoutSheetPricingSnapshot(
                catalogSubtotal = ui.subtotal,
                paymentAdjustment = ui.checkoutPaymentAdjustment,
            )

        fun from(confirmation: CheckoutAmounts.ConfirmationSnapshot): CheckoutSheetPricingSnapshot =
            CheckoutSheetPricingSnapshot(
                catalogSubtotal = confirmation.catalogSubtotal.cents,
                paymentAdjustment = confirmation.netAdjustment.cents,
            )
    }
}

sealed class DialogState {
    data object None : DialogState()
    data object AddProduct : DialogState()
    data class EditProduct(val product: Product) : DialogState()
    data class DeleteProduct(val product: Product) : DialogState()
    data object AddBundle : DialogState()
    data class EditBundle(val bundle: Bundle) : DialogState()
    data class DeleteBundle(val bundle: Bundle) : DialogState()
}

// ════════════════════════════════════════════════════════════════════════════
// 畫面狀態（Single Source of Truth）
// 所有集合皆使用不可變型別，完整支援 Kotlin 2.0 Strong Skipping
// ════════════════════════════════════════════════════════════════════════════

@Immutable
data class PosUiState(
    val isLoading: Boolean = true,
    val products: ImmutableList<Product> = persistentListOf(),
    val categories: ImmutableList<Category> = persistentListOf(),
    val bundles: ImmutableList<Bundle> = persistentListOf(),
    val bundleCategories: ImmutableList<BundleCategory> = persistentListOf(),
    /**
     * 進行中購物車（唯讀投影）。可寫來源為 ViewModel `posCartMemory`（見 ADR-0003）。
     */
    val cart: PosCart = PosCart(),
    val totalSales: Long = 0L,
    val txCount: Long = 0L,
    val salesLog: ImmutableList<SaleRecord> = persistentListOf(),
    val todaySalesLog: ImmutableList<SaleRecord> = persistentListOf(),
    val lastCheckout: LastCheckout? = null,
    val subtotal: Long = 0L,
    /**
     * 歷史欄位：恒等於 [subtotal]（目錄小計）。
     * 與 [SaleRecord.total]（應收款、不含小費）及 [LastCheckout.total]（應收＋小費）不同義。
     * 詳見 `CONTEXT.md` 與 `docs/phase-a-checkout-money-flow.md`。
     */
    @Deprecated(
        message = "恒等於目錄小計，請改用 subtotal；勿與 SaleRecord.total / LastCheckout.total 混淆",
        replaceWith = ReplaceWith("subtotal"),
    )
    val total: Long = 0L,
    val todayKey: String = "",
    val todaySales: Long = 0L,
    val dialogState: DialogState = DialogState.None,

    /** 自訂金額數字鍵字串（與先前 PosApp rememberSaveable 語意一致，解析失敗視為 0）。 */
    val checkoutCustomDigits: String = "",
    /** 使用者自折扣 Sheet 設定之折扣請求（實際折抵見 [checkoutDiscountApplied]）。 */
    val checkoutDiscountRequested: Long = 0L,
    /** 非 null 表示曾按「結帳」並應鎖定應收款；Dismiss 清空。 */
    val checkoutSheetSnapshot: CheckoutSheetPricingSnapshot? = null,
) {
    val checkoutParsedCustomAmount: Long get() = checkoutCustomDigits.toLongOrNull() ?: 0L
    val checkoutGrandBeforeDiscount: Long get() = subtotal + checkoutParsedCustomAmount
    val checkoutDiscountApplied: Long get() =
        checkoutDiscountRequested.coerceAtMost(checkoutGrandBeforeDiscount.coerceAtLeast(0L))

    /** 對應快照之加價／折讓淨值（custom − discountApplied）；結帳確認時送 [CheckoutSheetPricingSnapshot]。 */
    val checkoutPaymentAdjustment: Long get() = checkoutParsedCustomAmount - checkoutDiscountApplied

    /** 主畫面即時預覽應收（未開 BottomSheet 時與快照無關）。 */
    val checkoutSurfaceReceivablePreview: Long get() =
        CheckoutAmounts.amountDue(
            CheckoutAmounts.CatalogSubtotal(subtotal),
            CheckoutAmounts.NetPaymentAdjustment(checkoutPaymentAdjustment),
        ).cents
}

// ════════════════════════════════════════════════════════════════════════════
// 使用者互動意圖（User Intents / Events）
// ════════════════════════════════════════════════════════════════════════════

sealed interface PosEvent {
    data class SetCartQty(val productId: String, val qty: Int) : PosEvent
    data class SetBundleCartQty(val bundleId: String, val qty: Int) : PosEvent
    data class AddProduct(val name: String, val price: Long, val categoryId: String = "", val stock: Long? = null) : PosEvent
    data class UpdateProduct(val id: String, val name: String, val price: Long, val categoryId: String = "", val stock: Long? = null) : PosEvent
    data class DeleteProduct(val productId: String) : PosEvent
    data class SetProductStock(val productId: String, val stock: Long?) : PosEvent
    data class AddCategory(val name: String) : PosEvent
    data class UpdateCategory(val id: String, val name: String) : PosEvent
    data class DeleteCategory(val id: String) : PosEvent
    data class AddBundle(val name: String, val price: Long, val categoryId: String, val components: List<BundleComponent>) : PosEvent
    data class UpdateBundle(val id: String, val name: String, val price: Long, val categoryId: String, val components: List<BundleComponent>) : PosEvent
    data class DeleteBundle(val bundleId: String) : PosEvent
    data class AddBundleCategory(val name: String) : PosEvent
    data class UpdateBundleCategory(val id: String, val name: String) : PosEvent
    data class DeleteBundleCategory(val id: String) : PosEvent
    /**
     * 使用者確認結帳時送出的唯一金額封包（含 BottomSheet 鎖定的 [CheckoutSheetPricingSnapshot]）。
     */
    data class ConfirmCheckout(
        val pricing: CheckoutSheetPricingSnapshot,
        val paymentMethod: PaymentMethod = PaymentMethod.CASH,
        val tipAmount: Long = 0L,
    ) : PosEvent
    data object UndoCheckout : PosEvent
    data object ClearCart : PosEvent
    data class ShowDialog(val state: DialogState) : PosEvent
    data object DismissDialog : PosEvent
    data class ExportCsv(val target: DocumentTarget) : PosEvent
    data class ExportBackupJson(val target: DocumentTarget) : PosEvent
    data class ImportBackupJson(val target: DocumentTarget) : PosEvent
}
