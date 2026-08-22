package com.lambliver.stallpos.data

import org.json.JSONObject

/**
 * 備份 payload 版本遷移（還原路徑由 [parseValidatedBackupPayload] 呼叫）。
 *
 * ## 兩層版本欄位（勿混用）
 *
 * | 欄位 | 層級 | 職責 |
 * |------|------|------|
 * | **`schemaVersion`** | **Envelope**（備份檔根物件，與 `format` 並列） | 控制 [parseBackupEnvelope] 能否接受檔案、以及要跑哪些 **步驟遷移**（`migrateV1ToV2` …）。由 [PosStore.BACKUP_SCHEMA_VERSION] 定義 App 支援上限。 |
 * | **`payloadSchema`** | **Payload**（`payload` 物件內） | 標記業務資料束形狀；供 payload 內欄位遷移使用。現行 v3 與 envelope 3 對齊。 |
 *
 * 匯出時：外層 `schemaVersion` = [PosStore.BACKUP_SCHEMA_VERSION]，payload 內寫入 `payloadSchema`（同值）。
 *
 * ## 冪等性
 *
 * v1→v2 只補版本標記；v2→v3 補交易 ID、LastCheckout 關聯與 Reversal log，重複輸入不改變既有 ID。
 * Envelope `schemaVersion` 已為目前版本時，[migratePayloadToCurrent] 不進入遷移迴圈（identity）。
 *
 * 新增版本：遞增 [PosStore.BACKUP_SCHEMA_VERSION]、實作 `migrateV{n}ToV{n+1}`、補 [BackupMigrationTest] fixture。
 */
internal object BackupMigration {

    fun migratePayloadToCurrent(envelopeSchema: Int, payload: JSONObject): JSONObject {
        var current = payload
        var schema = envelopeSchema
        while (schema < PosStore.BACKUP_SCHEMA_VERSION) {
            current = when (schema) {
                1 -> migrateV1ToV2(current)
                2 -> migrateV2ToV3(current)
                else -> throw IllegalArgumentException(
                    "備份版本 $schema 無法遷移至 ${PosStore.BACKUP_SCHEMA_VERSION}",
                )
            }
            schema++
        }
        return current
    }

    /**
     * v1→v2：寫入 payload 內部版本標記（不影響現有 decode 鍵）。
     * 冪等：已有 `payloadSchema` 則不覆寫。
     */
    private fun migrateV1ToV2(payload: JSONObject): JSONObject =
        payload.apply {
            if (!has("payloadSchema")) put("payloadSchema", 2)
        }

    /** v2→v3：補 stable Sale ID、可靠的 LastCheckout.saleId 與空 reversal audit log。 */
    private fun migrateV2ToV3(payload: JSONObject): JSONObject =
        payload.apply {
            migrateLegacyTransactionJson(
                salesJson = optString("sales_log_json", ""),
                lastCheckoutJson = optString("last_checkout_json", ""),
            ).onSuccess { migrated ->
                put("sales_log_json", migrated.salesJson)
                put("last_checkout_json", migrated.lastCheckoutJson)
            }.onFailure {
                // 損壞 Sales 原文保留；無法證明安全的 destructive Undo 停用。
                put("last_checkout_json", "")
            }
            if (!has("reversal_log_json")) put("reversal_log_json", "[]")
            put("payloadSchema", 3)
        }
}
