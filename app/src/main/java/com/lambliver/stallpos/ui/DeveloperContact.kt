package com.lambliver.stallpos.ui

import android.content.Intent
import android.net.Uri

object DeveloperContact {
    const val EMAIL = "lambliver.dev@gmail.com"
}

internal fun reportToDeveloperIntent(body: String): Intent =
    Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:${DeveloperContact.EMAIL}")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(DeveloperContact.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "StallPOS 回報")
        putExtra(Intent.EXTRA_TEXT, body)
    }
