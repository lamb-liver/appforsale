package com.lambliver.stallpos.ui

import com.lambliver.stallpos.domain.DialogState
import com.lambliver.stallpos.domain.PosCart
import kotlinx.coroutines.flow.update

/** 備份還原單一結果：UI 僅依 [userMessage] 顯示一次 Toast。 */
internal sealed class BackupRestoreResult {
    abstract val userMessage: String

    data object Success : BackupRestoreResult() {
        override val userMessage: String = "資料已由備份完整還原"
    }

    data class ReadFailed(
        override val userMessage: String = "無法讀取備份檔",
    ) : BackupRestoreResult()

    data class RestoreFailed(
        override val userMessage: String = "還原失敗，請確認檔案格式",
    ) : BackupRestoreResult()
}

/** 還原成功後同步記憶體購物車並清除結帳中狀態。 */
internal fun PosViewModel.applyPostBackupRestore(cart: PosCart) {
    posCartMemory.value = cart
    resetCatalogClampKey()
    posUiState.update {
        it.copy(
            dialogState = DialogState.None,
            checkoutCustomDigits = "",
            checkoutDiscountRequested = 0L,
            checkoutSheetSnapshot = null,
        )
    }
}

internal fun PosViewModel.resetCatalogClampKey() {
    lastCatalogClampKey = null
}
