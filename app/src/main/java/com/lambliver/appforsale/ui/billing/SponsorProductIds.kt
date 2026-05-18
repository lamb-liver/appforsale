package com.lambliver.appforsale.ui.billing

object SponsorProductIds {
    /** 請於 Play Console 建立相同 productId 的「應用程式內商品」（一次性購買） */
    const val TIER_SMALL = "sponsor_tier_small"
    const val TIER_MEDIUM = "sponsor_tier_medium"
    const val TIER_LARGE = "sponsor_tier_large"

    fun allInApp(): List<String> = listOf(TIER_SMALL, TIER_MEDIUM, TIER_LARGE)
}
