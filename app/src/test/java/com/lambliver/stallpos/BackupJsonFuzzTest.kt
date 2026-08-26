package com.lambliver.stallpos

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lambliver.stallpos.data.FakePosPersistence
import com.lambliver.stallpos.data.PosBackupSafAdapter
import com.lambliver.stallpos.data.PosPersistSnapshot
import com.lambliver.stallpos.domain.IntegrityAudit
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.domain.SaleRecord
import com.lambliver.stallpos.domain.SaleReversal
import com.lambliver.stallpos.domain.ReversalReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupJsonFuzzTest {
    private val snapshot = PosPersistSnapshot(
        products = listOf(Product("p1", "徽章", 100, stock = 3, isActive = false)),
        salesLog = listOf(SaleRecord("sale-1", 1, "2026-08-26", 100, 0, 100, mapOf("p1" to 1))),
        reversalLog = listOf(SaleReversal("rev-1", "sale-1", 2, ReversalReason.UNDO_LAST_CHECKOUT)),
    )

    @Test
    fun roundTrip_preservesBusinessDataAndPassesAudit() = runBlocking {
        val source = FakePosPersistence(snapshot)
        val json = source.exportFullBackupJson()
        val dest = FakePosPersistence()
        dest.restoreFullBackupJson(json).getOrThrow()
        val restored = dest.snapshot.first()
        assertEquals(snapshot.products, restored.products)
        assertEquals(snapshot.salesLog.single().id, restored.salesLog.single().id)
        assertEquals(snapshot.reversalLog.single().id, restored.reversalLog.single().id)
        assertTrue(IntegrityAudit.audit(dest.integritySnapshot()).ok)
    }

    @Test
    fun truncatedJson_failsAndLeavesOriginalState() = runBlocking {
        val persist = FakePosPersistence(snapshot)
        val before = persist.snapshot.first()
        val json = persist.exportFullBackupJson().dropLast(24)
        assertTrue(persist.restoreFullBackupJson(json).isFailure)
        assertEquals(before, persist.snapshot.first())
        assertTrue(IntegrityAudit.audit(persist.integritySnapshot()).ok)
    }

    @Test
    fun garbageText_failsAndLeavesOriginalState() = runBlocking {
        val persist = FakePosPersistence(snapshot)
        val before = persist.snapshot.first()
        assertTrue(persist.restoreFullBackupJson("{not-a-backup").isFailure)
        assertTrue(persist.restoreFullBackupJson("").isFailure)
        assertEquals(before, persist.snapshot.first())
    }

    @Test
    fun oversizedSafStream_failsBeforeRestore() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File(app.cacheDir, "oversized-backup.json")
        file.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            var written = 0
            while (written <= PosBackupSafAdapter.MAX_BACKUP_BYTES) {
                out.write(chunk)
                written += chunk.size
            }
        }
        val result = PosBackupSafAdapter(FakePosPersistence(snapshot))
            .readUtf8(app.contentResolver, Uri.fromFile(file))
        file.delete()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("太大"))
    }

    @Test
    fun extraUnknownEnvelopeField_stillRestoresOrFailsCleanly() = runBlocking {
        val persist = FakePosPersistence(snapshot)
        val before = persist.snapshot.first()
        val json = persist.exportFullBackupJson().replaceFirst("{", """{"noise":true,""")
        val result = persist.restoreFullBackupJson(json)
        if (result.isFailure) {
            assertEquals(before, persist.snapshot.first())
        } else {
            assertEquals(snapshot.products, persist.snapshot.first().products)
            assertTrue(IntegrityAudit.audit(persist.integritySnapshot()).ok)
        }
    }
}
