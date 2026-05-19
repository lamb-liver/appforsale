package com.lambliver.stallpos

import com.lambliver.stallpos.data.BackupMigration
import com.lambliver.stallpos.data.PosStore
import com.lambliver.stallpos.data.parseBackupEnvelope
import com.lambliver.stallpos.data.parseValidatedBackupPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupMigrationTest {

    private fun v1Envelope(payload: JSONObject = v1Payload()): String =
        """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":1,"payload":$payload}"""

    private fun v1Payload(): JSONObject =
        JSONObject()
            .put("products_json", "[]")
            .put("cart_json", "{}")

    @Test
    fun migrateV1ToV2_addsPayloadSchema() {
        val migrated = BackupMigration.migratePayloadToCurrent(1, v1Payload())
        assertEquals(2, migrated.getInt("payloadSchema"))
    }

    @Test
    fun parseValidatedBackupPayload_migratesSchema1Backup() {
        val out = parseValidatedBackupPayload(v1Envelope())
        assertEquals(2, out.getInt("payloadSchema"))
    }

    @Test
    fun parseBackupEnvelope_acceptsSchema2Export() {
        val payload = v1Payload().put("payloadSchema", 2)
        val json = """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":2,"payload":$payload}"""
        val envelope = parseBackupEnvelope(json)
        assertEquals(2, envelope.schemaVersion)
        val parsed = parseValidatedBackupPayload(json)
        assertEquals(2, parsed.getInt("payloadSchema"))
    }

    @Test
    fun migrateV2_isIdentity() {
        val payload = v1Payload().put("payloadSchema", 2)
        val same = BackupMigration.migratePayloadToCurrent(2, payload)
        assertEquals(2, same.getInt("payloadSchema"))
    }

    /** migrateV1ToV2 冪等：同一 payload 連跑兩次結果相同（模擬還原路徑重入）。 */
    @Test
    fun migrateV1ToV2_idempotent_secondPassUnchanged() {
        val first = BackupMigration.migratePayloadToCurrent(1, v1Payload())
        val second = BackupMigration.migratePayloadToCurrent(1, JSONObject(first.toString()))
        assertEquals(first.toString(), second.toString())
    }

    /** 已有 payloadSchema 時不覆寫（即使 envelope 仍標 1）。 */
    @Test
    fun migrateV1ToV2_doesNotOverwriteExistingPayloadSchema() {
        val already = v1Payload().put("payloadSchema", 2)
        val out = BackupMigration.migratePayloadToCurrent(1, already)
        assertEquals(2, out.getInt("payloadSchema"))
        assertEquals(already.toString(), out.toString())
    }

    /** v2 匯出檔經 parseValidatedBackupPayload 再解析，payload 不變。 */
    @Test
    fun parseValidatedBackupPayload_schema2_doubleParseIsStable() {
        val payload = v1Payload().put("payloadSchema", 2)
        val json = """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":2,"payload":$payload}"""
        val first = parseValidatedBackupPayload(json)
        val second = parseValidatedBackupPayload(json)
        assertEquals(first.toString(), second.toString())
    }
}
