package com.lambliver.appforsale.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mobile Ads 僅在 UMP 流程結束且 [com.google.android.ump.ConsentInformation.canRequestAds] 為 true 時初始化。
 * 此旗標供 UI 決定是否顯示廣告版位。
 */
object AdsGate {
    private val _mobileAdsReady = MutableStateFlow(false)
    val mobileAdsReady: StateFlow<Boolean> = _mobileAdsReady.asStateFlow()

    internal fun setMobileAdsReady(ready: Boolean) {
        _mobileAdsReady.value = ready
    }
}
