package com.lambliver.stallpos.domain

object BackupReminder {
    const val FIRST_EXPORT_GRACE_MS = 7L * 24 * 60 * 60 * 1000

    fun shouldShow(
        nowMillis: Long,
        hasBusinessData: Boolean,
        firstBusinessDataAtMillis: Long?,
        lastSuccessfulBackupAtMillis: Long?,
        lastCanonicalWriteAtMillis: Long?,
        snoozed: Boolean,
    ): Boolean {
        if (snoozed || !hasBusinessData) return false
        if (lastSuccessfulBackupAtMillis == null) {
            val first = firstBusinessDataAtMillis ?: return false
            return nowMillis - first >= FIRST_EXPORT_GRACE_MS
        }
        val lastWrite = lastCanonicalWriteAtMillis ?: return false
        return lastWrite > lastSuccessfulBackupAtMillis
    }

    fun hasBusinessData(
        products: List<Product>,
        bundles: List<Bundle>,
        sales: List<SaleRecord>,
        events: List<MarketEvent>,
    ): Boolean = products.isNotEmpty() || bundles.isNotEmpty() || sales.isNotEmpty() || events.isNotEmpty()
}
