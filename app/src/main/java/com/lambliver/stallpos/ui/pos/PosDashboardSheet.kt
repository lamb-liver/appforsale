package com.lambliver.stallpos.ui.pos

import com.lambliver.stallpos.domain.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.roundToInt

// ════════════════════════════════════════════════════════════════════════════
// 今日儀表板 BottomSheet（從 MainActivity 拆出）
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardBottomSheet(
    sheetState: SheetState,
    todayLogs: ImmutableList<SaleRecord>,
    products:  ImmutableList<Product>,
    currency:  NumberFormat,
    onDismiss: () -> Unit,
) {
    val revenue = remember(todayLogs) { todayLogs.sumOf { it.total + it.tipAmount } }
    val cashTotal = remember(todayLogs) {
        todayLogs.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.total + it.tipAmount }
    }
    val digitalTotal = remember(todayLogs) {
        todayLogs.filter { it.paymentMethod == PaymentMethod.DIGITAL }.sumOf { it.total + it.tipAmount }
    }
    val payGrand = cashTotal + digitalTotal

    val productMap = remember(products) { products.associateBy { it.id } }
    val top3 = remember(todayLogs) {
        buildMap<String, Long> {
            todayLogs.forEach { log ->
                log.productQtySoldForReport().forEach { (id, qty) ->
                    put(id, (get(id) ?: 0L) + qty)
                }
            }
        }.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key to it.value }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        dragHandle       = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "今日儀表板",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                "總營業額（含小費）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                currency.format(revenue),
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color      = MaterialTheme.colorScheme.primary,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "現金 vs 行動支付",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (payGrand == 0L) {
                Text(
                    "今日尚無交易",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    if (cashTotal > 0L) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight(max(0.0001f, cashTotal.toFloat() / payGrand.toFloat()))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    if (digitalTotal > 0L) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight(max(0.0001f, digitalTotal.toFloat() / payGrand.toFloat()))
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                }
                val cashPct = if (payGrand > 0) (100.0 * cashTotal.toDouble() / payGrand.toDouble()).roundToInt() else 0
                val digPct  = (100 - cashPct).coerceIn(0, 100)
                Text(
                    "現金　${currency.format(cashTotal)}　·　約 ${cashPct}%",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "行動支付　${currency.format(digitalTotal)}　·　約 ${digPct}%",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "銷售數量　Top 3",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (top3.isEmpty()) {
                Text(
                    "今日尚無銷售紀錄",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                top3.forEachIndexed { i, (id, qty) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}. ${productMap[id]?.name ?: "已刪除商品"}",
                            Modifier.weight(1f),
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            maxLines  = 2,
                            overflow  = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$qty 份",
                            fontWeight = FontWeight.Black,
                            style      = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }

            TextButton(
                onClick     = onDismiss,
                modifier    = Modifier.fillMaxWidth(),
            ) {
                Text("關閉", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
