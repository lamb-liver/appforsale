package com.lambliver.stallpos.ui.pos

import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lambliver.stallpos.domain.Bundle
import com.lambliver.stallpos.domain.BundleCategory
import com.lambliver.stallpos.domain.Category
import com.lambliver.stallpos.domain.DialogState
import com.lambliver.stallpos.domain.PosUiState
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.ui.PosViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
internal class PosOverlayState(
    val scope: CoroutineScope,
    val discountSheetState: SheetState,
    val checkoutSheetState: SheetState,
    val dashboardSheetState: SheetState,
    val productActionSheetState: SheetState,
    val categoryActionSheetState: SheetState,
    val bundleActionSheetState: SheetState,
    val bundleCategoryActionSheetState: SheetState,
) {
    var sheetOverlay by mutableStateOf<PosSheetOverlay?>(null)
    var productForAction by mutableStateOf<Product?>(null)
    var categoryForAction by mutableStateOf<Category?>(null)
    var bundleForAction by mutableStateOf<Bundle?>(null)
    var bundleCategoryForAction by mutableStateOf<BundleCategory?>(null)
    var stockEditProductId by mutableStateOf<String?>(null)
    var pendingRestoreUri by mutableStateOf<Uri?>(null)
    var settingsMenuExpanded by mutableStateOf(false)
    var showExitConfirmDialog by mutableStateOf(false)

    val dismissProductSheet: () -> Unit = {
        scope.launch { productActionSheetState.hide(); productForAction = null }
    }

    val dismissCategorySheet: () -> Unit = {
        scope.launch { categoryActionSheetState.hide(); categoryForAction = null }
    }

    val dismissBundleSheet: () -> Unit = {
        scope.launch { bundleActionSheetState.hide(); bundleForAction = null }
    }

    val dismissBundleCategorySheet: () -> Unit = {
        scope.launch { bundleCategoryActionSheetState.hide(); bundleCategoryForAction = null }
    }

    fun hideDiscountSheet(vm: PosViewModel, newDiscount: Long?) {
        scope.launch {
            discountSheetState.hide()
            newDiscount?.let { vm.onCheckoutDiscountRequested(it) }
            sheetOverlay = null
        }
    }

    fun hideCheckoutSheet(vm: PosViewModel) {
        scope.launch {
            checkoutSheetState.hide()
            vm.dismissCheckoutSheet()
        }
    }

    fun hideDashboardSheet() {
        scope.launch {
            dashboardSheetState.hide()
            sheetOverlay = null
        }
    }

    fun applyUiEvent(
        event: PosUiEvent,
        vm: PosViewModel,
        onExportCsv: () -> Unit,
        onBackupJson: () -> Unit,
        onRestoreJson: () -> Unit,
    ) {
        event.toSheetOverlayOrNull()?.let { sheetOverlay = it }
        when (event) {
            PosUiEvent.ShowDiscountSheet,
            PosUiEvent.ShowDashboardSheet,
            PosUiEvent.ShowSponsorSheet,
            -> Unit
            PosUiEvent.RequestExportCsv -> onExportCsv()
            PosUiEvent.RequestBackupJson -> onBackupJson()
            PosUiEvent.RequestRestoreJson -> onRestoreJson()
            is PosUiEvent.ProductLongPress -> productForAction = event.product
            is PosUiEvent.CategoryLongPress -> categoryForAction = event.category
            is PosUiEvent.BundleLongPress -> bundleForAction = event.bundle
            is PosUiEvent.BundleCategoryLongPress -> bundleCategoryForAction = event.category
            PosUiEvent.UndoCheckout -> vm.onUndoClicked()
            PosUiEvent.ClearCart -> vm.onEvent(com.lambliver.stallpos.domain.PosEvent.ClearCart)
            PosUiEvent.BeginCheckout -> vm.beginCheckoutSheet()
            is PosUiEvent.SetProductCartQty -> vm.onUpdateCart(event.productId, event.qty)
            is PosUiEvent.SetBundleCartQty -> vm.onUpdateBundleCart(event.bundleId, event.qty)
            is PosUiEvent.CheckoutCustomDigitsChange -> vm.onCheckoutCustomDigitsChange(event.digits)
            PosUiEvent.ClearCheckoutDiscount -> vm.onCheckoutDiscountRequested(0L)
            is PosUiEvent.ShowDialog -> vm.onEvent(com.lambliver.stallpos.domain.PosEvent.ShowDialog(event.state))
        }
    }

    fun blocksExitConfirmation(uiState: PosUiState, showTour: Boolean, numpadExpanded: Boolean): Boolean =
        showTour ||
            showExitConfirmDialog ||
            numpadExpanded ||
            settingsMenuExpanded ||
            uiState.dialogState != DialogState.None ||
            uiState.checkoutSheetSnapshot != null ||
            sheetOverlay != null ||
            productForAction != null ||
            categoryForAction != null ||
            bundleForAction != null ||
            bundleCategoryForAction != null ||
            stockEditProductId != null ||
            pendingRestoreUri != null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberPosOverlayState(scope: CoroutineScope): PosOverlayState {
    val discountSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val checkoutSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dashboardSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val productActionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val categoryActionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bundleActionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bundleCategoryActionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    return remember(
        scope,
        discountSheetState,
        checkoutSheetState,
        dashboardSheetState,
        productActionSheetState,
        categoryActionSheetState,
        bundleActionSheetState,
        bundleCategoryActionSheetState,
    ) {
        PosOverlayState(
            scope = scope,
            discountSheetState = discountSheetState,
            checkoutSheetState = checkoutSheetState,
            dashboardSheetState = dashboardSheetState,
            productActionSheetState = productActionSheetState,
            categoryActionSheetState = categoryActionSheetState,
            bundleActionSheetState = bundleActionSheetState,
            bundleCategoryActionSheetState = bundleCategoryActionSheetState,
        )
    }
}
