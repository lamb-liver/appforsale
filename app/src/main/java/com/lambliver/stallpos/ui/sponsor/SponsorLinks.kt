package com.lambliver.stallpos.ui.sponsor

/**
 * 綠界各檔付款頁 URL（申請完成後填入）。
 * 每檔對應固定金額；連結須在綠界後台建立相同金額的收款／訂單。
 */
object SponsorLinks {

    /** 打道音遊 — 30 元 */
    const val ECPAY_URL_TIER_SMALL: String = ""

    /** 喝杯咖啡 — 99 元 */
    const val ECPAY_URL_TIER_MEDIUM: String = ""

    /** 吃個便當 — 150 元 */
    const val ECPAY_URL_TIER_LARGE: String = ""

    val tiers: List<SponsorTier> = listOf(
        SponsorTier(shortLabel = "打道音遊", amountTwd = 30, paymentUrl = ECPAY_URL_TIER_SMALL),
        SponsorTier(shortLabel = "喝杯咖啡", amountTwd = 99, paymentUrl = ECPAY_URL_TIER_MEDIUM),
        SponsorTier(shortLabel = "吃個便當", amountTwd = 150, paymentUrl = ECPAY_URL_TIER_LARGE),
    )

    fun isConfigured(): Boolean = tiers.any { it.isConfigured }
}

data class SponsorTier(
    val shortLabel: String,
    val amountTwd: Int,
    val paymentUrl: String,
) {
    /** 例：打道音遊（贊助開發者30元） */
    val buttonLabel: String get() = "$shortLabel（贊助開發者${amountTwd}元）"

    val isConfigured: Boolean get() = paymentUrl.isNotBlank()
}
