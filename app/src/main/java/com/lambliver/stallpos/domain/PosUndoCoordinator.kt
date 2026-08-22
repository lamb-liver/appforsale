package com.lambliver.stallpos.domain

/**
 * 「上一筆結帳（可用於復原）」之業務規則：可否復原、還原後的狀態差異。
 *
 * 持久化層只負責套用 [UndoEffects]；輸入為 [UndoState]（不含 UI）。
 */
internal object PosUndoCoordinator {

    /**
     * 復原計算所需之持久化快照欄位。
     *
     * 呼叫端必須在寫入 Reversal 的同一個原子 persistence transaction 內建立此快照。
     */
    data class UndoState(
        val products: List<Product>,
        val totalSales: Long,
        val txCount: Long,
        val salesLog: List<SaleRecord>,
        val reversalLog: List<SaleReversal>,
        val lastCheckout: LastCheckout?,
    )

    sealed interface UndoResult {
        /** 無 [LastCheckout] 快照；與現行 UX 對齊之靜默略過。 */
        data object NothingToUndo : UndoResult

        data class Ready(val effects: UndoEffects) : UndoResult
    }

    /** 復原一次結帳後應寫入持久化的欄位。 */
    data class UndoEffects(
        val products: List<Product>,
        val cart: PosCart,
        val totalSales: Long,
        val txCount: Long,
        val reversalLog: List<SaleReversal>,
    )

    fun computeUndo(
        state: UndoState,
        reversalId: String,
        reversedAtMillis: Long,
    ): UndoResult {
        val last = state.lastCheckout ?: return UndoResult.NothingToUndo
        if (reversalId.isBlank()) return UndoResult.NothingToUndo
        if (state.salesLog.none { it.id == last.saleId }) return UndoResult.NothingToUndo
        if (state.reversalLog.any { it.saleId == last.saleId }) return UndoResult.NothingToUndo
        return UndoResult.Ready(buildEffects(state, last, reversalId, reversedAtMillis))
    }

    private fun buildEffects(
        state: UndoState,
        last: LastCheckout,
        reversalId: String,
        reversedAtMillis: Long,
    ): UndoEffects {
        val restoreQty = stockQtyToRestore(last)
        val restoredProducts = restoreProductStock(state.products, restoreQty)

        return UndoEffects(
            products = restoredProducts,
            cart = PosCart(last.productCart, last.bundleCart),
            // [LastCheckout.total] 為結帳時寫入之應收＋小費（見 checkout 建立處）
            totalSales = (state.totalSales - last.total).coerceAtLeast(0L),
            txCount = (state.txCount - 1).coerceAtLeast(0L),
            reversalLog = state.reversalLog + SaleReversal(
                id = reversalId,
                saleId = last.saleId,
                tsMillis = reversedAtMillis,
                reason = ReversalReason.UNDO_LAST_CHECKOUT,
            ),
        )
    }

    private fun stockQtyToRestore(last: LastCheckout): Map<String, Long> =
        last.stockDeductions.takeIf { it.isNotEmpty() }
            ?: buildMap {
                last.productCart.forEach { (k, v) -> put(k, v.toLong()) }
            }

    private fun restoreProductStock(
        products: List<Product>,
        restoreQty: Map<String, Long>,
    ): List<Product> =
        products.map { p ->
            val qty = restoreQty[p.id] ?: return@map p
            val s = p.stock ?: return@map p
            p.copy(stock = s + qty)
        }
}
