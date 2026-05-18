package com.lambliver.appforsale.domain

/**
 * 目錄變更（商品／套組／分類）之協調 Seam：規則與衍生狀態集中於此，ViewModel 只套用 [CatalogPersistPlan] 與 Toast。
 *
 * 與 [PosCartCoordinator]（購物車互動）、[PosCheckoutCoordinator]（結帳對帳）分工。
 */
internal object PosCatalogCoordinator {

    /** 呼叫端傳入的唯讀目錄＋購物車快照（不含 UI 欄位）。 */
    data class CatalogState(
        val products: List<Product>,
        val bundles: List<Bundle>,
        val cart: PosCart,
    )

    /** 目錄層意圖；不等同 [PosEvent]（後者含導覽／Dialog）。 */
    sealed interface CatalogCommand {
        data class DeleteProduct(val productId: String) : CatalogCommand
        data class DeleteBundle(val bundleId: String) : CatalogCommand
    }

    /** 通過規則後，交由 [com.lambliver.appforsale.data.PosPersistence.applyCatalog] 的寫入計畫（null = 不寫該鍵）。 */
    data class CatalogPersistPlan(
        val products: List<Product>? = null,
        val bundles: List<Bundle>? = null,
        val cart: PosCart? = null,
    )

    sealed interface CatalogResult {
        data class Ready(val plan: CatalogPersistPlan) : CatalogResult
        data class UserMessage(val message: String) : CatalogResult
        /** 目標已不存在等；與現行 UX 對齊之靜默略過。 */
        data object Ignored : CatalogResult
    }

    fun execute(state: CatalogState, command: CatalogCommand): CatalogResult = when (command) {
        is CatalogCommand.DeleteProduct -> prepareDeleteProduct(state, command.productId)
        is CatalogCommand.DeleteBundle -> prepareDeleteBundle(state, command.bundleId)
    }

    fun prepareDeleteProduct(state: CatalogState, productId: String): CatalogResult {
        if (state.products.none { it.id == productId }) {
            return CatalogResult.Ignored
        }
        if (posProductReferencedByBundles(productId, state.bundles)) {
            return CatalogResult.UserMessage("仍有套組使用此商品，請先編輯套組")
        }
        val updatedProducts = state.products.filter { it.id != productId }
        val updatedCart = cartWithoutDeletedCatalogItem(state.cart, productId = productId)
        return CatalogResult.Ready(
            CatalogPersistPlan(
                products = updatedProducts,
                cart = updatedCart,
            ),
        )
    }

    fun prepareDeleteBundle(state: CatalogState, bundleId: String): CatalogResult {
        if (state.bundles.none { it.id == bundleId }) {
            return CatalogResult.Ignored
        }
        val updatedBundles = state.bundles.filter { it.id != bundleId }
        val updatedCart = cartWithoutDeletedCatalogItem(state.cart, bundleId = bundleId)
        return CatalogResult.Ready(
            CatalogPersistPlan(
                bundles = updatedBundles,
                cart = updatedCart,
            ),
        )
    }

    /**
     * 目錄刪除後自購物車移除對應商品或套組行。
     * 不執行庫存 clamp；ViewModel 在 catalog snapshot 更新後會呼叫 [PosCartCoordinator.clampCartToStock]。
     */
    private fun cartWithoutDeletedCatalogItem(
        cart: PosCart,
        productId: String? = null,
        bundleId: String? = null,
    ): PosCart {
        if (productId == null && bundleId == null) return cart
        val pc = productId?.let { id ->
            if (id !in cart.products) cart.products
            else cart.products.toMutableMap().apply { remove(id) }
        } ?: cart.products
        val bc = bundleId?.let { id ->
            if (id !in cart.bundles) cart.bundles
            else cart.bundles.toMutableMap().apply { remove(id) }
        } ?: cart.bundles
        if (pc === cart.products && bc === cart.bundles) return cart
        return PosCart(pc, bc)
    }
}
