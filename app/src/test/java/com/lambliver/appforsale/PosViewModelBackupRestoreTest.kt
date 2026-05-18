package com.lambliver.appforsale

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lambliver.appforsale.data.FakePosPersistence
import com.lambliver.appforsale.data.PosPersistSnapshot
import com.lambliver.appforsale.domain.PosCart
import com.lambliver.appforsale.domain.Product
import com.lambliver.appforsale.ui.BackupRestoreResult
import com.lambliver.appforsale.ui.PosViewModel
import com.lambliver.appforsale.ui.restoreBackupFromText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PosViewModelBackupRestoreTest {

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
    fun restoreBackupFromText_success_syncsCartAndClearsCheckoutUi() = runTest(testDispatcher) {
        val persist = FakePosPersistence(
            PosPersistSnapshot(
                products = listOf(Product("p1", "A", 10L)),
                cart = PosCart(products = mapOf("p1" to 3)),
            ),
        )
        val vm = PosViewModel(app, persist)
        advanceUntilIdle()
        vm.onCheckoutCustomDigitsChange("99")
        val json = persist.exportFullBackupJson()

        val outcome = vm.restoreBackupFromText(json)
        advanceUntilIdle()

        assertTrue(outcome is BackupRestoreResult.Success)
        assertEquals(mapOf("p1" to 3), vm.uiState.value.cart.products)
        assertEquals("", vm.uiState.value.checkoutCustomDigits)
        vm.clearForTest()
    }

    @Test
    fun restoreBackupFromText_invalidJson_returnsRestoreFailed() = runTest(testDispatcher) {
        val vm = PosViewModel(app, FakePosPersistence())
        advanceUntilIdle()

        val outcome = vm.restoreBackupFromText("{not backup}")
        assertTrue(outcome is BackupRestoreResult.RestoreFailed)
        vm.clearForTest()
    }
}
