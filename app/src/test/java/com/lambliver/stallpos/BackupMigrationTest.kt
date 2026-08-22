package com.lambliver.stallpos

import com.lambliver.stallpos.data.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupMigrationTest {

    private val legacySale =
        """{"ts":10,"date":"2026-05-13","subtotal":100,"discount":0,"total":100,"cart":{"p1":1},"bundles":{},"paymentMethod":"CASH","tipAmount":20,"lines":[],"stockDeductions":{"p1":1}}"""

    private val legacyLast =
        """{"ts":10,"total":120,"cart":{"p1":1},"bundles":{},"stockDeductions":{"p1":1}}"""

    private fun payload() = JSONObject()
        .put("products_json", "[]")
        .put("cart_json", "{}")
        .put("sales_log_json", "[$legacySale]")
        .put("last_checkout_json", legacyLast)

    private fun envelope(schema: Int, payload: JSONObject = payload()): String =
        """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":$schema,"payload":$payload}"""

    @Test
    fun schema1Backup_migratesThroughV2ToV3() {
        val out = parseValidatedBackupPayload(envelope(1))
        assertEquals(3, out.getInt("payloadSchema"))
        assertEquals("[]", out.getString("reversal_log_json"))
        assertTrue(decodeSalesRecordsJson(out.getString("sales_log_json")).single().id.isNotBlank())
        assertTrue(decodeLastCheckoutJson(out.getString("last_checkout_json"))!!.saleId.isNotBlank())
    }

    @Test
    fun schema2Backup_addsStableIdsAndLinksUniqueLastCheckout() {
        val source = payload().put("payloadSchema", 2)
        val first = parseValidatedBackupPayload(envelope(2, source))
        val second = parseValidatedBackupPayload(envelope(2, JSONObject(source.toString())))

        assertEquals(3, first.getInt("payloadSchema"))
        assertEquals(first.toString(), second.toString())
        val saleId = decodeSalesRecordsJson(first.getString("sales_log_json")).single().id
        assertEquals(saleId, decodeLastCheckoutJson(first.getString("last_checkout_json"))!!.saleId)
    }

    @Test
    fun schema3Backup_isIdentity() {
        val source = payload()
            .put("payloadSchema", 3)
            .put("reversal_log_json", "[]")
        val same = BackupMigration.migratePayloadToCurrent(3, source)
        assertEquals(source.toString(), same.toString())
    }

    @Test
    fun ambiguousLegacyLastCheckout_isClearedInsteadOfGuessed() {
        val source = payload()
            .put("payloadSchema", 2)
            .put("sales_log_json", "[$legacySale,$legacySale]")
        val out = BackupMigration.migratePayloadToCurrent(2, source)
        assertEquals("", out.getString("last_checkout_json"))
    }

    @Test
    fun malformedLegacySales_isPreservedAndUndoIsCleared() {
        val source = payload()
            .put("payloadSchema", 2)
            .put("sales_log_json", "not-json")
        val out = BackupMigration.migratePayloadToCurrent(2, source)
        assertEquals("not-json", out.getString("sales_log_json"))
        assertEquals("", out.getString("last_checkout_json"))
        assertEquals("[]", out.getString("reversal_log_json"))
    }

    @Test
    fun validExistingSaleId_remainsStableWhenMigrationRepeats() {
        val first = BackupMigration.migratePayloadToCurrent(2, payload().put("payloadSchema", 2))
        val saleId = decodeSalesRecordsJson(first.getString("sales_log_json")).single().id
        val again = BackupMigration.migratePayloadToCurrent(2, JSONObject(first.toString()).put("payloadSchema", 2))
        assertEquals(saleId, decodeSalesRecordsJson(again.getString("sales_log_json")).single().id)
        assertFalse(saleId.isBlank())
    }
}
