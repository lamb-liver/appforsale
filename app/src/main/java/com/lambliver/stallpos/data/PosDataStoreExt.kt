package com.lambliver.stallpos.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** 全 app 共用之 Preferences DataStore（POS 業務 + UI 偏好鍵）。 */
internal val Context.posPreferencesDataStore by preferencesDataStore(name = "pos_store")
