package com.lambliver.stallpos

import com.lambliver.stallpos.ui.DeveloperContact
import com.lambliver.stallpos.ui.reportToDeveloperIntent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeveloperContactTest {
    @Test
    fun reportIntent_targetsDeveloperEmail() {
        val intent = reportToDeveloperIntent("StallPOS 回報資訊\nPending: 1")
        assertEquals("lambliver.dev@gmail.com", DeveloperContact.EMAIL)
        assertTrue(intent.data.toString().startsWith("mailto:lambliver.dev@gmail.com"))
        assertArrayEquals(arrayOf("lambliver.dev@gmail.com"), intent.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL))
        assertEquals("StallPOS 回報", intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT))
        assertEquals("StallPOS 回報資訊\nPending: 1", intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
    }
}
