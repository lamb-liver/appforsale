package com.lambliver.stallpos

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import com.lambliver.stallpos.domain.PaymentMethod
import com.lambliver.stallpos.ui.feedback.PosFeedbackManager
import com.lambliver.stallpos.ui.pos.CheckoutBottomSheet
import com.lambliver.stallpos.ui.pos.CheckoutSheetTestTags
import com.lambliver.stallpos.ui.theme.StallPosTheme
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

    private val noopFeedback = PosFeedbackManager.noop()

    @OptIn(ExperimentalMaterial3Api::class)
    private fun launchSheet(
        total: Long = 120L,
        onConfirm: (PaymentMethod, Long) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            StallPosTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                LaunchedEffect(Unit) { sheetState.expand() }
                CheckoutBottomSheet(
                    sheetState = sheetState,
                    total = total,
                    currency = currency,
                    onDismiss = {},
                    onConfirm = onConfirm,
                    feedback = noopFeedback,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
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

        composeRule.onNodeWithTag(CheckoutSheetTestTags.DIGITAL_PAYMENT)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CheckoutSheetTestTags.DIGITAL_PAYMENT).assertIsSelected()
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CONFIRM)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(PaymentMethod.DIGITAL, method)
    }

    @Test
    fun cashPayment_insufficient_doesNotConfirm() {
        var confirmCount = 0
        launchSheet(total = 120L, onConfirm = { _, _ -> confirmCount++ })

        composeRule.onNodeWithTag(CheckoutSheetTestTags.CASH_INPUT).performTextReplacement("50")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CASH_INPUT).assertTextEquals("收款金額", "50")
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CONFIRM)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(0, confirmCount)
    }

    @Test
    fun cashPayment_sufficient_confirmsWithTip() {
        var tip = -1L
        launchSheet(total = 100L, onConfirm = { _, t -> tip = t })

        composeRule.onNodeWithTag(CheckoutSheetTestTags.CASH_INPUT).performTextReplacement("100")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CASH_INPUT).assertTextEquals("收款金額", "100")
        composeRule.onNodeWithTag(CheckoutSheetTestTags.CONFIRM)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(0L, tip)
    }
}
