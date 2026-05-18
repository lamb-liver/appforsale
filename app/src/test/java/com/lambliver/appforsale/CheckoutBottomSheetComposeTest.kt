package com.lambliver.appforsale

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lambliver.appforsale.domain.PaymentMethod
import com.lambliver.appforsale.ui.pos.CheckoutBottomSheet
import com.lambliver.appforsale.ui.pos.CheckoutSheetTestTags
import com.lambliver.appforsale.ui.theme.AppforsaleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CheckoutBottomSheetComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val currency = NumberFormat.getCurrencyInstance(Locale.TAIWAN)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun launchSheet(
        total: Long = 120L,
        onConfirm: (PaymentMethod, Long) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            AppforsaleTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                LaunchedEffect(Unit) { sheetState.expand() }
                CheckoutBottomSheet(
                    sheetState = sheetState,
                    total = total,
                    currency = currency,
                    onDismiss = {},
                    onConfirm = onConfirm,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun showsReceivable_forLockedTotal() {
        launchSheet(total = 88L)
        composeRule.onNodeWithTag(CheckoutSheetTestTags.RECEIVABLE).assertIsDisplayed()
        composeRule.onNodeWithText("應收", substring = true).assertIsDisplayed()
    }

    @Test
    fun digitalPayment_confirmWithoutCashInput() {
        var method: PaymentMethod? = null
        launchSheet(onConfirm = { m, _ -> method = m })

        composeRule.onNodeWithText("行動支付").performClick()
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CONFIRM).performClick()

        assertEquals(PaymentMethod.DIGITAL, method)
    }

    @Test
    fun cashPayment_insufficient_doesNotConfirm() {
        var confirmCount = 0
        launchSheet(total = 120L, onConfirm = { _, _ -> confirmCount++ })

        composeRule.onNodeWithTag(CheckoutSheetTestTags.CASH_INPUT).performTextInput("50")
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CONFIRM).performClick()

        assertEquals(0, confirmCount)
    }

    @Test
    fun cashPayment_sufficient_confirmsWithTip() {
        var tip = -1L
        launchSheet(total = 100L, onConfirm = { _, t -> tip = t })

        composeRule.onNodeWithTag(CheckoutSheetTestTags.CASH_INPUT).performTextInput("100")
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CONFIRM).performClick()

        assertEquals(0L, tip)
    }
}
