package com.lambliver.stallpos.ui.pos

import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.ui.PosViewModel
import com.lambliver.stallpos.ui.runIntegrityCheck
import com.lambliver.stallpos.ui.dialog.*
import com.lambliver.stallpos.ui.feedback.PosFeedbackManager
import com.lambliver.stallpos.ui.sponsor.SponsorDeveloperBottomSheet
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PosAppOverlays(
    vm: PosViewModel,
    uiState: PosUiState,
    currency: NumberFormat,
    checkoutSheetState: SheetState,
    onDismissCheckoutSheet: () -> Unit,
    onConfirmCheckout: (PaymentMethod, Long) -> Unit,
    sheetOverlay: PosSheetOverlay?,
    dashboardSheetState: SheetState,
    onHideDashboardSheet: () -> Unit,
    discountSheetState: SheetState,
    onHideDiscountSheet: (Long?) -> Unit,
    productForAction: Product?,
    productActionSheetState: SheetState,
    onDismissProductSheet: () -> Unit,
    onStockEditProductId: (String?) -> Unit,
    stockEditProductId: String?,
    categoryForAction: Category?,
    categoryActionSheetState: SheetState,
    onDismissCategorySheet: () -> Unit,
    bundleForAction: Bundle?,
    bundleActionSheetState: SheetState,
    onDismissBundleSheet: () -> Unit,
    bundleCategoryForAction: BundleCategory?,
    bundleCategoryActionSheetState: SheetState,
    onDismissBundleCategorySheet: () -> Unit,
    onDismissSponsorSheet: () -> Unit,
    showExitConfirmDialog: Boolean,
    onDismissExitConfirm: () -> Unit,
    showNonCashVoidConfirm: Boolean,
    onDismissNonCashVoidConfirm: () -> Unit,
    onConfirmNonCashVoid: () -> Unit,
    pendingRestoreUri: Uri?,
    onDismissRestoreConfirm: () -> Unit,
    onConfirmRestore: (Uri) -> Unit,
    feedback: PosFeedbackManager,
    cloudLoginConfigured: Boolean,
    onGoogleSignIn: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    SponsorDeveloperBottomSheet(
        visible = sheetOverlay == PosSheetOverlay.Sponsor,
        onDismiss = onDismissSponsorSheet,
    )

    if (sheetOverlay == PosSheetOverlay.Settings) {
        val extraLargeText by vm.extraLargeTextFlow.collectAsStateWithLifecycle(initialValue = false)
        val hapticEnabled by vm.hapticEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
        val soundEnabled by vm.soundEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
        val backupNeeded by vm.backupReminderVisible.collectAsStateWithLifecycle()
        val latestVersionTag by vm.latestVersionTag.collectAsStateWithLifecycle()
        val checkDataMessage by vm.checkDataMessage.collectAsStateWithLifecycle()
        PosSettingsSheet(
            backupNeeded = backupNeeded,
            extraLargeText = extraLargeText,
            hapticEnabled = hapticEnabled,
            soundEnabled = soundEnabled,
            latestVersionTag = latestVersionTag,
            checkDataMessage = checkDataMessage,
            inactiveProducts = uiState.products.filter { !it.isActive },
            onDismiss = onDismissSponsorSheet,
            onExportBackup = onExportBackup,
            onRestoreBackup = onRestoreBackup,
            onSnoozeBackup = vm::snoozeBackupReminder,
            onCheckData = { vm.runIntegrityCheck() },
            onExtraLargeTextChange = vm::setExtraLargeText,
            onHapticEnabledChange = vm::setHapticEnabled,
            onSoundEnabledChange = vm::setSoundEnabled,
            onReactivateProduct = { vm.onEvent(PosEvent.SetProductActive(it.id, true)) },
            onReportToDeveloper = onShareDiagnostics,
        )
    }

    if (sheetOverlay == PosSheetOverlay.LocalOperations) {
        LocalOperationsBottomSheet(
            uiState = uiState,
            onDismiss = onDismissSponsorSheet,
            onEvent = vm::onEvent,
            cloudLoginConfigured = cloudLoginConfigured,
            onGoogleSignIn = onGoogleSignIn,
            onCopyDiagnostics = onCopyDiagnostics,
            onShareDiagnostics = onShareDiagnostics,
        )
    }

    uiState.checkoutSheetSnapshot?.let { pricingSnap ->
        CheckoutBottomSheet(
            sheetState = checkoutSheetState,
            total = pricingSnap.surfaceReceivable,
            currency = currency,
            onDismiss = onDismissCheckoutSheet,
            onConfirm = onConfirmCheckout,
            feedback = feedback,
        )
    }

    if (sheetOverlay == PosSheetOverlay.Dashboard) {
        DashboardBottomSheet(
            sheetState = dashboardSheetState,
            todayLogs = uiState.todaySalesLog,
            allLogs = uiState.salesLog,
            reversals = uiState.reversalLog,
            products = uiState.products,
            currency = currency,
            onDismiss = onHideDashboardSheet,
        )
    }

    if (sheetOverlay == PosSheetOverlay.Discount) {
        DiscountBottomSheet(
            sheetState = discountSheetState,
            grandTotal = uiState.checkoutGrandBeforeDiscount,
            currency = currency,
            onApply = { onHideDiscountSheet(it) },
            onDismiss = { onHideDiscountSheet(null) },
        )
    }

    productForAction?.let { product ->
        ProductActionSheet(
            product = product,
            sheetState = productActionSheetState,
            onEdit = {
                onDismissProductSheet()
                vm.onEvent(PosEvent.ShowDialog(DialogState.EditProduct(product)))
            },
            onDelete = {
                onDismissProductSheet()
                vm.onEvent(PosEvent.ShowDialog(DialogState.DeleteProduct(product)))
            },
            onDeactivate = {
                onDismissProductSheet()
                vm.onEvent(PosEvent.SetProductActive(product.id, false))
            },
            onAdjustStock = {
                onStockEditProductId(product.id)
                onDismissProductSheet()
            },
            onDismiss = onDismissProductSheet,
        )
    }

    stockEditProductId?.let { pid ->
        uiState.products.find { it.id == pid }?.let { prod ->
            StockAdjustDialog(
                product = prod,
                onDismiss = { onStockEditProductId(null) },
                onSave = { stock ->
                    vm.onEvent(PosEvent.SetProductStock(pid, stock))
                    onStockEditProductId(null)
                },
            )
        }
    }

    categoryForAction?.let { cat ->
        CategoryActionSheet(
            category = cat,
            sheetState = categoryActionSheetState,
            onRename = { newName ->
                vm.onEvent(PosEvent.UpdateCategory(cat.id, newName))
                onDismissCategorySheet()
            },
            onDelete = {
                vm.onEvent(PosEvent.DeleteCategory(cat.id))
                onDismissCategorySheet()
            },
            onDismiss = onDismissCategorySheet,
        )
    }

    bundleForAction?.let { bundle ->
        BundleActionSheet(
            bundle = bundle,
            sheetState = bundleActionSheetState,
            onEdit = {
                onDismissBundleSheet()
                vm.onEvent(PosEvent.ShowDialog(DialogState.EditBundle(bundle)))
            },
            onDelete = {
                onDismissBundleSheet()
                vm.onEvent(PosEvent.ShowDialog(DialogState.DeleteBundle(bundle)))
            },
            onDismiss = onDismissBundleSheet,
        )
    }

    bundleCategoryForAction?.let { cat ->
        BundleCategoryActionSheet(
            category = cat,
            sheetState = bundleCategoryActionSheetState,
            onRename = { newName ->
                vm.onEvent(PosEvent.UpdateBundleCategory(cat.id, newName))
                onDismissBundleCategorySheet()
            },
            onDelete = {
                vm.onEvent(PosEvent.DeleteBundleCategory(cat.id))
                onDismissBundleCategorySheet()
            },
            onDismiss = onDismissBundleCategorySheet,
        )
    }

    when (val dialog = uiState.dialogState) {
        DialogState.None -> Unit

        DialogState.AddProduct -> ProductFormDialog(
            title = "新增商品",
            initial = null,
            categories = uiState.categories,
            onDismiss = { vm.onEvent(PosEvent.DismissDialog) },
            onAddCategory = { vm.onEvent(PosEvent.AddCategory(it)) },
            onConfirm = { name, price, catId, stock, cost ->
                vm.onEvent(PosEvent.AddProduct(name, price, catId, stock, cost))
            },
        )

        is DialogState.EditProduct -> key(dialog.product.id) {
            ProductFormDialog(
                title = "編輯商品",
                initial = dialog.product,
                categories = uiState.categories,
                onDismiss = { vm.onEvent(PosEvent.DismissDialog) },
                onAddCategory = { vm.onEvent(PosEvent.AddCategory(it)) },
                onConfirm = { name, price, catId, stock, cost ->
                    vm.onEvent(PosEvent.UpdateProduct(dialog.product.id, name, price, catId, stock, cost))
                },
            )
        }

        is DialogState.DeleteProduct -> DeleteConfirmDialog(
            product = dialog.product,
            onDismiss = { vm.onEvent(PosEvent.DismissDialog) },
            onConfirm = { vm.onEvent(PosEvent.DeleteProduct(dialog.product.id)) },
        )

        DialogState.AddBundle -> BundleFormDialog(
            title = "新增套組",
            initial = null,
            products = uiState.products,
            bundleCategories = uiState.bundleCategories,
            onDismiss = { vm.onEvent(PosEvent.DismissDialog) },
            onAddBundleCategory = { vm.onEvent(PosEvent.AddBundleCategory(it)) },
            onConfirm = { name, price, catId, comps ->
                vm.onEvent(PosEvent.AddBundle(name, price, catId, comps))
            },
        )

        is DialogState.EditBundle -> key(dialog.bundle.id) {
            BundleFormDialog(
                title = "編輯套組",
                initial = dialog.bundle,
                products = uiState.products,
                bundleCategories = uiState.bundleCategories,
                onDismiss = { vm.onEvent(PosEvent.DismissDialog) },
                onAddBundleCategory = { vm.onEvent(PosEvent.AddBundleCategory(it)) },
                onConfirm = { name, price, catId, comps ->
                    vm.onEvent(PosEvent.UpdateBundle(dialog.bundle.id, name, price, catId, comps))
                },
            )
        }

        is DialogState.DeleteBundle -> DeleteBundleConfirmDialog(
            bundle = dialog.bundle,
            onDismiss = { vm.onEvent(PosEvent.DismissDialog) },
            onConfirm = { vm.onEvent(PosEvent.DeleteBundle(dialog.bundle.id)) },
        )
    }

    if (showExitConfirmDialog) {
        ExitConfirmDialog(onDismiss = onDismissExitConfirm)
    }
    if (showNonCashVoidConfirm) {
        NonCashVoidConfirmDialog(
            onDismiss = onDismissNonCashVoidConfirm,
            onConfirm = onConfirmNonCashVoid,
        )
    }
    pendingRestoreUri?.let { uri ->
        RestoreBackupConfirmDialog(
            uri = uri,
            onDismiss = onDismissRestoreConfirm,
            onConfirm = onConfirmRestore,
        )
    }
}
