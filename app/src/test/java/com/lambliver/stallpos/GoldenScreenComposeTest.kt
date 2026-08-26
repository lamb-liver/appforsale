package com.lambliver.stallpos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.lambliver.stallpos.domain.PosUiState
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.ui.feedback.PosFeedbackManager
import com.lambliver.stallpos.ui.pos.CatalogTab
import com.lambliver.stallpos.ui.pos.PosMainScreen
import com.lambliver.stallpos.ui.pos.PosSettingsContent
import com.lambliver.stallpos.ui.pos.PosTourBounds
import com.lambliver.stallpos.ui.theme.StallPosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp")
class GoldenScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val currency = NumberFormat.getCurrencyInstance(Locale.TAIWAN)
    private val tourBounds = PosTourBounds(
        statsRow = mutableStateOf(null),
        fab = mutableStateOf(null),
        productRow = mutableStateOf(null),
        discountBtn = mutableStateOf(null),
        numpad = mutableStateOf(null),
    )

    @Test
    fun mainEmpty_rendersAt390dp() {
        composeRule.setContent {
            StallPosTheme {
                Box(Modifier.width(390.dp).height(844.dp)) {
                    PosMainScreen(
                        uiState = PosUiState(isLoading = false),
                        currency = currency,
                        catalogTab = CatalogTab.Products,
                        onCatalogTabChange = {},
                        numpadExpanded = false,
                        onNumpadExpandedChange = {},
                        collapseNumpad = {},
                        onUiEvent = {},
                        settingsMenuExpanded = false,
                        onSettingsMenuExpandedChange = {},
                        tourBounds = tourBounds,
                        feedback = PosFeedbackManager.noop(),
                        backupReminderVisible = false,
                        onSnoozeBackup = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("新增商品", substring = true).assertIsDisplayed()
    }

    @Test
    fun mainBackupReminder_rendersAt390dp() {
        composeRule.setContent {
            StallPosTheme {
                Box(Modifier.width(390.dp).height(844.dp)) {
                    PosMainScreen(
                        uiState = PosUiState(isLoading = false),
                        currency = currency,
                        catalogTab = CatalogTab.Products,
                        onCatalogTabChange = {},
                        numpadExpanded = false,
                        onNumpadExpandedChange = {},
                        collapseNumpad = {},
                        onUiEvent = {},
                        settingsMenuExpanded = false,
                        onSettingsMenuExpandedChange = {},
                        tourBounds = tourBounds,
                        feedback = PosFeedbackManager.noop(),
                        backupReminderVisible = true,
                        onSnoozeBackup = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("backup-reminder").assertIsDisplayed()
        composeRule.onNodeWithText("稍後").assertIsDisplayed()
    }

    @Test
    fun settingsSheet_rendersAt390dp() {
        composeRule.setContent {
            StallPosTheme {
                Box(Modifier.width(390.dp).height(844.dp)) {
                    PosSettingsContent(
                        backupNeeded = true,
                        extraLargeText = false,
                        hapticEnabled = true,
                        soundEnabled = true,
                        latestVersionTag = "v9.9.9",
                        checkDataMessage = "資料正常",
                        inactiveProducts = listOf(Product("p1", "舊徽章", 50, isActive = false)),
                        onExportBackup = {},
                        onRestoreBackup = {},
                        onSnoozeBackup = {},
                        onCheckData = {},
                        onExtraLargeTextChange = {},
                        onHapticEnabledChange = {},
                        onSoundEnabledChange = {},
                        onReactivateProduct = {},
                        onReportToDeveloper = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("settings-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("檢查資料").assertIsDisplayed()
        composeRule.onNodeWithText("有新版本 v9.9.9 可下載").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("舊徽章").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("回報給開發者").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun extraLargeSettings_rendersAt390dp() {
        composeRule.setContent {
            StallPosTheme(extraLargeText = true) {
                Box(Modifier.width(390.dp).height(844.dp)) {
                    PosSettingsContent(
                        backupNeeded = false,
                        extraLargeText = true,
                        hapticEnabled = false,
                        soundEnabled = false,
                        latestVersionTag = null,
                        checkDataMessage = null,
                        inactiveProducts = emptyList(),
                        onExportBackup = {},
                        onRestoreBackup = {},
                        onSnoozeBackup = {},
                        onCheckData = {},
                        onExtraLargeTextChange = {},
                        onHapticEnabledChange = {},
                        onSoundEnabledChange = {},
                        onReactivateProduct = {},
                        onReportToDeveloper = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("大字體　開").assertIsDisplayed()
        composeRule.onNodeWithText("震動回饋　關").assertIsDisplayed()
    }
}
