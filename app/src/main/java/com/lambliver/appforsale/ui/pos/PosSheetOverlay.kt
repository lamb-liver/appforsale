package com.lambliver.appforsale.ui.pos

/** 由 [PosOverlayState] 管理的 Modal Sheet（結帳由 [com.lambliver.appforsale.domain.PosUiState.checkoutSheetSnapshot] 驅動）。 */
internal enum class PosSheetOverlay {
    Discount,
    Dashboard,
    Sponsor,
}
