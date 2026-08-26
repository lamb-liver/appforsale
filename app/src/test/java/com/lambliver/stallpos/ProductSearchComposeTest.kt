package com.lambliver.stallpos

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.ui.pos.ProductQuickRow
import com.lambliver.stallpos.ui.theme.StallPosTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp")
class ProductSearchComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun search_filtersProductNamesIgnoringCase() {
        composeRule.setContent {
            StallPosTheme {
                ProductQuickRow(
                    products = persistentListOf(
                        Product("a", "Apple Badge", 100),
                        Product("b", "Banana Sticker", 50),
                    ),
                    categories = persistentListOf(),
                    cart = persistentMapOf(),
                    currency = NumberFormat.getCurrencyInstance(Locale.TAIWAN),
                    searchText = "apple",
                    onTap = {},
                    onLongPress = {},
                    onLongPressCategory = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Apple Badge").assertCountEquals(1)
        composeRule.onAllNodesWithText("Banana Sticker").assertCountEquals(0)
    }

    @Test
    fun inactiveProducts_areHiddenFromQuickCheckout() {
        composeRule.setContent {
            StallPosTheme {
                ProductQuickRow(
                    products = persistentListOf(
                        Product("a", "在售", 100),
                        Product("b", "已停用", 50, isActive = false),
                    ),
                    categories = persistentListOf(),
                    cart = persistentMapOf(),
                    currency = NumberFormat.getCurrencyInstance(Locale.TAIWAN),
                    onTap = {},
                    onLongPress = {},
                    onLongPressCategory = {},
                )
            }
        }
        composeRule.onAllNodesWithText("在售").assertCountEquals(1)
        composeRule.onAllNodesWithText("已停用").assertCountEquals(0)
    }
}
