package com.lambliver.stallpos.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal const val TRANSACTION_SCHEMA_VERSION = 3
internal val TRANSACTION_SCHEMA_VERSION_KEY = intPreferencesKey("transaction_schema_version")
internal val SALES_LOG_JSON_KEY = stringPreferencesKey("sales_log_json")
internal val SALES_REVERSAL_LOG_JSON_KEY = stringPreferencesKey("sales_reversal_log_json")
internal val LAST_CHECKOUT_JSON_KEY = stringPreferencesKey("last_checkout_json")

/** 一次性 local transaction schema 2→3 migration；屬於 persistence initialization。 */
internal object PosTransactionV3Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[TRANSACTION_SCHEMA_VERSION_KEY] ?: 2) < TRANSACTION_SCHEMA_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        val next = currentData.toMutablePreferences()
        migrateLegacyTransactionJson(
            salesJson = currentData[SALES_LOG_JSON_KEY].orEmpty(),
            lastCheckoutJson = currentData[LAST_CHECKOUT_JSON_KEY].orEmpty(),
        ).onSuccess { migrated ->
            next[SALES_LOG_JSON_KEY] = migrated.salesJson
            next[LAST_CHECKOUT_JSON_KEY] = migrated.lastCheckoutJson
        }.onFailure {
            // 保留 malformed Sales 原文，只停用無法安全配對的 Undo。
            next[LAST_CHECKOUT_JSON_KEY] = ""
        }
        if (currentData[SALES_REVERSAL_LOG_JSON_KEY] == null) {
            next[SALES_REVERSAL_LOG_JSON_KEY] = "[]"
        }
        next[TRANSACTION_SCHEMA_VERSION_KEY] = TRANSACTION_SCHEMA_VERSION
        return next
    }

    override suspend fun cleanUp() = Unit
}
