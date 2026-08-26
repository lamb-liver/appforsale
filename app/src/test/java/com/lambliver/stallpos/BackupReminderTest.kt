package com.lambliver.stallpos

import com.lambliver.stallpos.domain.BackupReminder
import com.lambliver.stallpos.domain.MarketEvent
import com.lambliver.stallpos.domain.MarketEventType
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.domain.SaleRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReminderTest {
    private val now = 1_000_000_000L
    private val product = Product("p1", "徽章", 100)

    @Test
    fun noBusinessData_hidden() {
        assertFalse(
            BackupReminder.shouldShow(
                nowMillis = now,
                hasBusinessData = false,
                firstBusinessDataAtMillis = now,
                lastSuccessfulBackupAtMillis = null,
                lastCanonicalWriteAtMillis = now,
                snoozed = false,
            ),
        )
    }

    @Test
    fun firstExportWithinGrace_hidden() {
        assertFalse(
            BackupReminder.shouldShow(
                nowMillis = now,
                hasBusinessData = true,
                firstBusinessDataAtMillis = now - BackupReminder.FIRST_EXPORT_GRACE_MS + 1,
                lastSuccessfulBackupAtMillis = null,
                lastCanonicalWriteAtMillis = now,
                snoozed = false,
            ),
        )
    }

    @Test
    fun firstExportAfterGrace_shown() {
        assertTrue(
            BackupReminder.shouldShow(
                nowMillis = now,
                hasBusinessData = true,
                firstBusinessDataAtMillis = now - BackupReminder.FIRST_EXPORT_GRACE_MS,
                lastSuccessfulBackupAtMillis = null,
                lastCanonicalWriteAtMillis = now,
                snoozed = false,
            ),
        )
    }

    @Test
    fun snoozed_hiddenUntilNextWrite() {
        assertFalse(
            BackupReminder.shouldShow(
                nowMillis = now,
                hasBusinessData = true,
                firstBusinessDataAtMillis = now - BackupReminder.FIRST_EXPORT_GRACE_MS,
                lastSuccessfulBackupAtMillis = null,
                lastCanonicalWriteAtMillis = now,
                snoozed = true,
            ),
        )
    }

    @Test
    fun afterBackupWithNoNewerWrite_hidden() {
        assertFalse(
            BackupReminder.shouldShow(
                nowMillis = now,
                hasBusinessData = true,
                firstBusinessDataAtMillis = 1,
                lastSuccessfulBackupAtMillis = 50,
                lastCanonicalWriteAtMillis = 50,
                snoozed = false,
            ),
        )
    }

    @Test
    fun afterBackupWithNewerCanonicalWrite_shown() {
        assertTrue(
            BackupReminder.shouldShow(
                nowMillis = now,
                hasBusinessData = true,
                firstBusinessDataAtMillis = 1,
                lastSuccessfulBackupAtMillis = 50,
                lastCanonicalWriteAtMillis = 51,
                snoozed = false,
            ),
        )
    }

    @Test
    fun hasBusinessData_ignoresEmptyCatalog() {
        assertFalse(BackupReminder.hasBusinessData(emptyList(), emptyList(), emptyList(), emptyList()))
        assertTrue(BackupReminder.hasBusinessData(listOf(product), emptyList(), emptyList(), emptyList()))
        assertTrue(
            BackupReminder.hasBusinessData(
                emptyList(),
                emptyList(),
                listOf(SaleRecord("s", 1, "d", 10, 0, 10, emptyMap())),
                emptyList(),
            ),
        )
        assertTrue(
            BackupReminder.hasBusinessData(
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(
                    MarketEvent.create(
                        name = "市集",
                        type = MarketEventType.MARKET,
                        startAtMillis = 1,
                        endAtMillis = 2,
                        timezone = "Asia/Taipei",
                    ),
                ),
            ),
        )
    }
}
