package com.lambliver.stallpos.ui.sponsor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** 以外部瀏覽器開啟綠界付款頁；成功回傳 true。 */
fun Context.openSponsorPayment(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
