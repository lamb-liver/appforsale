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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import java.text.NumberFormat
import kotlin.math.roundToInt

// ════════════════════════════════════════════════════════════════════════════
// 今日儀表板 BottomSheet（從 MainActivity 拆出）
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardBottomSheet(
    sheetState: SheetState,
    todayLogs: ImmutableList<SaleRecord>,
    allLogs: ImmutableList<SaleRecord>,
    reversals: ImmutableList<SaleReversal>,
    products:  ImmutableList<Product>,
    currency:  NumberFormat,
    onDismiss: () -> Unit,
) {
    var selectedSale by remember { mutableStateOf<SaleRecord?>(null) }
    val revenue = remember(todayLogs) { todayLogs.sumOf { it.total + it.tipAmount } }
    val paymentTotals = remember(todayLogs) {
        PaymentMethod.entries.associateWith { method ->
            todayLogs.filter { it.paymentMethod == method }.sumOf { it.total + it.tipAmount }
        }
    }
    val payGrand = paymentTotals.values.sum()
    val reversedSaleIds = remember(reversals) { reversals.mapTo(hashSetOf()) { it.saleId } }
    val recent = remember(allLogs) { allLogs.sortedByDescending { it.tsMillis }.take(20) }

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
                "今日明細",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                "今日營收（含小費）",
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
                "付款方式",
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
                paymentTotals.filterValues { it > 0L }.forEach { (method, amount) ->
                    val percentage = (100.0 * amount.toDouble() / payGrand.toDouble()).roundToInt()
                    Text(
                        "${method.displayName}　${currency.format(amount)}　·　約 ${percentage}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "最近交易",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (recent.isEmpty()) {
                Text("尚無交易紀錄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                recent.forEach { sale ->
                    TextButton(
                        onClick = { selectedSale = if (selectedSale?.id == sale.id) null else sale },
                        modifier = Modifier.fillMaxWidth().testTag("recent-transaction-${sale.id}"),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(
                                    sale.receiptNumber ?: sale.id,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "${sale.dateKey} · ${sale.paymentMethod.displayName}${if (sale.id in reversedSaleIds) " · 已作廢" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sale.id in reversedSaleIds) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(currency.format(sale.total + sale.tipAmount), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (selectedSale?.id == sale.id) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .testTag("transaction-detail-${sale.id}")
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val financialByLine = sale.lineFinancialSnapshots.associateBy { it.lineIndex }
                            sale.checkoutLines.forEachIndexed { index, line ->
                                val name = when (line) {
                                    is SaleCheckoutLine.Product -> line.displayName ?: productMap[line.productId]?.name ?: "已刪除商品"
                                    is SaleCheckoutLine.Bundle -> line.displayName ?: "套組"
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("$name × ${line.qty}", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        currency.format(financialByLine[index]?.finalAmount ?: line.lineSubtotal),
                                        Modifier.testTag("transaction-line-amount-${sale.id}-$index"),
                                    )
                                }
                            }
                            if (sale.checkoutLines.isEmpty()) Text("自訂金額交易", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DetailAmountRow("應收", sale.total, currency)
                            if (sale.tipAmount > 0) DetailAmountRow("小費", sale.tipAmount, currency)
                            DetailAmountRow("總收款", sale.total + sale.tipAmount, currency)
                        }
                    }
                }
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

@Composable
private fun DetailAmountRow(label: String, amount: Long, currency: NumberFormat) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(currency.format(amount), fontWeight = FontWeight.Bold)
    }
}
