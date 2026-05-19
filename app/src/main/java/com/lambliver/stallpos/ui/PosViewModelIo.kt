package com.lambliver.stallpos.ui

import com.lambliver.stallpos.domain.*

import android.app.Application
import android.net.Uri
import android.util.Log
import com.lambliver.stallpos.domain.DocumentTarget
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun PosViewModel.exportCsv(target: DocumentTarget) {
    val uri = Uri.parse(target.value)
    val state = posUiState.value
    viewModelScope.launch {
        try {
            posCsvExport.writeSalesCsv(
                getApplication<Application>().contentResolver,
                uri,
                state.totalSales,
                state.txCount,
                state.todaySales,
                state.todayKey,
                state.salesLog.toList(),
                state.products.toList(),
                state.bundles.toList(),
            ).getOrThrow()
            PosOpsLog.csvExported(recordCount = state.salesLog.size)
            posCsvShareUriChannel.send(target.value)
            emitToast("CSV 導出成功！")
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "exportCsv failed", e)
            emitToast("導出失敗，請再試一次", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.exportBackupJson(target: DocumentTarget) {
    val uri = Uri.parse(target.value)
    viewModelScope.launch {
        try {
            posBackupSaf.writeFullBackupJson(getApplication<Application>().contentResolver, uri).getOrThrow()
            PosOpsLog.backupExported(schemaVersion = com.lambliver.stallpos.data.PosStore.BACKUP_SCHEMA_VERSION)
            emitToast("備份已儲存")
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "exportBackupJson failed", e)
            emitToast("備份失敗，請再試一次", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.importBackupJson(target: DocumentTarget) {
    val uri = Uri.parse(target.value)
    viewModelScope.launch {
        val text = try {
            posBackupSaf.readUtf8(getApplication<Application>().contentResolver, uri).getOrThrow()
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "importBackupJson read failed", e)
            emitToast(BackupRestoreResult.ReadFailed().userMessage, PosToastSeverity.Error)
            return@launch
        }
        if (text.isBlank()) {
            emitToast(BackupRestoreResult.ReadFailed().userMessage, PosToastSeverity.Error)
            return@launch
        }
        val result = restoreBackupFromText(text)
        emitToast(
            result.userMessage,
            if (result is BackupRestoreResult.Success) PosToastSeverity.Info else PosToastSeverity.Error,
        )
    }
}

/**
 * 原子還原：DataStore 寫入與購物車記憶體同步同一結果；失敗時不顯示成功訊息。
 */
internal suspend fun PosViewModel.restoreBackupFromText(text: String): BackupRestoreResult {
    posCartDebounceJob?.cancel()
    posCartDebounceJob = null
    return try {
        posStore.restoreFullBackupJson(text).getOrThrow()
        val snap = posStore.snapshot.first()
        applyPostBackupRestore(snap.cart)
        PosOpsLog.backupRestored(schemaVersion = com.lambliver.stallpos.data.PosStore.BACKUP_SCHEMA_VERSION)
        BackupRestoreResult.Success
    } catch (e: Throwable) {
        Log.e(PosViewModel.LOG_TAG, "restoreBackupFromText failed", e)
        reconcileCartMemoryWithDisk()
        BackupRestoreResult.RestoreFailed()
    }
}
