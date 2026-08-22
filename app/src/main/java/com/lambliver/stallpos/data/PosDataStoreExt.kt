package com.lambliver.stallpos.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** UI preferences 與凍結的 v1.2/v1.3 business JSON migration artifact。 */
internal val Context.posPreferencesDataStore by preferencesDataStore(
    name = "pos_store",
    produceMigrations = { listOf(PosTransactionV3Migration) },
)
