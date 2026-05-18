package com.lambliver.appforsale.ui.pos

import com.lambliver.appforsale.domain.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lambliver.appforsale.ui.theme.BundleTilePalette
import com.lambliver.appforsale.ui.theme.ProductTilePalette
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import java.text.NumberFormat

// ════════════════════════════════════════════════════════════════════════════
// 商品／套組快選與自訂金額數字鍵盤（從 MainActivity 拆出，便于一人維護）
// ════════════════════════════════════════════════════════════════════════════

private fun tileColorForStableId(id: String): Color {
    val idx = ((id.hashCode() % ProductTilePalette.size) + ProductTilePalette.size) % ProductTilePalette.size
    return ProductTilePalette[idx]
}

private fun bundleTileColorForStableId(id: String): Color {
    val idx = ((id.hashCode() % BundleTilePalette.size) + BundleTilePalette.size) % BundleTilePalette.size
    return BundleTilePalette[idx]
}

@Composable
internal fun ProductQuickRow(
    products:           ImmutableList<Product>,
    categories:         ImmutableList<Category>,
    cart:               ImmutableMap<String, Int>,
    currency:           NumberFormat,
    onTap:              (Product) -> Unit,
    onLongPress:        (Product) -> Unit,
    onLongPressCategory:(Category) -> Unit,
    modifier:           Modifier = Modifier,
) {
    if (products.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text      = "尚未建立商品\n請點右上角齒輪 →「新增商品」",
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize  = 18.sp,
                modifier  = Modifier.padding(16.dp),
            )
        }
        return
    }

    // 選取的分類 ID；"" = 全部，"__none__" = 未分類
    var selectedCatId by remember { mutableStateOf("") }

    // 當分類被刪除後重置至「全部」
    LaunchedEffect(categories) {
        if (selectedCatId != "" && selectedCatId != "__none__" &&
            categories.none { it.id == selectedCatId }) {
            selectedCatId = ""
        }
    }

    Column(modifier = modifier) {
        // ── 分類 Tab（γ：有分類才顯示）────────────────────────────────
        if (categories.isNotEmpty()) {
            LazyRow(
                contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lazyListItems(listOf(Unit), key = { "__tab_all__" }) {
                    CategoryTab(label = "全部", selected = selectedCatId == "",
                        onClick = { selectedCatId = "" })
                }
                lazyListItems(categories, key = { it.id }) { cat ->
                    CategoryTab(
                        label       = cat.name,
                        selected    = selectedCatId == cat.id,
                        onClick     = { selectedCatId = cat.id },
                        onLongClick = { onLongPressCategory(cat) },
                    )
                }
                lazyListItems(listOf(Unit), key = { "__tab_uncat__" }) {
                    CategoryTab(label = "未分類", selected = selectedCatId == "__none__",
                        onClick = { selectedCatId = "__none__" })
                }
            }
        }

        // ── 商品格線（依選取分類篩選；cart 變動不重复配置 filter）────────
        val visibleProducts by remember(products, categories) {
            derivedStateOf {
                when {
                    categories.isEmpty()        -> products
                    selectedCatId == ""         -> products
                    selectedCatId == "__none__" -> products.filter { it.categoryId.isEmpty() }
                    else                        -> products.filter { it.categoryId == selectedCatId }
                }
            }
        }

        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            modifier              = Modifier.weight(1f),
            contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lazyGridItems(
                visibleProducts,
                key = { "p:${it.id}" },
                contentType = { "product_tile" },
            ) { product ->
                val tileColor = remember(product.id) { tileColorForStableId(product.id) }
                ProductTile(
                    product     = product,
                    qty         = cart[product.id] ?: 0,
                    color       = tileColor,
                    currency    = currency,
                    onTap       = { onTap(product) },
                    onLongPress = { onLongPress(product) },
                )
            }
        }
    }
}

@Composable
internal fun BundleQuickRow(
    bundles:              ImmutableList<Bundle>,
    bundleCategories:     ImmutableList<BundleCategory>,
    cartBundles:          ImmutableMap<String, Int>,
    currency:             NumberFormat,
    onTap:                (Bundle) -> Unit,
    onLongPress:          (Bundle) -> Unit,
    onLongPressCategory:  (BundleCategory) -> Unit,
    modifier:             Modifier = Modifier,
) {
    if (bundles.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text(
                    text      = if (bundleCategories.isEmpty() && bundles.isEmpty())
                        "尚未建立套組\n（須先有一般商品，再於右上角齒輪選「新增套組」）"
                    else "尚未建立套組",
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontSize  = 18.sp,
                )
            }
        }
        return
    }

    var selectedCatId by remember { mutableStateOf("") }

    LaunchedEffect(bundleCategories) {
        if (selectedCatId != "" && selectedCatId != "__none__" &&
            bundleCategories.none { it.id == selectedCatId }) {
            selectedCatId = ""
        }
    }

    Column(modifier = modifier) {
        if (bundleCategories.isNotEmpty()) {
            LazyRow(
                contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lazyListItems(listOf(Unit), key = { "__btab_all__" }) {
                    CategoryTab(label = "全部", selected = selectedCatId == "",
                        onClick = { selectedCatId = "" })
                }
                lazyListItems(bundleCategories, key = { it.id }) { cat ->
                    CategoryTab(
                        label       = cat.name,
                        selected    = selectedCatId == cat.id,
                        onClick     = { selectedCatId = cat.id },
                        onLongClick = { onLongPressCategory(cat) },
                    )
                }
                lazyListItems(listOf(Unit), key = { "__btab_uncat__" }) {
                    CategoryTab(label = "未分類", selected = selectedCatId == "__none__",
                        onClick = { selectedCatId = "__none__" })
                }
            }
        }

        val visibleBundles by remember(bundles, bundleCategories) {
            derivedStateOf {
                when {
                    bundleCategories.isEmpty()    -> bundles
                    selectedCatId == ""           -> bundles
                    selectedCatId == "__none__"   -> bundles.filter { it.categoryId.isEmpty() }
                    else                          -> bundles.filter { it.categoryId == selectedCatId }
                }
            }
        }

        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            modifier              = Modifier.weight(1f),
            contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lazyGridItems(
                visibleBundles,
                key = { "b:${it.id}" },
                contentType = { "bundle_tile" },
            ) { bundle ->
                val tileColor = remember(bundle.id) { bundleTileColorForStableId(bundle.id) }
                BundleTile(
                    bundle      = bundle,
                    qty         = cartBundles[bundle.id] ?: 0,
                    color       = tileColor,
                    currency    = currency,
                    onTap       = { onTap(bundle) },
                    onLongPress = { onLongPress(bundle) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BundleTile(
    bundle:      Bundle,
    qty:         Int,
    color:       Color,
    currency:    NumberFormat,
    onTap:       () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .combinedClickable(
                onClick     = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text       = bundle.name,
                color      = Color.White,
                fontWeight = FontWeight.Black,
                fontSize   = 22.sp,
                textAlign  = TextAlign.Center,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text       = currency.format(bundle.price),
                color      = Color.White,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text       = "${bundle.components.size} 項成分／套",
                color      = Color.White.copy(alpha = 0.62f),
                fontSize   = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign  = TextAlign.Center,
            )
        }
        if (qty > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "$qty",
                    color      = color,
                    fontWeight = FontWeight.Black,
                    fontSize   = 14.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CategoryTab(
    label:      String,
    selected:   Boolean,
    onClick:    () -> Unit,
    onLongClick:(() -> Unit)? = null,
) {
    val bgColor   = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bgColor)
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else
                    Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = label,
            color      = textColor,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductTile(
    product:     Product,
    qty:         Int,
    color:       Color,
    currency:    NumberFormat,
    onTap:       () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .combinedClickable(
                onClick     = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text       = product.name,
                color      = Color.White,
                fontWeight = FontWeight.Black,
                fontSize   = 22.sp,
                textAlign  = TextAlign.Center,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text       = currency.format(product.price),
                color      = Color.White,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )
            if (product.stock != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text       = "庫存 ${product.stock}",
                    color      = Color.White.copy(alpha = 0.62f),
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign  = TextAlign.Center,
                )
            }
        }
        if (qty > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "$qty",
                    color      = color,
                    fontWeight = FontWeight.Black,
                    fontSize   = 14.sp,
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 自訂金額數字鍵盤（僅數字與清除；折扣按鈕在畫面常駐列）
// ════════════════════════════════════════════════════════════════════════════

private val numpadKeys = listOf(
    listOf("7", "8", "9"),
    listOf("4", "5", "6"),
    listOf("1", "2", "3"),
    listOf("C", "0", "⌫"),
)

@Composable
internal fun PosNumpad(
    onKey:    (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        for (row in numpadKeys) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (key in row) {
                    OutlinedButton(
                        onClick  = { onKey(key) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 8.dp),
                        shape    = RoundedCornerShape(8.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(
                            containerColor = when (key) {
                                "C"  -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                "⌫" -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            },
                        ),
                    ) {
                        Text(
                            text       = key,
                            style      = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color      = when (key) {
                                "C"  -> MaterialTheme.colorScheme.error
                                "⌫" -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
