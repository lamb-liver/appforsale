package com.lambliver.stallpos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lambliver.stallpos.data.FakePosPersistence
import com.lambliver.stallpos.domain.CheckoutSheetPricingSnapshot
import com.lambliver.stallpos.domain.PosUiState
import com.lambliver.stallpos.domain.LastCheckout
import com.lambliver.stallpos.domain.PaymentMethod
import com.lambliver.stallpos.domain.SaleRecord
import com.lambliver.stallpos.domain.SyncUiState
import com.lambliver.stallpos.domain.SyncUiStatus
import com.lambliver.stallpos.ui.PosViewModel
import com.lambliver.stallpos.ui.runIntegrityCheck
import com.lambliver.stallpos.ui.pos.PosSheetOverlay
import com.lambliver.stallpos.ui.pos.PosUiEvent
import com.lambliver.stallpos.ui.pos.toSheetOverlayOrNull
import com.lambliver.stallpos.ui.pos.attentionText
import com.lambliver.stallpos.ui.pos.blockedReasonText
import com.lambliver.stallpos.ui.pos.checkoutFollowUpText
import com.lambliver.stallpos.ui.pos.displayText
import com.lambliver.stallpos.ui.pos.lastSyncText
import com.lambliver.stallpos.ui.pos.requiresNonCashVoidWarning
import kotlinx.collections.immutable.persistentListOf
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
        assertEquals(PosSheetOverlay.LocalOperations, PosUiEvent.ShowLocalOperationsSheet.toSheetOverlayOrNull())
        assertEquals(PosSheetOverlay.Sponsor, PosUiEvent.ShowSponsorSheet.toSheetOverlayOrNull())
        assertEquals(PosSheetOverlay.Settings, PosUiEvent.ShowSettingsSheet.toSheetOverlayOrNull())
        assertNull(PosUiEvent.BeginCheckout.toSheetOverlayOrNull())
    }

    @Test
    fun syncState_allPlaceholdersHaveDeterministicText() {
        assertEquals("還沒開雲端", SyncUiState.LocalOnly.displayText())
        assertEquals("連線中…", SyncUiState(SyncUiStatus.LOADING).displayText())
        assertEquals("已登入，還沒上傳過", SyncUiState(SyncUiStatus.SYNCED).displayText())
        assertEquals("已上傳", SyncUiState(SyncUiStatus.SYNCED, lastSyncedAtMillis = 1L).displayText())
        assertEquals(null, SyncUiState.LocalOnly.lastSyncText())
        assertEquals(null, SyncUiState.LocalOnly.attentionText())
        assertEquals(null, SyncUiState(SyncUiStatus.SYNCED, lastSyncedAtMillis = 1L).attentionText())
        assertEquals("還沒成功上傳過", SyncUiState(SyncUiStatus.SYNCED).lastSyncText())
        assertEquals("12 筆還沒上傳", SyncUiState(SyncUiStatus.PENDING, pendingCount = 12).displayText())
        assertEquals("12 筆還沒上傳", SyncUiState(SyncUiStatus.PENDING, pendingCount = 12).attentionText())
        assertEquals(
            "結帳完成，還有 12 筆沒上傳。",
            SyncUiState(SyncUiStatus.PENDING, pendingCount = 12, transientFailureStreak = 3).checkoutFollowUpText(),
        )
        assertEquals("2 筆上傳失敗", SyncUiState(SyncUiStatus.BLOCKED, blockedCount = 2).displayText())
        assertEquals("2 筆上傳失敗", SyncUiState(SyncUiStatus.BLOCKED, blockedCount = 2).attentionText())
        assertEquals(
            "這支手機已被換掉，請用現在登入的那支。",
            SyncUiState(SyncUiStatus.BLOCKED, blockedCount = 1, blockedCode = "DEVICE_RETIRED").blockedReasonText(),
        )
        assertEquals("網路錯誤", SyncUiState(SyncUiStatus.ERROR, message = "網路錯誤").displayText())
    }

    @Test
    fun digitalLastCheckout_requiresExternalRefundWarning() {
        val sale = SaleRecord("sale", 1, "d", 10, 0, 10, emptyMap(), paymentMethod = PaymentMethod.LINE_PAY)
        val state = PosUiState(
            salesLog = persistentListOf(sale),
            lastCheckout = LastCheckout("sale", 1, 10, emptyMap(), emptyMap(), emptyMap()),
        )
        assertEquals(true, state.requiresNonCashVoidWarning())
        assertEquals(false, state.copy(salesLog = persistentListOf(sale.copy(paymentMethod = PaymentMethod.CASH))).requiresNonCashVoidWarning())
    }

    @Test
    fun productCost_survivesAddAndEditIntents() = runTest(testDispatcher) {
        val vm = PosViewModel(app, FakePosPersistence())
        advanceUntilIdle()
        try {
            vm.onEvent(com.lambliver.stallpos.domain.PosEvent.AddProduct("A", 100, stock = 3, cost = 40))
            advanceUntilIdle()
            val product = vm.uiState.value.products.single()
            assertEquals(40L, product.cost)
            vm.onEvent(com.lambliver.stallpos.domain.PosEvent.UpdateProduct(product.id, "A", 100, stock = 3, cost = null))
            advanceUntilIdle()
            assertNull(vm.uiState.value.products.single().cost)
        } finally {
            vm.clearForTest()
            advanceUntilIdle()
        }
    }

    @Test
    fun beginCheckout_setsCheckoutSheetSnapshot() = runTest(testDispatcher) {
        val vm = PosViewModel(app, FakePosPersistence())
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

    @Test
    fun setProductActive_marksInactive() = runTest(testDispatcher) {
        val vm = PosViewModel(app, FakePosPersistence())
        advanceUntilIdle()
        try {
            vm.onEvent(com.lambliver.stallpos.domain.PosEvent.AddProduct("A", 100))
            advanceUntilIdle()
            val id = vm.uiState.value.products.single().id
            vm.onEvent(com.lambliver.stallpos.domain.PosEvent.SetProductActive(id, false))
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.products.single().isActive)
        } finally {
            vm.clearForTest()
            advanceUntilIdle()
        }
    }

    @Test
    fun integrityCheck_emptyStore_isOk() = runTest(testDispatcher) {
        val vm = PosViewModel(app, FakePosPersistence())
        advanceUntilIdle()
        try {
            vm.runIntegrityCheck()
            advanceUntilIdle()
            assertEquals("資料正常", vm.checkDataMessage.value)
        } finally {
            vm.clearForTest()
            advanceUntilIdle()
        }
    }

    @Test
    fun snoozeBackupReminder_hidesBanner() = runTest(testDispatcher) {
        val vm = PosViewModel(app, FakePosPersistence())
        advanceUntilIdle()
        try {
            vm.backupReminderVisible.value = true
            vm.snoozeBackupReminder()
            assertEquals(false, vm.backupReminderVisible.value)
        } finally {
            vm.clearForTest()
            advanceUntilIdle()
        }
    }
}
