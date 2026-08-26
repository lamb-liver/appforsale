package com.lambliver.stallpos.ui

import android.os.Build
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.lambliver.stallpos.BuildConfig
import com.lambliver.stallpos.data.CatalogPersistPlan
import com.lambliver.stallpos.data.VersionChecker
import com.lambliver.stallpos.domain.BackupReminder
import com.lambliver.stallpos.domain.IntegrityAudit
import com.lambliver.stallpos.domain.PosToastSeverity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun PosViewModel.afterCanonicalWrite(nowMillis: Long = System.currentTimeMillis()) {
    backupReminderSnoozed = false
    viewModelScope.launch {
        try {
            appUiPrefs.markCanonicalWrite(nowMillis)
            refreshBackupReminder(nowMillis)
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "backup reminder update failed", e)
        }
    }
}

internal fun PosViewModel.afterSuccessfulBackup(nowMillis: Long = System.currentTimeMillis()) {
    viewModelScope.launch {
        try {
            appUiPrefs.markSuccessfulBackup(nowMillis)
            refreshBackupReminder(nowMillis)
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "backup reminder update failed", e)
        }
    }
}

internal suspend fun PosViewModel.refreshBackupReminder(nowMillis: Long = System.currentTimeMillis()) {
    val snap = posUiState.value
    backupReminderVisible.value = BackupReminder.shouldShow(
        nowMillis = nowMillis,
        hasBusinessData = BackupReminder.hasBusinessData(
            products = snap.products,
            bundles = snap.bundles,
            sales = snap.salesLog,
            events = snap.events,
        ),
        firstBusinessDataAtMillis = appUiPrefs.firstBusinessDataAtMillis.first(),
        lastSuccessfulBackupAtMillis = appUiPrefs.lastSuccessfulBackupAtMillis.first(),
        lastCanonicalWriteAtMillis = appUiPrefs.lastCanonicalWriteAtMillis.first(),
        snoozed = backupReminderSnoozed,
    )
}

internal suspend fun PosViewModel.refreshLatestVersion(nowMillis: Long = System.currentTimeMillis()) {
    if (Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) return
    val tag = VersionChecker.cachedOrFetch(appUiPrefs, BuildConfig.VERSION_NAME, nowMillis)
    latestVersionTag.value = VersionChecker.newerThanCurrent(BuildConfig.VERSION_NAME, tag)
}

internal fun PosViewModel.runIntegrityCheck() {
    viewModelScope.launch {
        val report = IntegrityAudit.audit(posStore.integritySnapshot())
        checkDataMessage.value = report.userMessage()
        emitToast(
            report.userMessage(),
            if (report.ok) PosToastSeverity.Info else PosToastSeverity.Error,
        )
    }
}

internal suspend fun PosViewModel.persistCatalog(plan: CatalogPersistPlan) {
    posStore.applyCatalog(plan)
    afterCanonicalWrite()
}

internal fun PosViewModel.setProductActive(productId: String, active: Boolean) {
    val ui = posUiState.value
    when (
        val r = com.lambliver.stallpos.domain.PosCatalogCoordinator.execute(
            state = com.lambliver.stallpos.domain.PosCatalogCoordinator.CatalogState(
                products = ui.products,
                bundles = ui.bundles,
                cart = posCartMemory.value,
            ),
            command = com.lambliver.stallpos.domain.PosCatalogCoordinator.CatalogCommand.SetProductActive(
                productId,
                active,
            ),
        )
    ) {
        is com.lambliver.stallpos.domain.PosCatalogCoordinator.CatalogResult.Ignored -> return
        is com.lambliver.stallpos.domain.PosCatalogCoordinator.CatalogResult.UserMessage -> {
            emitToastAsync(r.message, PosToastSeverity.Error)
            return
        }
        is com.lambliver.stallpos.domain.PosCatalogCoordinator.CatalogResult.Ready -> {
            posCartDebounceJob?.cancel()
            r.plan.cart?.let { posCartMemory.value = it }
            viewModelScope.launch {
                try {
                    persistCatalog(
                        CatalogPersistPlan(
                            products = r.plan.products,
                            bundles = r.plan.bundles,
                            cart = r.plan.cart,
                        ),
                    )
                } catch (e: Throwable) {
                    Log.e(PosViewModel.LOG_TAG, "setProductActive failed", e)
                    emitToast("更新商品失敗：${e.message}", PosToastSeverity.Error)
                }
            }
        }
    }
}
