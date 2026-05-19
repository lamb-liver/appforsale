package com.lambliver.stallpos

import com.lambliver.stallpos.ui.sponsor.SponsorLinks
import com.lambliver.stallpos.ui.sponsor.SponsorTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorLinksTest {

    @Test
    fun buttonLabel_includesAmountTwd() {
        val tier = SponsorTier(shortLabel = "打道音遊", amountTwd = 30, paymentUrl = "")
        assertEquals("打道音遊（贊助開發者30元）", tier.buttonLabel)
    }

    @Test
    fun isConfigured_falseWhenAllUrlsBlank() {
        assertFalse(SponsorLinks.isConfigured())
        SponsorLinks.tiers.forEach { assertFalse(it.isConfigured) }
    }

    @Test
    fun tierAmounts_matchProductCopy() {
        assertEquals(30, SponsorLinks.tiers[0].amountTwd)
        assertEquals(99, SponsorLinks.tiers[1].amountTwd)
        assertEquals(150, SponsorLinks.tiers[2].amountTwd)
    }

    @Test
    fun allTierButtonLabels_includeFixedAmounts() {
        assertEquals("打道音遊（贊助開發者30元）", SponsorLinks.tiers[0].buttonLabel)
        assertEquals("喝杯咖啡（贊助開發者99元）", SponsorLinks.tiers[1].buttonLabel)
        assertEquals("吃個便當（贊助開發者150元）", SponsorLinks.tiers[2].buttonLabel)
    }

    @Test
    fun tier_isConfigured_whenPaymentUrlSet() {
        val configured = SponsorTier(shortLabel = "測試", amountTwd = 1, paymentUrl = "https://pay.example/30")
        assertTrue(configured.isConfigured)
    }
}
