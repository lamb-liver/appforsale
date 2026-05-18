package com.lambliver.appforsale.data

import com.lambliver.appforsale.domain.PosUndoCoordinator

/**
 * 將 data 層 [PosPersistSnapshot] 對應到 domain [PosUndoCoordinator.UndoState]。
 *
 * 刻意放在 `data` 而非 `domain`：[PosPersistSnapshot] 為持久化聚合型別，domain 不應依賴它；
 * 若日後快照形狀變更，只需調整此檔的映射。
 */
internal fun PosPersistSnapshot.toUndoState(): PosUndoCoordinator.UndoState =
    PosUndoCoordinator.UndoState(
        products = products,
        totalSales = totalSales,
        txCount = txCount,
        salesLog = salesLog,
        lastCheckout = lastCheckout,
    )

/** 無可復原時回傳 null。 */
internal fun PosPersistSnapshot.applyUndoIfPossible(): PosPersistSnapshot? =
    when (val r = PosUndoCoordinator.computeUndo(toUndoState())) {
        PosUndoCoordinator.UndoResult.NothingToUndo -> null
        is PosUndoCoordinator.UndoResult.Ready ->
            copy(
                products = r.effects.products,
                cart = r.effects.cart,
                totalSales = r.effects.totalSales,
                txCount = r.effects.txCount,
                salesLog = r.effects.salesLog,
                lastCheckout = null,
            )
    }
