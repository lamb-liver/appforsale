package com.lambliver.appforsale.domain

/**
 * 「目錄＋購物車快照 → 依庫存／套組規則調整購物車」之協調 Seam；與 [PosCheckoutCoordinator] 分工。
 *
 * ViewModel 只負責套用 [CartResult]、Toast、防抖寫入。
 */
internal object PosCartCoordinator {

    data class CartState(
        val products: List<Product>,
        val bundles: List<Bundle>,
        val cart: PosCart,
    )

    sealed interface CartCommand {
        data class AddProduct(val productId: String) : CartCommand
        data class RemoveProduct(val productId: String) : CartCommand
        data class SetProductQty(val productId: String, val qty: Int) : CartCommand
        data class AddBundle(val bundleId: String) : CartCommand
        data class RemoveBundle(val bundleId: String) : CartCommand
        data class SetBundleQty(val bundleId: String, val qty: Int) : CartCommand
    }

    sealed interface CartResult {
        data class Ready(val cart: PosCart, val toasts: List<String> = emptyList()) : CartResult
        data class UserMessage(val message: String) : CartResult
        /** 目標不存在等；與現行 UX 對齊之靜默略過。 */
        data object Ignored : CartResult
    }

    fun execute(state: CartState, command: CartCommand): CartResult = when (command) {
        is CartCommand.AddProduct -> addProduct(state, command.productId)
        is CartCommand.RemoveProduct -> removeProduct(state, command.productId)
        is CartCommand.SetProductQty -> setProductQty(state, command.productId, command.qty)
        is CartCommand.AddBundle -> addBundle(state, command.bundleId)
        is CartCommand.RemoveBundle -> removeBundle(state, command.bundleId)
        is CartCommand.SetBundleQty -> setBundleQty(state, command.bundleId, command.qty)
    }

    fun clampCartToStock(products: List<Product>, bundles: List<Bundle>, cart: PosCart): Pair<PosCart, Boolean> {
        val productsById = products.associateBy { it.id }
        var pc = cart.products.toMutableMap()
        var changed = false
        for ((id, qty) in cart.products.toMap()) {
            val p = productsById[id] ?: continue
            val cap = p.stock ?: continue
            val max = cap.toInt().coerceIn(0, Int.MAX_VALUE)
            when {
                max <= 0 && qty > 0 -> {
                    pc.remove(id)
                    changed = true
                }
                qty > max -> {
                    pc[id] = max
                    changed = true
                }
            }
        }
        val bc = posClampBundleCart(pc, cart.bundles, products, bundles)
        if (bc != cart.bundles) changed = true
        return Pair(PosCart(pc, bc), changed)
    }

    private fun addProduct(state: CartState, productId: String): CartResult {
        val current = state.cart.products[productId] ?: 0
        return setProductQty(state, productId, current + 1)
    }

    private fun removeProduct(state: CartState, productId: String): CartResult {
        if (productId !in state.cart.products) return CartResult.Ignored
        val current = state.cart.products[productId] ?: 0
        return setProductQty(state, productId, (current - 1).coerceAtLeast(0))
    }

    private fun setProductQty(state: CartState, productId: String, qty: Int): CartResult =
        when (
            val r = setProductLineQty(state.products, state.bundles, state.cart, productId, qty)
        ) {
            MutationResult.Ignored -> CartResult.Ignored
            is MutationResult.Blocked -> CartResult.UserMessage(r.message)
            is MutationResult.Applied -> CartResult.Ready(r.cart, r.toasts)
        }

    private fun addBundle(state: CartState, bundleId: String): CartResult {
        val current = state.cart.bundles[bundleId] ?: 0
        return setBundleQty(state, bundleId, current + 1)
    }

    private fun removeBundle(state: CartState, bundleId: String): CartResult {
        if (bundleId !in state.cart.bundles) return CartResult.Ignored
        val current = state.cart.bundles[bundleId] ?: 0
        return setBundleQty(state, bundleId, (current - 1).coerceAtLeast(0))
    }

    private fun setBundleQty(state: CartState, bundleId: String, qty: Int): CartResult =
        when (
            val r = setBundleLineQty(state.products, state.bundles, state.cart, bundleId, qty)
        ) {
            MutationResult.Ignored -> CartResult.Ignored
            is MutationResult.Blocked -> CartResult.UserMessage(r.message)
            is MutationResult.Applied -> CartResult.Ready(r.cart, r.toasts)
        }

    private sealed interface MutationResult {
        data object Ignored : MutationResult
        data class Applied(val cart: PosCart, val toasts: List<String> = emptyList()) : MutationResult
        data class Blocked(val message: String) : MutationResult
    }

    private fun setProductLineQty(
        products: List<Product>,
        bundles: List<Bundle>,
        cart: PosCart,
        productId: String,
        qty: Int,
    ): MutationResult {
        val product = products.find { it.id == productId } ?: return MutationResult.Ignored
        val mut = cart.products.toMutableMap()
        if (qty <= 0) {
            mut.remove(productId)
            return MutationResult.Applied(PosCart(mut, cart.bundles))
        }
        val cap = product.stock
        val maxAllowed = if (cap == null) Int.MAX_VALUE
        else cap.toInt().coerceIn(0, Int.MAX_VALUE)
        if (cap != null && maxAllowed <= 0) {
            return MutationResult.Blocked("此商品庫存為 0，無法加入")
        }
        val maxFromBundles = posMaxProductQtyAllowedInSingles(productId, mut, cart.bundles, products, bundles)
        val ceiling = minOf(maxAllowed, maxFromBundles)
        val clamped = qty.coerceAtMost(ceiling)
        val toast = if (clamped < qty) listOf("已達庫存或可搭配套組之上限") else emptyList()
        mut[productId] = clamped
        return MutationResult.Applied(PosCart(mut, cart.bundles), toast)
    }

    private fun setBundleLineQty(
        products: List<Product>,
        bundles: List<Bundle>,
        cart: PosCart,
        bundleId: String,
        qty: Int,
    ): MutationResult {
        val bundle = bundles.find { it.id == bundleId } ?: return MutationResult.Ignored
        if (!posValidateBundleComponents(products, bundle)) {
            return MutationResult.Blocked("套組成分異常，請重新編輯套組")
        }
        val mut = cart.bundles.toMutableMap()
        if (qty <= 0) {
            mut.remove(bundleId)
            return MutationResult.Applied(PosCart(cart.products, mut))
        }
        val maxAllowed = posMaxQtyForBundle(bundleId, cart.products, mut, products, bundles)
        val clamped = qty.coerceAtMost(maxAllowed)
        val toast = if (clamped < qty) listOf("庫存不足以加入更多套組") else emptyList()
        mut[bundleId] = clamped
        return MutationResult.Applied(PosCart(cart.products, mut), toast)
    }
}
