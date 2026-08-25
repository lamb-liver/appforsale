package com.lambliver.stallpos

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import com.lambliver.stallpos.domain.PaymentMethod
import com.lambliver.stallpos.domain.SaleCheckoutLine
import com.lambliver.stallpos.domain.SaleRecord
import com.lambliver.stallpos.ui.pos.DashboardBottomSheet
import com.lambliver.stallpos.ui.theme.StallPosTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp")
class DashboardBottomSheetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun recentTransaction_opensSnapshotDetail() {
        val sale = SaleRecord(
            id = "sale-1",
            tsMillis = 1,
            dateKey = "2026-08-25",
            subtotal = 120,
            discount = 0,
            total = 120,
            cartSnapshot = mapOf("p1" to 2),
            paymentMethod = PaymentMethod.CASH,
            receiptNumber = "TPE-0001",
            checkoutLines = listOf(SaleCheckoutLine.Product("p1", 2, 60, 120, "徽章")),
        )
        composeRule.setContent {
            StallPosTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                LaunchedEffect(Unit) { sheetState.expand() }
                DashboardBottomSheet(
                    sheetState = sheetState,
                    todayLogs = persistentListOf(sale),
                    allLogs = persistentListOf(sale),
                    reversals = persistentListOf(),
                    products = persistentListOf(),
                    currency = NumberFormat.getCurrencyInstance(Locale.TAIWAN),
                    onDismiss = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("recent-transaction-sale-1")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.waitUntil(1_000) {
            composeRule.onAllNodesWithTag("transaction-detail-sale-1").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
