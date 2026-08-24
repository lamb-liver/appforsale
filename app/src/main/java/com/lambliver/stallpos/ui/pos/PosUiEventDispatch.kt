package com.lambliver.stallpos.ui.pos

import com.lambliver.stallpos.domain.PaymentMethod
import com.lambliver.stallpos.domain.PosUiState

/**
 * [PosUiEvent] 對 Sheet overlay 的對應（可單元測試，與 [com.lambliver.stallpos.ui.PosApp] 行為一致）。
 */
internal fun PosUiEvent.toSheetOverlayOrNull(): PosSheetOverlay? = when (this) {
    PosUiEvent.ShowDiscountSheet -> PosSheetOverlay.Discount
    PosUiEvent.ShowDashboardSheet -> PosSheetOverlay.Dashboard
    PosUiEvent.ShowLocalOperationsSheet -> PosSheetOverlay.LocalOperations
    PosUiEvent.ShowSponsorSheet -> PosSheetOverlay.Sponsor
    else -> null
}

internal fun PosUiState.requiresNonCashVoidWarning(): Boolean {
    val saleId = lastCheckout?.saleId ?: return false
    return salesLog.firstOrNull { it.id == saleId }?.paymentMethod == PaymentMethod.DIGITAL
}
