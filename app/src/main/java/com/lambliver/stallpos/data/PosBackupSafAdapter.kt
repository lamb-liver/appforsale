package com.lambliver.stallpos.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 備份 JSON 讀寫（實際覆寫／還原仍由 [PosPersistence] 負責）。
 */
class PosBackupSafAdapter(
    private val store: PosPersistence,
) {

    suspend fun writeFullBackupJson(resolver: ContentResolver, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = store.exportFullBackupJson()
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("無法開啟輸出串流")
            }
        }

    suspend fun readUtf8(resolver: ContentResolver, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.openInputStream(uri)?.use { inp ->
                    inp.readBytes().toString(Charsets.UTF_8)
                } ?: error("無法讀取檔案")
            }
        }
}
