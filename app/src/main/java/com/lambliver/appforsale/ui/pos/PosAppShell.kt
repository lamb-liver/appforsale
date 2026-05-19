package com.lambliver.appforsale.ui.pos

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lambliver.appforsale.domain.DocumentTarget
import com.lambliver.appforsale.domain.PosEvent
import com.lambliver.appforsale.ui.OnboardingTour
import com.lambliver.appforsale.ui.PosViewModel
import com.lambliver.appforsale.ui.TourStep
import com.lambliver.appforsale.ui.billing.SponsorBillingViewModel
import com.lambliver.appforsale.ui.feedback.PosFeedbackManager
import kotlinx.coroutines.launch
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PosAppShell(
    vm: PosViewModel,
    billingVm: SponsorBillingViewModel,
    uiState: com.lambliver.appforsale.domain.PosUiState,
    currency: NumberFormat,
    snackbarHostState: SnackbarHostState,
    overlay: PosOverlayState,
    feedback: PosFeedbackManager,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    onHapticEnabledChange: (Boolean) -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var showTour by remember { mutableStateOf(!prefs.getBoolean("tour_done", false)) }

    val tourBounds = remember {
        PosTourBounds(
            statsRow = mutableStateOf(null),
            fab = mutableStateOf(null),
            productRow = mutableStateOf(null),
            discountBtn = mutableStateOf(null),
            numpad = mutableStateOf(null),
        )
    }

    val tourSteps = listOf(
        TourStep("新增商品", "點右上角齒輪 →「新增商品」，輸入名稱與價格即可完成", tourBounds.fab.value),
        TourStep("快選商品", "點擊加入購物車；長按可編輯、調整庫存或刪除；可設定追蹤庫存與商品分類", tourBounds.productRow.value),
        TourStep("套用折扣", "點畫面下方「%」開啟折扣；可選快捷或自訂百分比／金額", tourBounds.discountBtn.value),
        TourStep("自訂金額", "點畫面下方「自訂金額」列開啟鍵盤；可單獨結帳或搭配快選合計", tourBounds.numpad.value),
        TourStep("儀表與匯出", "右側可看今日儀表板；齒輪可新增商品／套組、備份／還原；檔案圖示匯出 CSV", tourBounds.statsRow.value),
    )

    var catalogTab by rememberSaveable { mutableStateOf(CatalogTab.Products) }
    var numpadExpanded by rememberSaveable { mutableStateOf(false) }

    val collapseNumpad: () -> Unit = {
        numpadExpanded = false
    }

    LaunchedEffect(vm.checkoutSuccessFlow, feedback) {
        vm.checkoutSuccessFlow.collect {
            feedback.checkoutSuccess()
            scope.launch { overlay.checkoutSheetState.hide() }
            numpadExpanded = false
            val result = snackbarHostState.showSnackbar(
                message = "結帳成功",
                actionLabel = "復原",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                vm.onUndoClicked()
            }
        }
    }

    LaunchedEffect(vm.csvShareUriFlow) {
        vm.csvShareUriFlow.collect { uriString ->
            val uri = uriString.toUri()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(shareIntent, "分享 CSV"))
            } catch (_: ActivityNotFoundException) {
                // 無可分享 app；Toast 已由 ViewModel 顯示
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) vm.onEvent(PosEvent.ExportCsv(DocumentTarget(uri.toString()))) }

    val backupJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) vm.onEvent(PosEvent.ExportBackupJson(DocumentTarget(uri.toString()))) }

    val restoreJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) overlay.pendingRestoreUri = uri }

    val onUiEvent: (PosUiEvent) -> Unit = { event ->
        overlay.applyUiEvent(
            event = event,
            vm = vm,
            billingVm = billingVm,
            onExportCsv = {
                exportLauncher.launch("POS_全部銷售紀錄_${uiState.todayKey}.csv")
            },
            onBackupJson = {
                backupJsonLauncher.launch("POS_完整備份_${uiState.todayKey}.json")
            },
            onRestoreJson = {
                restoreJsonLauncher.launch(
                    arrayOf("application/json", "application/octet-stream"),
                )
            },
        )
    }

    val suppressExit = overlay.blocksExitConfirmation(uiState, showTour, numpadExpanded)

    BackHandler(enabled = !suppressExit) {
        overlay.showExitConfirmDialog = true
    }
    BackHandler(enabled = overlay.settingsMenuExpanded) {
        overlay.settingsMenuExpanded = false
    }
    BackHandler(enabled = numpadExpanded, onBack = collapseNumpad)
    BackHandler(enabled = uiState.checkoutSheetSnapshot != null) {
        overlay.hideCheckoutSheet(vm)
    }
    BackHandler(enabled = overlay.showExitConfirmDialog) {
        overlay.showExitConfirmDialog = false
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            PosMainScreen(
                billingVm = billingVm,
                uiState = uiState,
                currency = currency,
                catalogTab = catalogTab,
                onCatalogTabChange = { catalogTab = it },
                numpadExpanded = numpadExpanded,
                onNumpadExpandedChange = { numpadExpanded = it },
                collapseNumpad = collapseNumpad,
                onUiEvent = onUiEvent,
                settingsMenuExpanded = overlay.settingsMenuExpanded,
                onSettingsMenuExpandedChange = { overlay.settingsMenuExpanded = it },
                tourBounds = tourBounds,
                feedback = feedback,
                hapticEnabled = hapticEnabled,
                soundEnabled = soundEnabled,
                onHapticEnabledChange = onHapticEnabledChange,
                onSoundEnabledChange = onSoundEnabledChange,
                modifier = Modifier.padding(padding),
            )
        }

        if (showTour) {
            OnboardingTour(
                steps = tourSteps,
                onFinish = {
                    prefs.edit { putBoolean("tour_done", true) }
                    showTour = false
                },
            )
        }
    }

    PosAppOverlays(
        vm = vm,
        billingVm = billingVm,
        uiState = uiState,
        currency = currency,
        sheetOverlay = overlay.sheetOverlay,
        checkoutSheetState = overlay.checkoutSheetState,
        onDismissCheckoutSheet = { overlay.hideCheckoutSheet(vm) },
        onConfirmCheckout = { method, tip ->
            val pricingSnap = uiState.checkoutSheetSnapshot
            if (pricingSnap != null) {
                vm.onEvent(
                    PosEvent.ConfirmCheckout(
                        pricing = pricingSnap,
                        paymentMethod = method,
                        tipAmount = tip,
                    ),
                )
            }
        },
        dashboardSheetState = overlay.dashboardSheetState,
        onHideDashboardSheet = { overlay.hideDashboardSheet() },
        discountSheetState = overlay.discountSheetState,
        onHideDiscountSheet = { overlay.hideDiscountSheet(vm, it) },
        productForAction = overlay.productForAction,
        productActionSheetState = overlay.productActionSheetState,
        onDismissProductSheet = overlay.dismissProductSheet,
        onStockEditProductId = { overlay.stockEditProductId = it },
        stockEditProductId = overlay.stockEditProductId,
        categoryForAction = overlay.categoryForAction,
        categoryActionSheetState = overlay.categoryActionSheetState,
        onDismissCategorySheet = overlay.dismissCategorySheet,
        bundleForAction = overlay.bundleForAction,
        bundleActionSheetState = overlay.bundleActionSheetState,
        onDismissBundleSheet = overlay.dismissBundleSheet,
        bundleCategoryForAction = overlay.bundleCategoryForAction,
        bundleCategoryActionSheetState = overlay.bundleCategoryActionSheetState,
        onDismissBundleCategorySheet = overlay.dismissBundleCategorySheet,
        onDismissSponsorSheet = { overlay.sheetOverlay = null },
        showExitConfirmDialog = overlay.showExitConfirmDialog,
        onDismissExitConfirm = { overlay.showExitConfirmDialog = false },
        pendingRestoreUri = overlay.pendingRestoreUri,
        onDismissRestoreConfirm = { overlay.pendingRestoreUri = null },
        onConfirmRestore = { uri -> vm.onEvent(PosEvent.ImportBackupJson(DocumentTarget(uri.toString()))) },
        feedback = feedback,
    )
}
