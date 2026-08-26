package com.lambliver.stallpos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.ui.pos.LocalOperationsBottomSheet
import com.lambliver.stallpos.ui.theme.StallPosTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp")
class LocalOperationsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyState_rendersAt390dp() {
        composeRule.setContent {
            StallPosTheme {
                Box(Modifier.width(390.dp).height(844.dp)) {
                    LocalOperationsBottomSheet(PosUiState(isLoading = false), {}, {})
                }
            }
        }
        composeRule.onNodeWithText("活動與庫存").assertIsDisplayed()
        composeRule.onNodeWithText("尚未建立活動", substring = true).assertIsDisplayed()
    }

    @Test
    fun longNamesAndLargeInventory_renderAt390dp() {
        val event = MarketEvent.create(
            name = "極端長名稱活動".repeat(8),
            type = MarketEventType.MARKET,
            startAtMillis = 1,
            endAtMillis = 2,
            timezone = "Asia/Taipei",
        )
        val products = (1..100).map {
            Product("p$it", "極端長商品名稱".repeat(8) + it, 100, stock = 1_000_000, cost = if (it == 1) null else 20)
        }
        val levels = products.map { InventoryLevel(it.id, InventoryLocation.General, 1_000_000, 1) }
        val state = PosUiState(
            isLoading = false,
            products = products.toImmutableList(),
            events = listOf(event).toImmutableList(),
            inventoryLevels = levels.toImmutableList(),
            sync = SyncUiState(SyncUiStatus.ERROR, message = "網路錯誤"),
        )
        composeRule.setContent {
            StallPosTheme {
                Box(Modifier.width(390.dp).height(844.dp)) {
                    LocalOperationsBottomSheet(state, {}, {})
                }
            }
        }
        composeRule.onNodeWithText("網路錯誤").assertIsDisplayed()
        composeRule.onNodeWithText("有商品沒填成本", substring = true).assertIsDisplayed()
    }
}
