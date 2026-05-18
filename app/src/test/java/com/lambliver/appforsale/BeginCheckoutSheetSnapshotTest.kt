package com.lambliver.appforsale

import com.lambliver.appforsale.domain.CheckoutSheetPricingSnapshot
import com.lambliver.appforsale.domain.PosUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [PosViewModel.beginCheckoutSheet] 鎖定邏輯：寫入 [PosUiState.checkoutSheetSnapshot] 後，
 * [CheckoutSheetPricingSnapshot.surfaceReceivable] 與主畫面預覽一致。
 */
class BeginCheckoutSheetSnapshotTest {

    @Test
    fun lockedFrom_matchesCheckoutSurfaceReceivablePreview() {
        val ui = PosUiState(
            subtotal = 100L,
            checkoutCustomDigits = "10",
            checkoutDiscountRequested = 40L,
        )
        val snap = CheckoutSheetPricingSnapshot.lockedFrom(ui)

        assertEquals(100L, snap.catalogSubtotal)
        assertEquals(-30L, snap.paymentAdjustment)
        assertEquals(ui.checkoutSurfaceReceivablePreview, snap.surfaceReceivable)
        assertEquals(70L, snap.surfaceReceivable)
    }

    @Test
    fun uiState_afterBeginCheckoutSheet_hasExpectedSurfaceReceivable() {
        val ui = PosUiState(
            subtotal = 30L,
            checkoutCustomDigits = "20",
            checkoutDiscountRequested = 0L,
        )
        val written = ui.copy(checkoutSheetSnapshot = CheckoutSheetPricingSnapshot.lockedFrom(ui))

        val snap = written.checkoutSheetSnapshot
        assertNotNull(snap)
        assertEquals(50L, snap!!.surfaceReceivable)
        assertEquals(written.checkoutSurfaceReceivablePreview, snap.surfaceReceivable)
    }
}
