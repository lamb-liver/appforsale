package com.lambliver.stallpos.ui.dialog


import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.ui.pos.CategoryTab

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
fun BundleFormDialog(
    title:               String,
    initial:             Bundle?,
    products:            ImmutableList<Product>,
    bundleCategories:    ImmutableList<BundleCategory>,
    onDismiss:           () -> Unit,
    onAddBundleCategory: (String) -> Unit,
    onConfirm:           (name: String, price: Long, categoryId: String, components: List<BundleComponent>) -> Unit,
) {
    var name       by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var priceText  by rememberSaveable { mutableStateOf(initial?.price?.toString() ?: "0") }
    var categoryId by rememberSaveable { mutableStateOf(initial?.categoryId ?: "") }

    var rows by remember(initial?.id) {
        mutableStateOf(
            initial?.components?.map { c -> c.productId to c.qty.toString() }
                ?: emptyList<Pair<String, String>>(),
        )
    }
    var pickProduct        by remember { mutableStateOf(false) }
    var showNewCatField    by remember { mutableStateOf(false) }
    var newCatName         by remember { mutableStateOf("") }
    var pendingCategoryName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bundleCategories.size) {
        pendingCategoryName?.let { pending ->
            bundleCategories.find { it.name == pending }?.let { cat ->
                categoryId = cat.id
                pendingCategoryName = null
            }
        }
    }

    val pm = remember(products) { products.associateBy { it.id } }
    val priceValue = priceText.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val saveEnabled = name.isNotBlank() && rows.isNotEmpty() &&
        rows.all { row ->
            pm[row.first] != null && (row.second.toLongOrNull() ?: 0L) >= 1L
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        text             = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value           = name,
                    onValueChange   = { name = it },
                    label           = { Text("套組名稱") },
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value           = priceText,
                    onValueChange   = { priceText = it.filter { ch -> ch.isDigit() } },
                    label           = { Text("售價（可為 0）") },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier        = Modifier.fillMaxWidth(),
                )
                Text("套組分類", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lazyListItems(listOf(Unit), key = { "__bfc_none__" }) {
                        CategoryTab(
                            label    = "未指定",
                            selected = categoryId == "",
                            onClick  = { categoryId = "" },
                        )
                    }
                    lazyListItems(bundleCategories, key = { it.id }) { cat ->
                        CategoryTab(
                            label    = cat.name,
                            selected = categoryId == cat.id,
                            onClick  = { categoryId = cat.id },
                        )
                    }
                }
                TextButton(onClick = { showNewCatField = !showNewCatField }) {
                    Text("新增套組分類")
                }
                if (showNewCatField) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier            = Modifier.fillMaxWidth(),
                    ) {
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
                                onAddBundleCategory(newCatName.trim())
                            },
                            onSuccessDone = {
                                newCatName = ""
                                showNewCatField = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text("成分（每套所需數量）", style = MaterialTheme.typography.labelLarge)
                for ((idx, row) in rows.withIndex()) {
                    val pid = row.first
                    val qt = row.second
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pm[pid]?.name ?: pid,
                            Modifier.weight(1f),
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            style      = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedTextField(
                            value           = qt,
                            onValueChange   = { nv ->
                                val filtered = nv.filter { ch -> ch.isDigit() }
                                rows = rows.mapIndexed { i, pr ->
                                    if (i == idx) pr.first to filtered else pr
                                }
                            },
                            modifier        = Modifier.width(80.dp),
                            singleLine      = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        IconButton(onClick = {
                            rows = rows.filterIndexed { i, _ -> i != idx }
                        }) { Icon(Icons.Default.Close, contentDescription = "移除") }
                    }
                }
                Button(
                    onClick  = { pickProduct = true },
                    enabled  = products.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("加入成分") }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val comps = rows.mapNotNull { row ->
                        val pid = row.first
                        val qt = row.second
                        val q = qt.toLongOrNull() ?: return@mapNotNull null
                        if (q < 1L) return@mapNotNull null
                        BundleComponent(pid, q)
                    }
                    onConfirm(name.trim(), priceValue, categoryId, comps)
                },
                enabled = saveEnabled,
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (pickProduct) {
        Dialog(onDismissRequest = { pickProduct = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "選擇成分商品",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        lazyListItems(products, key = { it.id }) { p ->
                            TextButton(
                                onClick = {
                                    rows = rows + (p.id to "1")
                                    pickProduct = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(p.name, fontSize = 18.sp) }
                        }
                    }
                    TextButton(
                        onClick = { pickProduct = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("關閉") }
                }
            }
        }
    }
}

@Composable
fun DeleteBundleConfirmDialog(
    bundle:    Bundle,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("刪除套組") },
        text             = { Text("確定刪除「${bundle.name}」？") },
        confirmButton    = { Button(onClick = onConfirm) { Text("確定") } },
        dismissButton    = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 套組操作底部 Sheet
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleActionSheet(
    bundle:     Bundle,
    sheetState: SheetState,
    onEdit:     () -> Unit,
    onDelete:   () -> Unit,
    onDismiss:  () -> Unit,
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
                text       = bundle.name,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("編輯套組", fontSize = 18.sp) },
                leadingContent  = { Icon(Icons.Default.Edit, contentDescription = null) },
                modifier        = Modifier.clickable(onClick = onEdit),
            )
            ListItem(
                headlineContent = {
                    Text("刪除套組", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
                },
                leadingContent  = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickable(onClick = onDelete),
            )
        }
    }
}
