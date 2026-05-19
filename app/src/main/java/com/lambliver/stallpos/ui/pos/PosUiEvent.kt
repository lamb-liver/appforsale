package com.lambliver.stallpos.ui.pos

import com.lambliver.stallpos.domain.Bundle
import com.lambliver.stallpos.domain.BundleCategory
import com.lambliver.stallpos.domain.Category
import com.lambliver.stallpos.domain.DialogState
import com.lambliver.stallpos.domain.Product

/** PosMainScreen 發出的 UI 意圖；由 [com.lambliver.stallpos.ui.PosApp] 轉發至 ViewModel 或本地 overlay。 */
sealed interface PosUiEvent {
    data object ShowDiscountSheet : PosUiEvent
    data object ShowDashboardSheet : PosUiEvent
    data object ShowSponsorSheet : PosUiEvent
    data object RequestExportCsv : PosUiEvent
    data object RequestBackupJson : PosUiEvent
    data object RequestRestoreJson : PosUiEvent
    data class ProductLongPress(val product: Product) : PosUiEvent
    data class CategoryLongPress(val category: Category) : PosUiEvent
    data class BundleLongPress(val bundle: Bundle) : PosUiEvent
    data class BundleCategoryLongPress(val category: BundleCategory) : PosUiEvent
    data object UndoCheckout : PosUiEvent
    data object ClearCart : PosUiEvent
    data object BeginCheckout : PosUiEvent
    data class SetProductCartQty(val productId: String, val qty: Int) : PosUiEvent
    data class SetBundleCartQty(val bundleId: String, val qty: Int) : PosUiEvent
    data class CheckoutCustomDigitsChange(val digits: String) : PosUiEvent
    data object ClearCheckoutDiscount : PosUiEvent
    data class ShowDialog(val state: DialogState) : PosUiEvent
}
