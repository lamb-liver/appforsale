package com.lambliver.appforsale.ui

import com.lambliver.appforsale.BuildConfig

/**
 * Anchored adaptive banner 廣告單元 ID。
 * - **debug**：Google 官方測試版位（避免開發時誤觸正式廣告）。
 * - **release**：AdMob 後台建立的正式橫幅版位，見 `BuildConfig.ADMOB_BANNER_AD_UNIT_ID`。
 */
object AdMobConfig {
    /** 頂端錨點適應型橫幅（與 `TopAnchoredAdaptiveBanner` 搭配）。 */
    val ANCHORED_ADAPTIVE_BANNER_AD_UNIT_ID: String = BuildConfig.ADMOB_BANNER_AD_UNIT_ID
}
