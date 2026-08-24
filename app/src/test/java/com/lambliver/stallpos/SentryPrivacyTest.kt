package com.lambliver.stallpos

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Request
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SentryPrivacyTest {
    @Test
    fun configurationDisablesPiiAndScrubsEventPayloads() {
        val options = SentryAndroidOptions()
        configureSentry(options, "")
        val event = SentryEvent().apply {
            user = User().apply { email = "owner@example.com" }
            request = Request().apply { data = "token" }
            breadcrumbs = listOf(Breadcrumb("product payload"))
            contexts["transaction"] = "full sale"
        }

        val cleaned = requireNotNull(options.beforeSend).execute(event, Hint())

        assertFalse(options.isEnabled)
        assertFalse(options.isSendDefaultPii)
        assertEquals(0, options.maxBreadcrumbs)
        assertNull(cleaned?.user)
        assertNull(cleaned?.request)
        assertEquals(emptyList<Breadcrumb>(), cleaned?.breadcrumbs)
        assertEquals(0, cleaned?.contexts?.size)
    }
}
