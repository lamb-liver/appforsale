package com.lambliver.appforsale.ui.dialog


import com.lambliver.appforsale.domain.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun StockAdjustDialog(
    product:   Product,
    onDismiss: () -> Unit,
    onSave:    (stock: Long?) -> Unit,
) {
    var trackStock by remember(product.id) { mutableStateOf(product.stock != null) }
    var stockText  by remember(product.id) { mutableStateOf(product.stock?.toString() ?: "") }

    val stockParsed = stockText.toLongOrNull()
    val saveEnabled = !trackStock || (stockParsed != null && stockParsed >= 0L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("調整庫存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(product.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text("追蹤庫存", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = trackStock, onCheckedChange = { trackStock = it })
                }
                if (trackStock) {
                    OutlinedTextField(
                        value           = stockText,
                        onValueChange   = { stockText = it.filter { ch -> ch.isDigit() } },
                        label           = { Text("庫存數量") },
                        singleLine      = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier        = Modifier.fillMaxWidth(),
                        supportingText  = { Text("為 0 時無法加入購物車", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                    )
                } else {
                    Text(
                        "未勾選時為無上限（不扣庫存）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    run {
                        if (!trackStock) {
                            onSave(null)
                            return@run
                        }
                        val parsed = stockParsed ?: return@run
                        onSave(parsed.coerceAtLeast(0L))
                    }
                },
                enabled = saveEnabled,
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 新增與編輯商品共用同一個 Dialog。
 * [initial] 為 null 時為新增模式，否則為編輯模式。
 */
@Composable
fun ProductFormDialog(
    title:         String,
    initial:       Product?,
    categories:    ImmutableList<Category>,
    onDismiss:     () -> Unit,
    onAddCategory: (String) -> Unit,
    onConfirm:     (name: String, price: Long, categoryId: String, stock: Long?) -> Unit,
) {
    var name       by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var priceText  by rememberSaveable { mutableStateOf(initial?.price?.toString() ?: "") }
    var categoryId by rememberSaveable { mutableStateOf(initial?.categoryId ?: "") }

    var trackStock by rememberSaveable { mutableStateOf(initial?.stock != null) }
    var stockText  by rememberSaveable { mutableStateOf(initial?.stock?.toString() ?: "") }

    var showNewCatField    by remember { mutableStateOf(false) }
    var newCatName         by remember { mutableStateOf("") }
    var pendingCategoryName by remember { mutableStateOf<String?>(null) }

    // 當分類清單更新時，自動選取剛建立的分類
    LaunchedEffect(categories.size) {
        pendingCategoryName?.let { pending ->
            categories.find { it.name == pending }?.let { cat ->
                categoryId = cat.id
                pendingCategoryName = null
            }
        }
    }

    val priceValue = priceText.toLongOrNull() ?: 0L
    val stockParsed = stockText.toLongOrNull()
    val stockOk     = !trackStock || (stockParsed != null && stockParsed >= 0L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("名稱") },
                    singleLine    = true,
                )
                OutlinedTextField(
                    value           = priceText,
                    onValueChange   = { priceText = it.filter { ch -> ch.isDigit() } },
                    label           = { Text("價格") },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text("追蹤庫存", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = trackStock, onCheckedChange = { trackStock = it })
                }
                if (trackStock) {
                    OutlinedTextField(
                        value           = stockText,
                        onValueChange   = { stockText = it.filter { ch -> ch.isDigit() } },
                        label           = { Text("庫存數量") },
                        singleLine      = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText  = { Text("為 0 無法加入購物車；關閉則無上限", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                    )
                }

                // ── 分類選擇 ─────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (categories.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            lazyListItems(listOf(Unit), key = { "__chip_none__" }) {
                                FilterChip(
                                    selected = categoryId.isEmpty(),
                                    onClick  = { categoryId = "" },
                                    label    = { Text("未分類") },
                                )
                            }
                            lazyListItems(categories, key = { it.id }) { cat ->
                                FilterChip(
                                    selected = categoryId == cat.id,
                                    onClick  = { categoryId = cat.id },
                                    label    = { Text(cat.name) },
                                )
                            }
                            lazyListItems(listOf(Unit), key = { "__chip_new_cat__" }) {
                                FilterChip(
                                    selected = false,
                                    onClick  = { showNewCatField = true },
                                    label    = { Text("+ 新增分類") },
                                )
                            }
                        }
                    } else {
                        FilterChip(
                            selected = false,
                            onClick  = { showNewCatField = true },
                            label    = { Text("+ 建立第一個分類（選填）") },
                        )
                    }

                    if (showNewCatField) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value         = newCatName,
                                onValueChange = { newCatName = it },
                                label         = { Text("分類名稱") },
                                singleLine    = true,
                                modifier      = Modifier.fillMaxWidth(),
                            )
                            SlideToConfirmNewCategory(
                                enabled = newCatName.isNotBlank(),
                                onCommitted = {
                                    pendingCategoryName = newCatName.trim()
                                    onAddCategory(newCatName.trim())
                                },
                                onSuccessDone = {
                                    newCatName = ""
                                    showNewCatField = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    run {
                        val st = if (!trackStock) {
                            null
                        } else {
                            val parsed = stockParsed ?: return@run
                            parsed.coerceAtLeast(0L)
                        }
                        onConfirm(name.trim(), priceValue, categoryId, st)
                    }
                },
                enabled = name.isNotBlank() && priceValue > 0 && stockOk,
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DeleteConfirmDialog(
    product:   Product,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刪除商品") },
        text  = { Text("確定刪除「${product.name}」？") },
        confirmButton = { Button(onClick = onConfirm) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 商品操作底部 Sheet（長按觸發）
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductActionSheet(
    product:       Product,
    sheetState:    SheetState,
    onEdit:        () -> Unit,
    onDelete:      () -> Unit,
    onAdjustStock: () -> Unit,
    onDismiss:     () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text     = product.name,
                style    = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("編輯商品", fontSize = 18.sp) },
                leadingContent  = { Icon(Icons.Default.Edit, contentDescription = null) },
                modifier        = Modifier.clickable(onClick = onEdit),
            )
            ListItem(
                headlineContent = { Text("調整庫存", fontSize = 18.sp) },
                leadingContent  = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                modifier        = Modifier.clickable(onClick = onAdjustStock),
            )
            ListItem(
                headlineContent = {
                    Text("刪除商品", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
                },
                leadingContent  = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickable(onClick = onDelete),
            )
        }
    }
}
