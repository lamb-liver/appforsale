package com.lambliver.stallpos.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** UI 偏好（與 [PosStore] 業務快照分離，共用同一 DataStore 實例）。 */
class AppUiPreferences(private val context: Context) {

    val hapticEnabledFlow: Flow<Boolean> =
        context.posPreferencesDataStore.data.map { prefs ->
            prefs[HAPTIC_ENABLED] ?: true
        }

    val soundEnabledFlow: Flow<Boolean> =
        context.posPreferencesDataStore.data.map { prefs ->
            prefs[SOUND_ENABLED] ?: true
        }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.posPreferencesDataStore.edit { prefs ->
            prefs[HAPTIC_ENABLED] = enabled
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.posPreferencesDataStore.edit { prefs ->
            prefs[SOUND_ENABLED] = enabled
        }
    }

    companion object {
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    }
}
