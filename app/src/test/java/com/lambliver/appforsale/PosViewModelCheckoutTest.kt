package com.lambliver.appforsale

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lambliver.appforsale.data.FakePosPersistence
import com.lambliver.appforsale.data.PosPersistSnapshot
import com.lambliver.appforsale.domain.CheckoutSheetPricingSnapshot
import com.lambliver.appforsale.domain.PaymentMethod
import com.lambliver.appforsale.domain.PosCart
import com.lambliver.appforsale.domain.Product
import com.lambliver.appforsale.ui.PosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel 結帳編排：Coordinator 拒絕、持久化成功、持久化失敗還原購物車。
 * 使用 [FakePosPersistence] + [com.lambliver.appforsale.data.PosPersistence.commitCheckout]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PosViewModelCheckoutTest {

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

    private fun catalogSnapshot() = PosPersistSnapshot(
        products = listOf(Product("p1", "品", 20L, stock = 10L)),
        cart = PosCart(products = mapOf("p1" to 2)),
    )

    private fun runVmTest(
        persist: FakePosPersistence = FakePosPersistence(catalogSnapshot()),
        block: suspend TestScope.(PosViewModel, FakePosPersistence) -> Unit,
    ) = runTest(testDispatcher) {
        val vm = PosViewModel(app, persist)
        advanceUntilIdle()
        try {
            block(vm, persist)
        } finally {
            vm.clearForTest()
            advanceUntilIdle()
        }
    }

    @Test
    fun confirmCheckout_success_clearsCartAndRecordsSale() = runVmTest { vm, persist ->
        vm.onCheckoutClicked(CheckoutSheetPricingSnapshot(40L, 0L))
        advanceUntilIdle()

        assertEquals(PosCart(), persist.cartFlow.first())
        assertEquals(1L, persist.txCountFlow.first())
        assertEquals(40L, persist.totalSalesFlow.first())
        assertTrue(vm.uiState.value.cart.products.isEmpty() && vm.uiState.value.cart.bundles.isEmpty())
        assertEquals(40L, persist.salesLogFlow.first().single().total)
        assertEquals(8L, persist.productsFlow.first().single().stock ?: 0L)
    }

    @Test
    fun confirmCheckout_persistenceFailure_restoresCartMemory() = runVmTest { vm, persist ->
        persist.checkoutThrows = RuntimeException("disk full")

        vm.onCheckoutClicked(CheckoutSheetPricingSnapshot(40L, 0L))
        advanceUntilIdle()

        assertEquals(mapOf("p1" to 2), persist.cartFlow.first().products)
        assertEquals(0L, persist.txCountFlow.first())
        assertEquals("結帳失敗，請再試一次", vm.toastFlow.first())
        assertEquals(mapOf("p1" to 2), vm.uiState.value.cart.products)
    }

    @Test
    fun confirmCheckout_keepsCartUntilPersistenceCompletes() = runVmTest { vm, persist ->
        persist.checkoutThrows = RuntimeException("disk full")

        vm.onCheckoutClicked(CheckoutSheetPricingSnapshot(40L, 0L))
        // 尚未 advanceUntilIdle：結帳協程可能仍在進行，購物車應維持
        assertEquals(mapOf("p1" to 2), vm.uiState.value.cart.products)

        advanceUntilIdle()
        assertEquals(mapOf("p1" to 2), vm.uiState.value.cart.products)
    }

    @Test
    fun confirmCheckout_subtotalRace_doesNotPersistOrClearCart() = runVmTest { vm, persist ->
        vm.onCheckoutClicked(
            CheckoutSheetPricingSnapshot(catalogSubtotal = 99L, paymentAdjustment = 0L),
            paymentMethod = PaymentMethod.CASH,
        )
        advanceUntilIdle()

        assertEquals("購物車內容已變更，請關閉後再結帳", vm.toastFlow.first())
        assertEquals(mapOf("p1" to 2), persist.cartFlow.first().products)
        assertEquals(0L, persist.txCountFlow.first())
        assertEquals(mapOf("p1" to 2), vm.uiState.value.cart.products)
    }

    @Test
    fun confirmCheckout_failure_keepsCheckoutSheetSnapshot() = runVmTest { vm, persist ->
        vm.beginCheckoutSheet()
        val locked = vm.uiState.value.checkoutSheetSnapshot
        assertNotNull(locked)
        persist.checkoutThrows = RuntimeException("disk full")

        vm.onCheckoutClicked(locked!!)
        advanceUntilIdle()

        assertEquals("結帳失敗，請再試一次", vm.toastFlow.first())
        assertEquals(locked, vm.uiState.value.checkoutSheetSnapshot)
    }
}
