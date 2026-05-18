package com.lambliver.appforsale.ui.pos

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import com.lambliver.appforsale.domain.*
import com.lambliver.appforsale.ui.PosViewModel
import com.lambliver.appforsale.ui.dialog.*
import com.lambliver.appforsale.ui.billing.SponsorBillingViewModel
import com.lambliver.appforsale.ui.sponsor.SponsorDeveloperBottomSheet
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PosAppOverlays(
    vm: PosViewModel,
    billingVm: SponsorBillingViewModel,
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
    pendingRestoreUri: Uri?,
    onDismissRestoreConfirm: () -> Unit,
    onConfirmRestore: (Uri) -> Unit,
) {
    SponsorDeveloperBottomSheet(
        billingVm = billingVm,
        visible = sheetOverlay == PosSheetOverlay.Sponsor,
        onDismiss = onDismissSponsorSheet,
    )

    uiState.checkoutSheetSnapshot?.let { pricingSnap ->
        CheckoutBottomSheet(
            sheetState = checkoutSheetState,
            total = pricingSnap.surfaceReceivable,
            currency = currency,
            onDismiss = onDismissCheckoutSheet,
            onConfirm = onConfirmCheckout,
        )
    }

    if (sheetOverlay == PosSheetOverlay.Dashboard) {
        DashboardBottomSheet(
            sheetState = dashboardSheetState,
            todayLogs = uiState.todaySalesLog,
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
            onConfirm = { name, price, catId, stock ->
                vm.onEvent(PosEvent.AddProduct(name, price, catId, stock))
            },
        )

        is DialogState.EditProduct -> key(dialog.product.id) {
            ProductFormDialog(
                title = "編輯商品",
                initial = dialog.product,
                categories = uiState.categories,
                onDismiss = { vm.onEvent(PosEvent.DismissDialog) },
                onAddCategory = { vm.onEvent(PosEvent.AddCategory(it)) },
                onConfirm = { name, price, catId, stock ->
                    vm.onEvent(PosEvent.UpdateProduct(dialog.product.id, name, price, catId, stock))
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
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismissExitConfirm,
            title = {
                Text(
                    text = "離開應用程式？",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "將返回主畫面。結帳前資料會依現有設定寫回本機（含購物車防抖儲存）。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                )
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissExitConfirm,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("繼續使用", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissExitConfirm()
                        (context as? ComponentActivity)?.finish()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        "離開",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = onDismissRestoreConfirm,
            title = { Text("還原備份") },
            text = {
                Text(
                    "將以備份檔完整覆寫目前商品目錄、分類、套組、購物車、銷售紀錄與統計。\n\n此操作無法復原，確定繼續？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmRestore(uri)
                        onDismissRestoreConfirm()
                    },
                ) {
                    Text(
                        "確定還原",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestoreConfirm) { Text("取消") }
            },
        )
    }
}
