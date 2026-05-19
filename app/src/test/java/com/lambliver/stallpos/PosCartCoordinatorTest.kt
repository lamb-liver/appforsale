package com.lambliver.stallpos

import com.lambliver.stallpos.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosCartCoordinatorTest {

    private val prod = Product("p1", "品", 10L, stock = 5L)
    private val bundles = emptyList<Bundle>()

    private fun state(cart: PosCart = PosCart()) = PosCartCoordinator.CartState(
        products = listOf(prod),
        bundles = bundles,
        cart = cart,
    )

    @Test
    fun addProduct_ready_incrementsCount() {
        val r = PosCartCoordinator.execute(
            state = state(),
            command = PosCartCoordinator.CartCommand.AddProduct("p1"),
        ) as PosCartCoordinator.CartResult.Ready
        assertEquals(PosCart(products = mapOf("p1" to 1)), r.cart)
    }

    @Test
    fun removeProduct_ignored_whenNotInCart() {
        val r = PosCartCoordinator.execute(
            state = state(),
            command = PosCartCoordinator.CartCommand.RemoveProduct("p1"),
        )
        assertEquals(PosCartCoordinator.CartResult.Ignored, r)
    }

    @Test
    fun setProductQty_unknownId_ignored() {
        val r = PosCartCoordinator.execute(
            state = state(cart = PosCart(products = mapOf("p1" to 1))),
            command = PosCartCoordinator.CartCommand.SetProductQty("ghost", 99),
        )
        assertEquals(PosCartCoordinator.CartResult.Ignored, r)
    }

    @Test
    fun setProductQty_zeroStock_userMessage() {
        val dead = prod.copy(stock = 0L)
        val r = PosCartCoordinator.execute(
            state = PosCartCoordinator.CartState(listOf(dead), bundles, PosCart()),
            command = PosCartCoordinator.CartCommand.SetProductQty("p1", 1),
        )
        val msg = r as PosCartCoordinator.CartResult.UserMessage
        assertEquals("此商品庫存為 0，無法加入", msg.message)
    }

    @Test
    fun setProductQty_qtyZero_removesLine() {
        val r = PosCartCoordinator.execute(
            state = state(cart = PosCart(products = mapOf("p1" to 2))),
            command = PosCartCoordinator.CartCommand.SetProductQty("p1", 0),
        ) as PosCartCoordinator.CartResult.Ready
        assertEquals(PosCart(), r.cart)
    }

    @Test
    fun setProductQty_clampsAboveStock_warnsToast() {
        val r = PosCartCoordinator.execute(
            state = state(),
            command = PosCartCoordinator.CartCommand.SetProductQty("p1", 999),
        ) as PosCartCoordinator.CartResult.Ready
        assertEquals(PosCart(products = mapOf("p1" to 5)), r.cart)
        assertEquals(listOf("已達庫存或可搭配套組之上限"), r.toasts)
    }

    @Test
    fun clampCartToStock_removesLineWhenListedStockHitsZero() {
        val cartBefore = PosCart(products = mapOf("p1" to 3))
        val (next, changed) = PosCartCoordinator.clampCartToStock(
            products = listOf(prod.copy(stock = 0L)),
            bundles = bundles,
            cart = cartBefore,
        )
        assertTrue(changed)
        assertEquals(PosCart(), next)
    }

    @Test
    fun setBundleQty_invalidComponents_userMessage() {
        val bun = Bundle("b1", "組", 20L, components = listOf(BundleComponent("missing", 1L)))
        val r = PosCartCoordinator.execute(
            state = PosCartCoordinator.CartState(listOf(prod), listOf(bun), PosCart()),
            command = PosCartCoordinator.CartCommand.SetBundleQty("b1", 1),
        )
        val msg = r as PosCartCoordinator.CartResult.UserMessage
        assertTrue(msg.message.contains("成分異常"))
    }

    @Test
    fun setBundleQty_clampsAgainstStockIncludesSinglesConsumption() {
        val bun = Bundle("b1", "組", 20L, components = listOf(BundleComponent("p1", 2L)))
        val products = listOf(prod.copy(stock = 5L))
        val bundlesList = listOf(bun)
        val cartStart = PosCart(products = mapOf("p1" to 1), bundles = mapOf("b1" to 1))
        val maxMore = PosCartCoordinator.execute(
            PosCartCoordinator.CartState(products, bundlesList, cartStart),
            PosCartCoordinator.CartCommand.SetBundleQty("b1", 10),
        ) as PosCartCoordinator.CartResult.Ready
        assertEquals(listOf("庫存不足以加入更多套組"), maxMore.toasts)
        assertTrue(maxMore.cart.bundles["b1"]!! < 10)
        assertEquals(2, maxMore.cart.bundles["b1"])
    }
}
