package com.lambliver.stallpos

import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.data.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosCheckoutCoordinatorTest {

    private fun pricing(catalogSubtotal: Long, paymentAdjustment: Long) =
        CheckoutSheetPricingSnapshot(catalogSubtotal, paymentAdjustment)

    private fun confirmation(catalogSubtotal: Long, paymentAdjustment: Long) =
        pricing(catalogSubtotal, paymentAdjustment).toConfirmationSnapshot()

    @Test
    fun prepare_userMessage_whenSubtotalRace() {
        val p = Product("p1", "品", 10L, stock = null)
        val r = PosCheckoutCoordinator.prepare(
            currentCatalogSubtotal = 100L,
            confirmation = confirmation(99L, 0L),
            products = listOf(p),
            bundles = emptyList(),
            cart = PosCart(products = mapOf("p1" to 1)),
            dateKey = "2099-01-01",
            paymentMethod = PaymentMethod.CASH,
            tipAmountRaw = 0L,
        )
        val msg = r as PosCheckoutCoordinator.PrepareResult.UserMessage
        assertEquals("購物車內容已變更，請關閉後再結帳", msg.message)
    }

    @Test
    fun prepare_userMessage_whenInsufficientStock() {
        val p = Product("p1", "品", 30L, stock = 1L)
        val r = PosCheckoutCoordinator.prepare(
            currentCatalogSubtotal = 30L,
            confirmation = confirmation(30L, 0L),
            products = listOf(p),
            bundles = emptyList(),
            cart = PosCart(products = mapOf("p1" to 5)),
            dateKey = "2099-01-01",
            paymentMethod = PaymentMethod.DIGITAL,
            tipAmountRaw = 0L,
        )
        val msg = r as PosCheckoutCoordinator.PrepareResult.UserMessage
        assertEquals("庫存不足，請調整購物車後再結帳", msg.message)
    }

    @Test
    fun prepare_userMessage_whenBundleComponentsInvalid() {
        val prod = Product("p1", "品", 10L, stock = 99L)
        val badBundle = Bundle("b1", "壞組", 50L, components = listOf(BundleComponent("missing", 1L)))
        val r = PosCheckoutCoordinator.prepare(
            currentCatalogSubtotal = 50L,
            confirmation = confirmation(50L, 0L),
            products = listOf(prod),
            bundles = listOf(badBundle),
            cart = PosCart(bundles = mapOf("b1" to 1)),
            dateKey = "2099-01-01",
            paymentMethod = PaymentMethod.CASH,
            tipAmountRaw = 0L,
        )
        val msg = r as PosCheckoutCoordinator.PrepareResult.UserMessage
        assertTrue(msg.message.contains("成分異常"))
    }

    @Test
    fun prepare_ready_requestMatchesStoreContract() {
        val prod = Product("p1", "品", 20L, stock = 5L)
        val cart = PosCart(products = mapOf("p1" to 2))
        val r = PosCheckoutCoordinator.prepare(
            currentCatalogSubtotal = 40L,
            confirmation = confirmation(40L, -10L),
            products = listOf(prod),
            bundles = emptyList(),
            cart = cart,
            dateKey = "2026-05-01",
            paymentMethod = PaymentMethod.CASH,
            tipAmountRaw = -99L,
        )
        val ok = r as PosCheckoutCoordinator.PrepareResult.Ready
        val i = ok.request
        assertEquals(cart.products, i.productCart)
        assertEquals(cart.bundles, i.bundleCart)
        assertEquals(30L, i.subtotal)
        assertEquals(0L, i.discount)
        assertEquals(30L, i.total)
        assertEquals("2026-05-01", i.dateKey)
        assertEquals(PaymentMethod.CASH, i.paymentMethod)
        assertEquals(0L, i.tipAmount)
        assertEquals(mapOf("p1" to 2L), i.stockDeductions)
        assertEquals(1, i.checkoutLines.size)
        val line = i.checkoutLines[0] as SaleCheckoutLine.Product
        assertEquals("p1", line.productId)
        assertEquals(2, line.qty)
        assertEquals(40L, line.lineSubtotal)
    }

    @Test
    fun prepare_ready_mergedDeductions_forBundleAndSinglesShareStock() {
        val prod = Product("p1", "品", 10L, stock = 10L)
        val bun = Bundle("b1", "組", 25L, components = listOf(BundleComponent("p1", 2L)))
        val cart = PosCart(products = mapOf("p1" to 1), bundles = mapOf("b1" to 1))
        val r = PosCheckoutCoordinator.prepare(
            currentCatalogSubtotal = 35L,
            confirmation = confirmation(35L, 0L),
            products = listOf(prod),
            bundles = listOf(bun),
            cart = cart,
            dateKey = "d",
            paymentMethod = PaymentMethod.CASH,
            tipAmountRaw = 3L,
        )
        val ok = r as PosCheckoutCoordinator.PrepareResult.Ready
        assertEquals(mapOf("p1" to 3L), ok.request.stockDeductions)
        assertEquals(3L, ok.request.tipAmount)
    }
}
