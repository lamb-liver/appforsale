package com.lambliver.appforsale.ui.pos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lambliver.appforsale.BuildConfig
import com.lambliver.appforsale.domain.*
import com.lambliver.appforsale.ui.AdMobConfig
import com.lambliver.appforsale.ui.AdsGate
import com.lambliver.appforsale.ui.ads.TopAnchoredAdaptiveBanner
import com.lambliver.appforsale.ui.billing.SponsorBillingViewModel
import com.lambliver.appforsale.ui.feedback.PosFeedbackManager
import kotlinx.collections.immutable.toImmutableMap
import java.text.NumberFormat
import androidx.compose.ui.semantics.Role

internal enum class CatalogTab { Products, Bundles }

internal class PosTourBounds(
    val statsRow: MutableState<Rect?>,
    val fab: MutableState<Rect?>,
    val productRow: MutableState<Rect?>,
    val discountBtn: MutableState<Rect?>,
    val numpad: MutableState<Rect?>,
)

@Composable
internal fun PosMainScreen(
    billingVm: SponsorBillingViewModel,
    uiState: PosUiState,
    currency: NumberFormat,
    catalogTab: CatalogTab,
    onCatalogTabChange: (CatalogTab) -> Unit,
    numpadExpanded: Boolean,
    onNumpadExpandedChange: (Boolean) -> Unit,
    collapseNumpad: () -> Unit,
    onUiEvent: (PosUiEvent) -> Unit,
    settingsMenuExpanded: Boolean,
    onSettingsMenuExpandedChange: (Boolean) -> Unit,
    tourBounds: PosTourBounds,
    feedback: PosFeedbackManager,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    onHapticEnabledChange: (Boolean) -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adsReady by AdsGate.mobileAdsReady.collectAsStateWithLifecycle()
    val removeAdsAfterSponsor by billingVm.removeAdsAfterSponsor.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        if (adsReady && !removeAdsAfterSponsor) {
            TopAnchoredAdaptiveBanner(
                adUnitId = AdMobConfig.ANCHORED_ADAPTIVE_BANNER_AD_UNIT_ID,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .onGloballyPositioned { tourBounds.statsRow.value = it.boundsInRoot() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "今日",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                )
                Text(
                    text = currency.format(uiState.todaySales),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onUiEvent(PosUiEvent.UndoCheckout) },
                    enabled = uiState.lastCheckout != null,
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Default.Undo, contentDescription = "復原上筆") }
                IconButton(
                    onClick = { onUiEvent(PosUiEvent.ShowDashboardSheet) },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Default.Dashboard, contentDescription = "今日儀表板") }
                IconButton(
                    onClick = { onUiEvent(PosUiEvent.RequestExportCsv) },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Default.FileDownload, contentDescription = "匯出全部銷售 CSV") }
                Box(
                    Modifier.onGloballyPositioned { tourBounds.fab.value = it.boundsInRoot() },
                ) {
                    IconButton(
                        onClick = { onSettingsMenuExpandedChange(true) },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Default.Settings, contentDescription = "資料備份與設定") }
                    DropdownMenu(
                        expanded = settingsMenuExpanded,
                        onDismissRequest = { onSettingsMenuExpandedChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("新增商品") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                onSettingsMenuExpandedChange(false)
                                onUiEvent(PosUiEvent.ShowDialog(DialogState.AddProduct))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("新增套組") },
                            leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                            onClick = {
                                onSettingsMenuExpandedChange(false)
                                onUiEvent(PosUiEvent.ShowDialog(DialogState.AddBundle))
                            },
                            enabled = uiState.products.isNotEmpty(),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("贊助開發者") },
                            leadingIcon = { Icon(Icons.Default.LocalCafe, contentDescription = null) },
                            onClick = {
                                onSettingsMenuExpandedChange(false)
                                onUiEvent(PosUiEvent.ShowSponsorSheet)
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("震動回饋") },
                            leadingIcon = {
                                if (hapticEnabled) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            onClick = { onHapticEnabledChange(!hapticEnabled) },
                        )
                        DropdownMenuItem(
                            text = { Text("音效回饋") },
                            leadingIcon = {
                                if (soundEnabled) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            onClick = { onSoundEnabledChange(!soundEnabled) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("備份資料") },
                            onClick = {
                                onSettingsMenuExpandedChange(false)
                                onUiEvent(PosUiEvent.RequestBackupJson)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("還原資料") },
                            onClick = {
                                onSettingsMenuExpandedChange(false)
                                onUiEvent(PosUiEvent.RequestRestoreJson)
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = { onCatalogTabChange(CatalogTab.Products) },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (catalogTab == CatalogTab.Products) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Text(
                    "一般商品",
                    fontWeight = FontWeight.Bold,
                    color = if (catalogTab == CatalogTab.Products) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Button(
                onClick = { onCatalogTabChange(CatalogTab.Bundles) },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (catalogTab == CatalogTab.Bundles) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Text(
                    "套組",
                    fontWeight = FontWeight.Bold,
                    color = if (catalogTab == CatalogTab.Bundles) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        when (catalogTab) {
            CatalogTab.Products -> ProductQuickRow(
                products = uiState.products,
                categories = uiState.categories,
                cart = uiState.cart.products.toImmutableMap(),
                currency = currency,
                onTap = { product ->
                    onUiEvent(
                        PosUiEvent.SetProductCartQty(
                            product.id,
                            (uiState.cart.products[product.id] ?: 0) + 1,
                        ),
                    )
                },
                onLongPress = { onUiEvent(PosUiEvent.ProductLongPress(it)) },
                onLongPressCategory = { onUiEvent(PosUiEvent.CategoryLongPress(it)) },
                onTilePressFeedback = feedback::lightTap,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .onGloballyPositioned { tourBounds.productRow.value = it.boundsInRoot() },
            )

            CatalogTab.Bundles -> BundleQuickRow(
                bundles = uiState.bundles,
                bundleCategories = uiState.bundleCategories,
                cartBundles = uiState.cart.bundles.toImmutableMap(),
                currency = currency,
                onTap = { bundle ->
                    onUiEvent(
                        PosUiEvent.SetBundleCartQty(
                            bundle.id,
                            (uiState.cart.bundles[bundle.id] ?: 0) + 1,
                        ),
                    )
                },
                onLongPress = { onUiEvent(PosUiEvent.BundleLongPress(it)) },
                onLongPressCategory = { onUiEvent(PosUiEvent.BundleCategoryLongPress(it)) },
                onTilePressFeedback = feedback::lightTap,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { tourBounds.numpad.value = it.boundsInRoot() },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNumpadExpandedChange(!numpadExpanded) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (uiState.checkoutCustomDigits.isEmpty()) {
                        Text(
                            text = "自訂金額",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else {
                        Text(
                            text = currency.format(uiState.checkoutParsedCustomAmount),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                val discountTap = if (uiState.checkoutGrandBeforeDiscount > 0) {
                    Modifier.clickable { onUiEvent(PosUiEvent.ShowDiscountSheet) }
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .onGloballyPositioned { tourBounds.discountBtn.value = it.boundsInRoot() }
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            when {
                                uiState.checkoutDiscountApplied > 0 -> MaterialTheme.colorScheme.error
                                uiState.checkoutGrandBeforeDiscount > 0 ->
                                    MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .then(discountTap),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = when {
                            uiState.checkoutDiscountApplied > 0 -> MaterialTheme.colorScheme.onError
                            uiState.checkoutGrandBeforeDiscount > 0 ->
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = numpadExpanded,
                enter = fadeIn(animationSpec = tween(durationMillis = 120)) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(durationMillis = 220),
                    ),
                exit = fadeOut(animationSpec = tween(durationMillis = 90)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(durationMillis = 220),
                    ),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(role = Role.Button, onClick = collapseNumpad)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "收起",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    PosNumpad(
                        onKey = { key ->
                            val cur = uiState.checkoutCustomDigits
                            onUiEvent(
                                PosUiEvent.CheckoutCustomDigitsChange(
                                    when (key) {
                                        "⌫" -> cur.dropLast(1)
                                        "C" -> ""
                                        else -> if (cur.length < 8) cur + key else cur
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (uiState.subtotal > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "小計",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    currency.format(uiState.subtotal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onUiEvent(PosUiEvent.ClearCart) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                    ),
                ) {
                    Text(
                        "清空",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }

        if (uiState.checkoutDiscountApplied > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Percent,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "折扣 −${currency.format(uiState.checkoutDiscountApplied)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                TextButton(
                    onClick = { onUiEvent(PosUiEvent.ClearCheckoutDiscount) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        "取消折扣",
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        PosCheckoutButton(
            displayAmount = uiState.checkoutSurfaceReceivablePreview,
            itemCount = uiState.cartItemCount,
            enabled = uiState.checkoutGrandBeforeDiscount > 0,
            onClick = { onUiEvent(PosUiEvent.BeginCheckout) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        )
    }
}
