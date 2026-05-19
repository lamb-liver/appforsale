package com.lambliver.stallpos

import com.lambliver.stallpos.data.PosStore
import com.lambliver.stallpos.data.parseBackupEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 備份封包外層驗證；純 JVM，不依賴 Android `org.json`。 */
class PosBackupPayloadTest {

    private fun envelope(payloadJson: String = "{}", schemaVersion: Int = 1): String =
        """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":$schemaVersion,"payload":$payloadJson}"""

    @Test
    fun parse_returnsPayload_andStripsBom() {
        val payload = """{"products_json":"[]","cart_json":"{}"}"""
        val text = "\uFEFF " + envelope(payload) + " "
        val out = parseBackupEnvelope(text)
        assertEquals(PosStore.BACKUP_FORMAT_ID, out.format)
        assertEquals(1, out.schemaVersion)
        assertEquals(payload, out.payloadJson)
    }

    @Test
    fun parse_stripsBomAndLeadingNewline() {
        val json = envelope()
        val out = parseBackupEnvelope("\uFEFF\n$json")
        assertEquals(1, out.schemaVersion)
    }

    @Test
    fun parse_wrongFormat_throws() {
        val bad = """{"format":"unknown","schemaVersion":1,"payload":{}}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parseBackupEnvelope(bad)
        }
        assertEquals("不是此 App 的備份檔", ex.message)
    }

    @Test
    fun parse_schemaTooLow_messageIncludesRange() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parseBackupEnvelope(envelope(schemaVersion = 0))
        }
        assertEquals(
            "備份版本 0 超出支援範圍（1–${PosStore.BACKUP_SCHEMA_VERSION}）",
            ex.message,
        )
    }

    @Test
    fun parse_schemaTooHigh_messageIncludesRange() {
        val tooHigh = PosStore.BACKUP_SCHEMA_VERSION + 1
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parseBackupEnvelope(envelope(schemaVersion = tooHigh))
        }
        assertEquals(
            "備份版本 $tooHigh 超出支援範圍（1–${PosStore.BACKUP_SCHEMA_VERSION}）",
            ex.message,
        )
    }

    @Test
    fun parse_missingSchemaVersion_treatedAsZeroAndRejected() {
        val json = """{"format":"${PosStore.BACKUP_FORMAT_ID}","payload":{}}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parseBackupEnvelope(json)
        }
        assertEquals(
            "備份版本 0 超出支援範圍（1–${PosStore.BACKUP_SCHEMA_VERSION}）",
            ex.message,
        )
    }

    @Test
    fun parse_schemaVersionFloat_rejected() {
        val json =
            """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":1.0,"payload":{}}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parseBackupEnvelope(json)
        }
        assertEquals("備份版本格式無效", ex.message)
    }

    @Test
    fun parse_missingPayload_throws() {
        val root =
            """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":1}"""
        assertThrows(IllegalStateException::class.java) {
            parseBackupEnvelope(root)
        }
    }

    @Test
    fun parse_payloadAsString_rejected() {
        val json =
            """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":1,"payload":"oops"}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parseBackupEnvelope(json)
        }
        assertEquals("備份 payload 必須為物件", ex.message)
    }

    @Test
    fun parse_payloadKeyOrderIndependent() {
        val json =
            """{"schemaVersion":1,"payload":{"x":1},"format":"${PosStore.BACKUP_FORMAT_ID}"}"""
        val out = parseBackupEnvelope(json)
        assertTrue(out.payloadJson.contains("\"x\":1"))
    }

    @Test
    fun parse_payloadAsLastKey_withoutTrailingComma() {
        val json =
            """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":1,"payload":{}}"""
        val out = parseBackupEnvelope(json)
        assertEquals("{}", out.payloadJson)
    }

    @Test
    fun parse_payloadWithEscapedQuoteAndBrace() {
        val payload = """{"note":"say \"hi\"","brace":"}"}"""
        val json =
            """{"format":"${PosStore.BACKUP_FORMAT_ID}","schemaVersion":1,"payload":$payload}"""
        val out = parseBackupEnvelope(json)
        assertEquals(payload, out.payloadJson)
    }
}
