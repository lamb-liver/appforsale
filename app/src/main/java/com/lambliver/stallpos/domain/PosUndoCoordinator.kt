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
     * **不變量（正常路徑）**：每次結帳在同一原子寫入內追加 [salesLog] 並寫入 [lastCheckout]，
     * 故可復原時兩者應同時存在。
     *
     * **防禦（損壞備份／舊資料）**：若僅 [lastCheckout] 非 null 而 [salesLog] 為空，
     * 仍回傳 [UndoResult.Ready] 並還原購物車／庫存／累計；不對 log 做 [removeAt]（避免 IndexOutOfBounds）。
     */
    data class UndoState(
        val products: List<Product>,
        val totalSales: Long,
        val txCount: Long,
        val salesLog: List<SaleRecord>,
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
        val salesLog: List<SaleRecord>,
    )

    fun computeUndo(state: UndoState): UndoResult {
        val last = state.lastCheckout ?: return UndoResult.NothingToUndo
        return UndoResult.Ready(buildEffects(state, last))
    }

    private fun buildEffects(state: UndoState, last: LastCheckout): UndoEffects {
        val log = trimLastSaleIfPresent(state.salesLog)

        val restoreQty = stockQtyToRestore(last)
        val restoredProducts = restoreProductStock(state.products, restoreQty)

        return UndoEffects(
            products = restoredProducts,
            cart = PosCart(last.productCart, last.bundleCart),
            // [LastCheckout.total] 為結帳時寫入之應收＋小費（見 checkout 建立處）
            totalSales = (state.totalSales - last.total).coerceAtLeast(0L),
            txCount = (state.txCount - 1).coerceAtLeast(0L),
            salesLog = log,
        )
    }

    /** 有紀錄才移除最後一筆；空列表明跳過（見 [UndoState] KDoc）。 */
    private fun trimLastSaleIfPresent(salesLog: List<SaleRecord>): List<SaleRecord> {
        if (salesLog.isEmpty()) return salesLog
        return salesLog.toMutableList().also { it.removeAt(it.lastIndex) }
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
