package com.lambliver.stallpos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lambliver.stallpos.domain.CheckoutSheetPricingSnapshot
import com.lambliver.stallpos.domain.PosUiState
import com.lambliver.stallpos.ui.PosViewModel
import com.lambliver.stallpos.ui.pos.PosSheetOverlay
import com.lambliver.stallpos.ui.pos.PosUiEvent
import com.lambliver.stallpos.ui.pos.toSheetOverlayOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class PosUiEventDispatchTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sheetOverlay_mapping_matchesPosAppContract() {
        assertEquals(PosSheetOverlay.Discount, PosUiEvent.ShowDiscountSheet.toSheetOverlayOrNull())
        assertEquals(PosSheetOverlay.Dashboard, PosUiEvent.ShowDashboardSheet.toSheetOverlayOrNull())
        assertEquals(PosSheetOverlay.Sponsor, PosUiEvent.ShowSponsorSheet.toSheetOverlayOrNull())
        assertNull(PosUiEvent.BeginCheckout.toSheetOverlayOrNull())
    }

    @Test
    fun beginCheckout_setsCheckoutSheetSnapshot() = runTest(testDispatcher) {
        val vm = PosViewModel(app)
        advanceUntilIdle()
        try {
            vm.beginCheckoutSheet()
            val snap = vm.uiState.value.checkoutSheetSnapshot
            assertNotNull(snap)
            val ui = PosUiState(
                subtotal = vm.uiState.value.subtotal,
                checkoutCustomDigits = vm.uiState.value.checkoutCustomDigits,
                checkoutDiscountRequested = vm.uiState.value.checkoutDiscountRequested,
            )
            assertEquals(CheckoutSheetPricingSnapshot.lockedFrom(ui).surfaceReceivable, snap!!.surfaceReceivable)
        } finally {
            vm.clearForTest()
            advanceUntilIdle()
        }
    }
}
