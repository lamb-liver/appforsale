package com.lambliver.stallpos

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.lambliver.stallpos.data.*
import com.lambliver.stallpos.domain.PosCart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyBusinessRetirementTest {
    @Test
    fun validPayload_isSafeToRetire() {
        assertTrue(validLegacyPreferences().isLegacyBusinessDataSafeToRetire())
    }

    @Test
    fun unknownCartSchema_isPreserved() {
        val prefs = validLegacyPreferences().apply {
            this[LEGACY_CART_JSON_KEY] = """{"v":99,"p":{},"b":{}}"""
        }

        assertFalse(prefs.isLegacyBusinessDataSafeToRetire())
    }

    @Test
    fun negativeAggregate_isPreserved() {
        val prefs = validLegacyPreferences().apply { this[LEGACY_TOTAL_SALES_KEY] = -1L }

        assertFalse(prefs.isLegacyBusinessDataSafeToRetire())
    }

    private fun validLegacyPreferences() = mutablePreferencesOf(
        LEGACY_PRODUCTS_JSON_KEY to "[]",
        LEGACY_CATEGORIES_JSON_KEY to "[]",
        LEGACY_BUNDLE_CATEGORIES_JSON_KEY to "[]",
        LEGACY_BUNDLES_JSON_KEY to "[]",
        LEGACY_CART_JSON_KEY to encodePosCartJson(PosCart()),
        LEGACY_TOTAL_SALES_KEY to 0L,
        LEGACY_TX_COUNT_KEY to 0L,
        SALES_LOG_JSON_KEY to "[]",
        SALES_REVERSAL_LOG_JSON_KEY to "[]",
        LAST_CHECKOUT_JSON_KEY to "",
    )
}
