package com.lambliver.stallpos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lambliver.stallpos.ui.sponsor.openSponsorPayment
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SponsorPaymentTest {

    @Test
    fun openSponsorPayment_blankUrl_returnsFalse() {
        val context: Application = ApplicationProvider.getApplicationContext()
        assertFalse(context.openSponsorPayment(""))
        assertFalse(context.openSponsorPayment("   "))
    }
}
