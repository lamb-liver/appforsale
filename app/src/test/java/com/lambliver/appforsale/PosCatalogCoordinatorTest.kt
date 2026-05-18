package com.lambliver.appforsale

import com.lambliver.appforsale.data.CatalogPersistPlan
import com.lambliver.appforsale.data.FakePosPersistence
import com.lambliver.appforsale.data.PosPersistSnapshot
import com.lambliver.appforsale.domain.Bundle
import com.lambliver.appforsale.domain.BundleComponent
import com.lambliver.appforsale.domain.PosCart
import com.lambliver.appforsale.domain.PosCatalogCoordinator
import com.lambliver.appforsale.domain.PosCatalogCoordinator.CatalogCommand
import com.lambliver.appforsale.domain.PosCatalogCoordinator.CatalogState
import com.lambliver.appforsale.domain.Product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目錄協調器測試面＝[PosCatalogCoordinator] 介面；不依賴 Compose／ViewModel。
 * 持久化以 [FakePosPersistence] 驗證單一 seam。
 */
class PosCatalogCoordinatorTest {

    private val p1 = Product("p1", "品A", 100L)
    private val p2 = Product("p2", "品B", 50L)
    private val bundleUsingP1 = Bundle(
        "b1",
        "組",
        200L,
        components = listOf(BundleComponent("p1", 1L)),
    )

    private fun state(
        products: List<Product> = listOf(p1, p2),
        bundles: List<Bundle> = emptyList(),
        cart: PosCart = PosCart(products = mapOf("p1" to 2, "p2" to 1)),
    ) = CatalogState(products, bundles, cart)

    @Test
    fun deleteProduct_ready_removesFromCatalogAndCart() {
        val result = PosCatalogCoordinator.execute(
            state = state(),
            command = CatalogCommand.DeleteProduct("p1"),
        )
        val ready = result as PosCatalogCoordinator.CatalogResult.Ready
        assertEquals(listOf(p2), ready.plan.products)
        assertEquals(mapOf("p2" to 1), ready.plan.cart!!.products)
        assertNull(ready.plan.bundles)
    }

    @Test
    fun deleteProduct_ready_persistsViaFakePosPersistence() = runBlocking {
        val persist = FakePosPersistence(
            PosPersistSnapshot(
                products = listOf(p1, p2),
                cart = PosCart(products = mapOf("p1" to 2, "p2" to 1)),
            ),
        )
        val ready = PosCatalogCoordinator.execute(
            state = state(),
            command = CatalogCommand.DeleteProduct("p1"),
        ) as PosCatalogCoordinator.CatalogResult.Ready

        persist.applyCatalog(
            CatalogPersistPlan(
                products = ready.plan.products,
                bundles = ready.plan.bundles,
                cart = ready.plan.cart,
            ),
        )

        assertEquals(listOf(p2), persist.productsFlow.first())
        assertEquals(mapOf("p2" to 1), persist.cartFlow.first().products)
    }

    @Test
    fun deleteProduct_userMessage_whenReferencedByBundle() {
        val result = PosCatalogCoordinator.execute(
            state = state(bundles = listOf(bundleUsingP1)),
            command = CatalogCommand.DeleteProduct("p1"),
        )
        val msg = result as PosCatalogCoordinator.CatalogResult.UserMessage
        assertTrue(msg.message.contains("套組"))
    }

    @Test
    fun deleteProduct_ignored_whenProductMissing() {
        val result = PosCatalogCoordinator.execute(
            state = state(),
            command = CatalogCommand.DeleteProduct("missing"),
        )
        assertTrue(result is PosCatalogCoordinator.CatalogResult.Ignored)
    }

    @Test
    fun deleteBundle_ready_removesFromCatalogAndCart() {
        val bun = Bundle("b2", "套組B", 80L, components = emptyList())
        val result = PosCatalogCoordinator.execute(
            state = state(
                bundles = listOf(bundleUsingP1, bun),
                cart = PosCart(products = mapOf("p2" to 1), bundles = mapOf("b1" to 1, "b2" to 2)),
            ),
            command = CatalogCommand.DeleteBundle("b2"),
        )
        val ready = result as PosCatalogCoordinator.CatalogResult.Ready
        assertEquals(listOf(bundleUsingP1), ready.plan.bundles)
        assertEquals(mapOf("b1" to 1), ready.plan.cart!!.bundles)
        assertNull(ready.plan.products)
    }

    @Test
    fun deleteBundle_ready_persistsViaFakePosPersistence() = runBlocking {
        val bun = Bundle("b2", "套組B", 80L, components = emptyList())
        val persist = FakePosPersistence(
            PosPersistSnapshot(
                products = listOf(p1, p2),
                bundles = listOf(bun),
                cart = PosCart(bundles = mapOf("b2" to 1)),
            ),
        )
        val ready = PosCatalogCoordinator.execute(
            state = state(bundles = listOf(bun), cart = PosCart(bundles = mapOf("b2" to 1))),
            command = CatalogCommand.DeleteBundle("b2"),
        ) as PosCatalogCoordinator.CatalogResult.Ready

        persist.applyCatalog(
            CatalogPersistPlan(
                products = ready.plan.products,
                bundles = ready.plan.bundles,
                cart = ready.plan.cart,
            ),
        )

        assertTrue(persist.bundlesFlow.first().isEmpty())
        assertTrue(persist.cartFlow.first().bundles.isEmpty())
    }

    @Test
    fun deleteBundle_ignored_whenBundleMissing() {
        val result = PosCatalogCoordinator.execute(
            state = state(bundles = listOf(bundleUsingP1)),
            command = CatalogCommand.DeleteBundle("missing"),
        )
        assertTrue(result is PosCatalogCoordinator.CatalogResult.Ignored)
    }
}
