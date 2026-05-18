package com.lambliver.appforsale.ui.ads

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

private const val LOG_TAG = "AppforsaleAdMob"

/** 對應 AdMob LoadAdError.code，方便在 Logcat 篩選 `AppforsaleAdMob`。 */
@Suppress("MagicNumber")
private fun loadAdErrorLabel(code: Int): String = when (code) {
    0 -> "INTERNAL_ERROR(0)"
    1 -> "INVALID_REQUEST(1) — 檢查 AndroidManifest APPLICATION_ID"
    2 -> "NETWORK_ERROR(2) — 檢查模擬器／裝置網路"
    3 -> "NO_FILL(3) — 當下無測試廣告，稍後重試或換實機"
    else -> "OTHER($code)"
}

@Composable
fun TopAnchoredAdaptiveBanner(
    adUnitId: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            AdView(ctx).apply {
                val adWidthDp = ctx.resources.configuration.screenWidthDp
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidthDp),
                )
                this.adUnitId = adUnitId
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        Log.d(LOG_TAG, "Banner onAdLoaded adUnitId=$adUnitId")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(
                            LOG_TAG,
                            "Banner onAdFailedToLoad ${loadAdErrorLabel(error.code)} " +
                                "domain=${error.domain} message=${error.message} adUnitId=$adUnitId",
                        )
                    }

                    override fun onAdImpression() {
                        Log.d(LOG_TAG, "Banner onAdImpression adUnitId=$adUnitId")
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .then(modifier),
        onRelease = { it.destroy() },
    )
}
