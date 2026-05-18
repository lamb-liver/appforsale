package com.lambliver.appforsale.ui

import android.util.Log

/** 關鍵業務操作結構化 log（不含商品名稱、金額明細等 PII）。 */
internal object PosOpsLog {
    private const val TAG = PosViewModel.LOG_TAG

    fun checkoutCommitted(txCount: Long, receivableCents: Long) {
        Log.i(TAG, "event=checkout_ok txCount=$txCount receivable=$receivableCents")
    }

    fun checkoutUndone(txCount: Long) {
        Log.i(TAG, "event=undo_ok txCount=$txCount")
    }

    fun backupRestored(schemaVersion: Int) {
        Log.i(TAG, "event=backup_restore_ok schemaVersion=$schemaVersion")
    }

    fun backupExported(schemaVersion: Int) {
        Log.i(TAG, "event=backup_export_ok schemaVersion=$schemaVersion")
    }

    fun csvExported(recordCount: Int) {
        Log.i(TAG, "event=csv_export_ok records=$recordCount")
    }
}
