package com.lambliver.appforsale.domain

/**
 * 「目錄＋購物車快照 → 金額／套組／庫存驗證 → [CheckoutWriteRequest]」之單一協調 Seam。
 *
 * ViewModel 僅負責並發護欄、副作用（清空購物車、協程、Toast）。
 */
internal object PosCheckoutCoordinator {

    sealed interface PrepareResult {
        data class Ready(val request: CheckoutWriteRequest) : PrepareResult
        /** 直接給使用者看的簡短訊息（與既有 Toast 行為對齊）。 */
        data class UserMessage(val message: String) : PrepareResult
    }

    fun prepare(
        currentCatalogSubtotal: Long,
        confirmation: CheckoutAmounts.ConfirmationSnapshot,
        products: List<Product>,
        bundles: List<Bundle>,
        cart: PosCart,
        dateKey: String,
        paymentMethod: PaymentMethod,
        tipAmountRaw: Long,
    ): PrepareResult {
        val breakdown = when (
            val r = CheckoutAmounts.reconcile(
                liveCatalogSubtotal = CheckoutAmounts.CatalogSubtotal(currentCatalogSubtotal),
                confirmation = confirmation,
            )
        ) {
            is CheckoutAmounts.ReconcileResult.Rejected ->
                return PrepareResult.UserMessage(r.message)
            is CheckoutAmounts.ReconcileResult.Accepted -> r.breakdown
        }

        val saleMoney = breakdown.toSaleRecordAmountFields()
        val pc = cart.products
        val bc = cart.bundles

        for (b in bundles) {
            if (bc[b.id] ?: 0 <= 0) continue
            if (!posValidateBundleComponents(products, b)) {
                return PrepareResult.UserMessage("套組「${b.name}」成分異常，無法結帳")
            }
        }

        val lines = posBuildCheckoutLines(pc, bc, products, bundles)
        val deductions = posMergeStockDeductions(pc, posExpandBundleCart(bc, bundles))
        if (!posValidateStockForCheckout(deductions, products)) {
            return PrepareResult.UserMessage("庫存不足，請調整購物車後再結帳")
        }

        val tip = tipAmountRaw.coerceAtLeast(0L)
        return PrepareResult.Ready(
            CheckoutWriteRequest(
                productCart = pc,
                bundleCart = bc,
                subtotal = saleMoney.persistedSubtotal,
                discount = saleMoney.discount,
                total = saleMoney.total,
                dateKey = dateKey,
                paymentMethod = paymentMethod,
                checkoutLines = lines,
                stockDeductions = deductions,
                tipAmount = tip,
            ),
        )
    }
}
