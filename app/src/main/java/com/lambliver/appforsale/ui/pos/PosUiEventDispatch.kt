package com.lambliver.appforsale.ui.pos

/**
 * [PosUiEvent] 對 Sheet overlay 的對應（可單元測試，與 [com.lambliver.appforsale.ui.PosApp] 行為一致）。
 */
internal fun PosUiEvent.toSheetOverlayOrNull(): PosSheetOverlay? = when (this) {
    PosUiEvent.ShowDiscountSheet -> PosSheetOverlay.Discount
    PosUiEvent.ShowDashboardSheet -> PosSheetOverlay.Dashboard
    PosUiEvent.ShowSponsorSheet -> PosSheetOverlay.Sponsor
    else -> null
}
