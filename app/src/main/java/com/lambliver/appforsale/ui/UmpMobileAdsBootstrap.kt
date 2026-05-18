package com.lambliver.appforsale.ui

import androidx.activity.ComponentActivity
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * 先完成 UMP 同意資訊更新與必要之同意表單，僅在可請求廣告時才初始化 Mobile Ads SDK。
 * 回呼可能發生在背景執行緒，後續 UI／Ads 初始化統一走回主執行緒。
 */
object UmpMobileAdsBootstrap {

    fun attach(activity: ComponentActivity) {
        AdsGate.setMobileAdsReady(false)
        val params = ConsentRequestParameters.Builder().build()

        UserMessagingPlatform.getConsentInformation(activity).requestConsentInfoUpdate(
            activity,
            params,
            {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
                            if (consentInformation.canRequestAds()) {
                                val appCtx = activity.applicationContext
                                MobileAds.initialize(appCtx) {
                                    activity.runOnUiThread { AdsGate.setMobileAdsReady(true) }
                                }
                            } else {
                                AdsGate.setMobileAdsReady(false)
                            }
                        }
                    }
                }
            },
            { activity.runOnUiThread { AdsGate.setMobileAdsReady(false) } },
        )
    }
}
