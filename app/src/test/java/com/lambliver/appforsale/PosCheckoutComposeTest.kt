package com.lambliver.appforsale

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lambliver.appforsale.domain.CheckoutSheetPricingSnapshot
import com.lambliver.appforsale.ui.theme.AppforsaleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 結帳金額鎖定快照之 Compose 測試（公式層）。
 * BottomSheet 互動見 [CheckoutBottomSheetComposeTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PosCheckoutComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pricingSnapshot_surfaceReceivable_displayed() {
        val snap = CheckoutSheetPricingSnapshot(catalogSubtotal = 100L, paymentAdjustment = 10L)
        composeRule.setContent {
            AppforsaleTheme {
                Text("應收 ${snap.surfaceReceivable}")
            }
        }
        composeRule.onNodeWithText("應收 110").assertIsDisplayed()
    }

    @Test
    fun pricingSnapshot_clampsNegativeReceivableToZero() {
        val snap = CheckoutSheetPricingSnapshot(catalogSubtotal = 50L, paymentAdjustment = -100L)
        composeRule.setContent {
            AppforsaleTheme {
                Text("應收 ${snap.surfaceReceivable}")
            }
        }
        composeRule.onNodeWithText("應收 0").assertIsDisplayed()
    }
}
