package com.lambliver.stallpos

import android.app.Application
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions

class StallPosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SentryAndroid.init(this) { options -> configureSentry(options, BuildConfig.SENTRY_DSN) }
    }
}

internal fun configureSentry(options: SentryAndroidOptions, dsn: String) {
    options.dsn = dsn
    options.isEnabled = dsn.isNotBlank()
    options.isSendDefaultPii = false
    options.tracesSampleRate = 0.0
    options.maxBreadcrumbs = 0
    options.isAttachScreenshot = false
    options.isAttachViewHierarchy = false
    options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
        event.user = null
        event.request = null
        event.breadcrumbs = emptyList()
        event.contexts.clear()
        event
    }
}
