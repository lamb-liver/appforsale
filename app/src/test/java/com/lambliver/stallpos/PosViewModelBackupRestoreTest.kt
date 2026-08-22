package com.lambliver.stallpos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lambliver.stallpos.data.FakePosPersistence
import com.lambliver.stallpos.data.PosPersistSnapshot
import com.lambliver.stallpos.data.PosStore
import com.lambliver.stallpos.data.encodePosCartJson
import com.lambliver.stallpos.data.encodeProducts
import com.lambliver.stallpos.domain.PosCart
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.domain.ReversalReason
import com.lambliver.stallpos.domain.SaleRecord
import com.lambliver.stallpos.domain.SaleReversal
import com.lambliver.stallpos.domain.activeSales
import com.lambliver.stallpos.ui.BackupRestoreResult
import com.lambliver.stallpos.ui.PosViewModel
import com.lambliver.stallpos.ui.restoreBackupFromText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

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

    @Test
    fun backupRoundTrip_preservesSaleAndReversalIds() = runTest(testDispatcher) {
        val sale = SaleRecord(
            id = "sale-1",
            tsMillis = 1L,
            dateKey = "2026-08-22",
            subtotal = 10L,
            discount = 0L,
            total = 10L,
            cartSnapshot = emptyMap(),
        )
        val reversal = SaleReversal("reversal-1", sale.id, 2L, ReversalReason.UNDO_LAST_CHECKOUT)
        val source = FakePosPersistence(PosPersistSnapshot(salesLog = listOf(sale), reversalLog = listOf(reversal)))
        val restored = FakePosPersistence()

        restored.restoreFullBackupJson(source.exportFullBackupJson()).getOrThrow()

        assertEquals("sale-1", restored.salesLogFlow.first().single().id)
        assertEquals("reversal-1", restored.reversalLogFlow.first().single().id)
    }

    @Test
    fun v12Backup_upgradeUndoAndV3RoundTrip_preserveIdentityRevenueAndStock() = runTest(testDispatcher) {
        val legacySale =
            """{"ts":10,"date":"2026-08-22","subtotal":100,"discount":0,"total":100,"cart":{"p1":2},"bundles":{},"paymentMethod":"CASH","tipAmount":20,"lines":[],"stockDeductions":{"p1":2}}"""
        val legacyLast =
            """{"ts":10,"total":120,"cart":{"p1":2},"bundles":{},"stockDeductions":{"p1":2}}"""
        val payload = JSONObject()
            .put("payloadSchema", 2)
            .put("products_json", encodeProducts(listOf(Product("p1", "A", 50L, stock = 3L))))
            .put("categories_json", "[]")
            .put("bundle_categories_json", "[]")
            .put("bundles_json", "[]")
            .put("cart_json", encodePosCartJson(PosCart()))
            .put("sales_log_json", "[$legacySale]")
            .put("last_checkout_json", legacyLast)
            .put("total_sales", 120L)
            .put("tx_count", 1L)
        val v12Backup = JSONObject()
            .put("format", PosStore.BACKUP_FORMAT_ID)
            .put("schemaVersion", 2)
            .put("exportedAtMillis", 4_000_000_000L)
            .put("payload", payload)
            .toString()
        val upgraded = FakePosPersistence()

        upgraded.restoreFullBackupJson(v12Backup).getOrThrow()
        val migratedSaleId = upgraded.salesLogFlow.first().single().id
        assertEquals(migratedSaleId, upgraded.lastCheckoutFlow.first()!!.saleId)
        upgraded.undoLastCheckout()
        val afterUndo = upgraded.snapshot.first()
        val v3Backup = upgraded.exportFullBackupJson()
        val restored = FakePosPersistence()

        restored.restoreFullBackupJson(v3Backup).getOrThrow()
        val roundTripped = restored.snapshot.first()
        assertEquals(migratedSaleId, roundTripped.salesLog.single().id)
        assertEquals(afterUndo.reversalLog.single(), roundTripped.reversalLog.single())
        assertEquals(0L, activeSales(roundTripped.salesLog, roundTripped.reversalLog).sumOf { it.total + it.tipAmount })
        assertEquals(5L, roundTripped.products.single().stock)
        assertEquals(PosCart(products = mapOf("p1" to 2)), roundTripped.cart)
    }
}
