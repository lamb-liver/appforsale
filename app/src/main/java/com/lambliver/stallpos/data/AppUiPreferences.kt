package com.lambliver.stallpos.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** UI 偏好（與 [PosStore] 業務快照分離，共用同一 DataStore 實例）。 */
class AppUiPreferences(private val context: Context) {

    val hapticEnabledFlow: Flow<Boolean> =
        context.posPreferencesDataStore.data.map { prefs -> prefs[HAPTIC_ENABLED] ?: true }

    val soundEnabledFlow: Flow<Boolean> =
        context.posPreferencesDataStore.data.map { prefs -> prefs[SOUND_ENABLED] ?: true }

    val extraLargeTextFlow: Flow<Boolean> =
        context.posPreferencesDataStore.data.map { prefs -> prefs[EXTRA_LARGE_TEXT] ?: false }

    val lastSuccessfulBackupAtMillis: Flow<Long?> =
        context.posPreferencesDataStore.data.map { prefs -> prefs[LAST_SUCCESSFUL_BACKUP] }

    val firstBusinessDataAtMillis: Flow<Long?> =
        context.posPreferencesDataStore.data.map { prefs -> prefs[FIRST_BUSINESS_DATA] }

    val lastCanonicalWriteAtMillis: Flow<Long?> =
        context.posPreferencesDataStore.data.map { prefs -> prefs[LAST_CANONICAL_WRITE] }

    val cachedLatestVersionTag: Flow<String?> =
        context.posPreferencesDataStore.data.map { prefs -> prefs[CACHED_LATEST_VERSION] }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.posPreferencesDataStore.edit { it[HAPTIC_ENABLED] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.posPreferencesDataStore.edit { it[SOUND_ENABLED] = enabled }
    }

    suspend fun setExtraLargeText(enabled: Boolean) {
        context.posPreferencesDataStore.edit { it[EXTRA_LARGE_TEXT] = enabled }
    }

    suspend fun markSuccessfulBackup(nowMillis: Long) {
        context.posPreferencesDataStore.edit { it[LAST_SUCCESSFUL_BACKUP] = nowMillis }
    }

    suspend fun markCanonicalWrite(nowMillis: Long) {
        context.posPreferencesDataStore.edit { prefs ->
            if (prefs[FIRST_BUSINESS_DATA] == null) prefs[FIRST_BUSINESS_DATA] = nowMillis
            prefs[LAST_CANONICAL_WRITE] = nowMillis
        }
    }

    suspend fun versionCache(): VersionCache {
        val prefs = context.posPreferencesDataStore.data.first()
        return VersionCache(
            latestTag = prefs[CACHED_LATEST_VERSION],
            checkedAtMillis = prefs[VERSION_CHECKED_AT],
            retryAtMillis = prefs[VERSION_RETRY_AT],
        )
    }

    suspend fun saveVersionCache(latestTag: String, checkedAtMillis: Long) {
        context.posPreferencesDataStore.edit { prefs ->
            prefs[CACHED_LATEST_VERSION] = latestTag
            prefs[VERSION_CHECKED_AT] = checkedAtMillis
            prefs.remove(VERSION_RETRY_AT)
        }
    }

    suspend fun saveVersionRetry(retryAtMillis: Long) {
        context.posPreferencesDataStore.edit { it[VERSION_RETRY_AT] = retryAtMillis }
    }

    data class VersionCache(
        val latestTag: String?,
        val checkedAtMillis: Long?,
        val retryAtMillis: Long?,
    )

    companion object {
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val EXTRA_LARGE_TEXT = booleanPreferencesKey("extra_large_text")
        val LAST_SUCCESSFUL_BACKUP = longPreferencesKey("last_successful_backup_at")
        val FIRST_BUSINESS_DATA = longPreferencesKey("first_business_data_at")
        val LAST_CANONICAL_WRITE = longPreferencesKey("last_canonical_write_at")
        val CACHED_LATEST_VERSION = stringPreferencesKey("cached_latest_version_tag")
        val VERSION_CHECKED_AT = longPreferencesKey("latest_version_checked_at")
        val VERSION_RETRY_AT = longPreferencesKey("latest_version_retry_at")
    }
}
