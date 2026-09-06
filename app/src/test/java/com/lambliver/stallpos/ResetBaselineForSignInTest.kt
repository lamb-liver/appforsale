package com.lambliver.stallpos

import com.lambliver.stallpos.data.CloudLoginIntent
import com.lambliver.stallpos.data.resetBaselineForSignIn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetBaselineForSignInTest {
    @Test
    fun additionalJoinNeverDumpsCatalog() {
        assertFalse(
            resetBaselineForSignIn(
                forceDevice = false,
                intent = CloudLoginIntent.SIGN_IN,
                additionalDevice = true,
                previousUser = null,
                sessionUserId = "u2",
            ),
        )
        assertFalse(
            resetBaselineForSignIn(
                forceDevice = false,
                intent = CloudLoginIntent.SIGN_IN,
                additionalDevice = true,
                previousUser = "other",
                sessionUserId = "u2",
            ),
        )
    }

    @Test
    fun firstDeviceAndTakeoverStillDump() {
        assertTrue(
            resetBaselineForSignIn(
                forceDevice = false,
                intent = CloudLoginIntent.SIGN_IN,
                additionalDevice = false,
                previousUser = null,
                sessionUserId = "u1",
            ),
        )
        assertTrue(
            resetBaselineForSignIn(
                forceDevice = true,
                intent = CloudLoginIntent.SIGN_IN,
                additionalDevice = false,
                previousUser = "u1",
                sessionUserId = "u1",
            ),
        )
    }
}
