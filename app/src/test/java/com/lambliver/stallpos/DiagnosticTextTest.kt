package com.lambliver.stallpos

import com.lambliver.stallpos.data.SyncDiagnosticInfo
import com.lambliver.stallpos.domain.SyncUiState
import com.lambliver.stallpos.domain.SyncUiStatus
import com.lambliver.stallpos.ui.buildDiagnosticText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTextTest {
    @Test
    fun output_containsOnlySupportMetadata() {
        val text = buildDiagnosticText(
            appVersion = "2.1.0",
            androidVersion = "16",
            sync = SyncUiState(SyncUiStatus.BLOCKED, pendingCount = 3, blockedCount = 1),
            info = SyncDiagnosticInfo(
                deviceId = "anonymous-device",
                lastSyncAtMillis = 1,
                recentRequestIds = listOf("request-1"),
                recentErrorCodes = listOf("SERVER_CONFLICT"),
            ),
        )

        assertTrue(text.contains("Pending: 3"))
        assertTrue(text.contains("request-1"))
        assertTrue(text.contains("SERVER_CONFLICT"))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("商品"))
    }
}
