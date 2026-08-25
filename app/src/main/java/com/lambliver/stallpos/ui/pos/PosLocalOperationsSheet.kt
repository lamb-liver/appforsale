package com.lambliver.stallpos.ui.pos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lambliver.stallpos.domain.*

internal fun SyncUiState.displayText(): String = when (status) {
    SyncUiStatus.LOCAL_ONLY -> "僅本機"
    SyncUiStatus.LOADING -> "正在確認同步狀態…"
    SyncUiStatus.SYNCED -> "已同步"
    SyncUiStatus.PENDING -> "$pendingCount 筆待同步"
    SyncUiStatus.BLOCKED -> "$blockedCount 筆需要處理"
    SyncUiStatus.ERROR -> message ?: "同步失敗"
}

private enum class InventoryUiAction(val label: String) {
    ALLOCATE("調撥至活動"),
    RETURN("退回 GENERAL"),
    GENERAL_ADD("GENERAL 盤增"),
    GENERAL_REMOVE("GENERAL 盤減"),
    GENERAL_DAMAGE("GENERAL 損壞"),
    EVENT_ADD("活動盤增"),
    EVENT_DAMAGE("活動損壞"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalOperationsBottomSheet(
    uiState: PosUiState,
    onDismiss: () -> Unit,
    onEvent: (PosEvent) -> Unit,
    cloudLoginConfigured: Boolean = false,
    onGoogleSignIn: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onShareDiagnostics: () -> Unit = {},
) {
    var eventName by rememberSaveable { mutableStateOf("") }
    var eventLocation by rememberSaveable { mutableStateOf("") }
    var eventType by rememberSaveable { mutableStateOf(MarketEventType.MARKET) }
    val selectableEvents = uiState.events.filter { it.status != MarketEventStatus.CLOSED }
    var selectedEventId by rememberSaveable(selectableEvents.map { it.id }) {
        mutableStateOf(selectableEvents.firstOrNull { it.status == MarketEventStatus.ACTIVE }?.id ?: selectableEvents.firstOrNull()?.id)
    }
    val selectedEvent = selectableEvents.firstOrNull { it.id == selectedEventId }
    var inventoryAction by rememberSaveable { mutableStateOf(InventoryUiAction.GENERAL_ADD) }
    val quantities = remember { mutableStateMapOf<String, String>() }
    val trackedProducts = uiState.products.filter { it.stock != null }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("活動與庫存", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(
                    uiState.sync.displayText(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = onGoogleSignIn,
                    enabled = cloudLoginConfigured,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (cloudLoginConfigured) "使用 Google 登入雲端" else "雲端登入尚未設定")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopyDiagnostics, modifier = Modifier.weight(1f)) { Text("複製診斷資訊") }
                    OutlinedButton(onClick = onShareDiagnostics, modifier = Modifier.weight(1f)) { Text("分享診斷資訊") }
                }
            }

            if (uiState.products.any { it.cost == null }) {
                item {
                    Text(
                        "有商品成本未知，毛利會標示為不完整，不會把未知當作 0。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item { SectionTitle("活動") }
            if (uiState.events.isEmpty()) {
                item { Text("尚未建立活動，本機交易使用 GENERAL 庫存。") }
            } else {
                items(uiState.events, key = { it.id }) { event ->
                    EventRow(event, onEvent)
                }
            }

            item {
                OutlinedTextField(
                    value = eventName,
                    onValueChange = { eventName = it },
                    label = { Text("新活動名稱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = eventLocation,
                    onValueChange = { eventLocation = it },
                    label = { Text("地點（選填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MarketEventType.entries) { type ->
                        FilterChip(
                            selected = eventType == type,
                            onClick = { eventType = type },
                            label = { Text(type.displayName()) },
                        )
                    }
                }
                Button(
                    onClick = {
                        onEvent(PosEvent.CreateMarketEvent(eventName.trim(), eventType, eventLocation.trim()))
                        eventName = ""
                        eventLocation = ""
                    },
                    enabled = eventName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("建立活動") }
            }

            item { HorizontalDivider(); SectionTitle("庫存") }
            if (selectableEvents.isNotEmpty()) {
                item {
                    Text("目標活動", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(selectableEvents, key = { it.id }) { event ->
                            FilterChip(
                                selected = selectedEventId == event.id,
                                onClick = { selectedEventId = event.id },
                                label = { Text(event.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(InventoryUiAction.entries.filter { selectedEvent != null || !it.needsEvent() }) { action ->
                        FilterChip(
                            selected = inventoryAction == action,
                            onClick = { inventoryAction = action },
                            label = { Text(action.label) },
                        )
                    }
                }
            }
            if (trackedProducts.isEmpty()) {
                item { Text("尚無追蹤庫存的商品。") }
            } else {
                items(trackedProducts, key = { it.id }) { product ->
                    val general = uiState.inventoryLevels.quantity(product.id, InventoryLocation.General)
                    val eventQuantity = selectedEvent?.let {
                        uiState.inventoryLevels.quantity(product.id, InventoryLocation.event(it.id))
                    } ?: 0L
                    val quantity = quantities[product.id].orEmpty().toLongOrNull()
                    InventoryOperationRow(
                        product = product,
                        generalQuantity = general,
                        eventQuantity = eventQuantity,
                        quantityText = quantities[product.id].orEmpty(),
                        onQuantityChange = { quantities[product.id] = it.filter(Char::isDigit) },
                        enabled = quantity != null && quantity > 0 &&
                            (!inventoryAction.needsEvent() || selectedEvent != null) &&
                            inventoryAction.canApply(general, eventQuantity, quantity),
                        onApply = apply@{
                            val validQuantity = quantity ?: return@apply
                            if (inventoryAction.needsEvent() && selectedEventId == null) return@apply
                            onEvent(inventoryAction.toEvent(product.id, validQuantity, selectedEventId))
                            quantities[product.id] = ""
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: MarketEvent, onEvent: (PosEvent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(event.status.displayName(), color = MaterialTheme.colorScheme.primary)
            }
            Text("${event.code} · ${event.timezone}${event.location.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}")
            when (event.status) {
                MarketEventStatus.PLANNED -> Button(
                    onClick = { onEvent(PosEvent.ChangeMarketEventStatus(event.id, MarketEventStatus.ACTIVE)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("開始活動") }
                MarketEventStatus.ACTIVE -> Button(
                    onClick = { onEvent(PosEvent.CloseMarketEvent(event.id)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("結束並退回剩餘庫存") }
                MarketEventStatus.CLOSED -> Unit
            }
        }
    }
}

@Composable
private fun InventoryOperationRow(
    product: Product,
    generalQuantity: Long,
    eventQuantity: Long,
    quantityText: String,
    onQuantityChange: (String) -> Unit,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(product.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("GENERAL $generalQuantity · 活動 $eventQuantity · 成本 ${product.cost?.toString() ?: "未知"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = onQuantityChange,
                    label = { Text("數量") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onApply, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("執行") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

private fun List<InventoryLevel>.quantity(productId: String, location: InventoryLocation): Long =
    firstOrNull { it.productId == productId && it.location.key == location.key }?.quantity ?: 0L

private fun InventoryUiAction.needsEvent() = this in setOf(InventoryUiAction.ALLOCATE, InventoryUiAction.RETURN, InventoryUiAction.EVENT_ADD, InventoryUiAction.EVENT_DAMAGE)

private fun InventoryUiAction.canApply(general: Long, event: Long, quantity: Long) = when (this) {
    InventoryUiAction.ALLOCATE, InventoryUiAction.GENERAL_REMOVE, InventoryUiAction.GENERAL_DAMAGE -> general >= quantity
    InventoryUiAction.RETURN, InventoryUiAction.EVENT_DAMAGE -> event >= quantity
    InventoryUiAction.GENERAL_ADD, InventoryUiAction.EVENT_ADD -> true
}

private fun InventoryUiAction.toEvent(productId: String, quantity: Long, eventId: String?): PosEvent.MoveInventory {
    val general = InventoryLocation.General
    val event = eventId?.let(InventoryLocation::event)
    return when (this) {
        InventoryUiAction.ALLOCATE -> PosEvent.MoveInventory(productId, quantity, general, event, InventoryMovementType.ALLOCATE_TO_EVENT)
        InventoryUiAction.RETURN -> PosEvent.MoveInventory(productId, quantity, event, general, InventoryMovementType.RETURN_FROM_EVENT)
        InventoryUiAction.GENERAL_ADD -> PosEvent.MoveInventory(productId, quantity, null, general, InventoryMovementType.ADJUSTMENT)
        InventoryUiAction.GENERAL_REMOVE -> PosEvent.MoveInventory(productId, quantity, general, null, InventoryMovementType.ADJUSTMENT)
        InventoryUiAction.GENERAL_DAMAGE -> PosEvent.MoveInventory(productId, quantity, general, null, InventoryMovementType.DAMAGE)
        InventoryUiAction.EVENT_ADD -> PosEvent.MoveInventory(productId, quantity, null, event, InventoryMovementType.ADJUSTMENT)
        InventoryUiAction.EVENT_DAMAGE -> PosEvent.MoveInventory(productId, quantity, event, null, InventoryMovementType.DAMAGE)
    }
}

private fun MarketEventType.displayName() = when (this) {
    MarketEventType.CONVENTION -> "展會"
    MarketEventType.MARKET -> "市集"
    MarketEventType.POPUP -> "快閃"
    MarketEventType.OTHER -> "其他"
}

private fun MarketEventStatus.displayName() = when (this) {
    MarketEventStatus.PLANNED -> "已規劃"
    MarketEventStatus.ACTIVE -> "進行中"
    MarketEventStatus.CLOSED -> "已結束"
}
