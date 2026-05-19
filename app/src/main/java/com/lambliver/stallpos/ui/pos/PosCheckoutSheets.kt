package com.lambliver.stallpos.ui.pos

import com.lambliver.stallpos.domain.*

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lambliver.stallpos.ui.feedback.PosFeedbackManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lambliver.stallpos.ui.theme.LinearSemanticSuccess
import java.text.NumberFormat

/** Robolectric Compose 測試用 semantics 標籤。 */
internal object CheckoutSheetTestTags {
    const val RECEIVABLE = "checkout_sheet_receivable"
    const val CASH_INPUT = "checkout_cash_input"
    const val CONFIRM = "checkout_confirm_button"
}

private enum class DiscountPage { QuickPick, Custom }

// ════════════════════════════════════════════════════════════════════════════
// 結帳前折扣／找零 BottomSheet（從 MainActivity 拆出）
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscountBottomSheet(
    sheetState: SheetState,
    grandTotal: Long,
    currency:   NumberFormat,
    onApply:    (Long) -> Unit,
    onDismiss:  () -> Unit,
) {
    var page by remember { mutableStateOf(DiscountPage.QuickPick) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        dragHandle       = { BottomSheetDefaults.DragHandle() },
    ) {
        when (page) {
            DiscountPage.QuickPick -> DiscountQuickPickContent(
                grandTotal = grandTotal,
                currency   = currency,
                onSelect   = onApply,
                onCustom   = { page = DiscountPage.Custom },
            )
            DiscountPage.Custom -> DiscountCustomContent(
                grandTotal = grandTotal,
                currency   = currency,
                onApply    = onApply,
                onBack     = { page = DiscountPage.QuickPick },
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 快捷折扣選項頁
// ─────────────────────────────────────────────────────────────────────────────

private data class QuickPickDiscountDerived(
    val ninetyResult: Long,
    val ninetyOff:    Long,
    val eightyResult: Long,
    val eightyOff:    Long,
    val tailOff:      Long,
    val tailResult:   Long,
)

@Composable
private fun DiscountQuickPickContent(
    grandTotal: Long,
    currency:   NumberFormat,
    onSelect:   (Long) -> Unit,
    onCustom:   () -> Unit,
) {
    val quickPick by remember(grandTotal) {
        derivedStateOf {
            val ninetyResult = grandTotal * 9 / 10
            val eightyResult = grandTotal * 8 / 10
            val tailOff      = grandTotal % 10
            QuickPickDiscountDerived(
                ninetyResult = ninetyResult,
                ninetyOff    = grandTotal - ninetyResult,
                eightyResult = eightyResult,
                eightyOff    = grandTotal - eightyResult,
                tailOff      = tailOff,
                tailResult   = grandTotal - tailOff,
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "折扣選項",
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
        )
        Text(
            "原始金額：${currency.format(grandTotal)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickDiscountCard(
                label     = "九折",
                sublabel  = "折抵 ${currency.format(quickPick.ninetyOff)}",
                result    = currency.format(quickPick.ninetyResult),
                onClick   = { onSelect(quickPick.ninetyOff) },
                modifier  = Modifier.weight(1f),
            )
            QuickDiscountCard(
                label    = "八折",
                sublabel = "折抵 ${currency.format(quickPick.eightyOff)}",
                result   = currency.format(quickPick.eightyResult),
                onClick  = { onSelect(quickPick.eightyOff) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickDiscountCard(
                label    = "去尾數",
                sublabel = if (quickPick.tailOff > 0) "折抵 ${currency.format(quickPick.tailOff)}" else "無尾數可抹",
                result   = if (quickPick.tailOff > 0) currency.format(quickPick.tailResult) else "—",
                onClick  = { if (quickPick.tailOff > 0) onSelect(quickPick.tailOff) },
                enabled  = quickPick.tailOff > 0,
                modifier = Modifier.weight(1f),
            )
            QuickDiscountCard(
                label       = "自訂折扣",
                sublabel    = "手動設定金額或比例",
                result      = "",
                onClick     = onCustom,
                highlighted = true,
                modifier    = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickDiscountCard(
    label:       String,
    sublabel:    String,
    result:      String,
    onClick:     () -> Unit,
    enabled:     Boolean = true,
    highlighted: Boolean = false,
    modifier:    Modifier = Modifier,
) {
    val containerColor = when {
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        else        -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(88.dp),
        colors   = CardDefaults.cardColors(
            containerColor         = containerColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color      = if (!enabled) LocalContentColor.current.copy(alpha = 0.38f)
                else if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else LocalContentColor.current,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
                if (result.isNotEmpty()) {
                    Text(
                        result,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 自訂折扣設定頁
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscountCustomContent(
    grandTotal: Long,
    currency:   NumberFormat,
    onApply:    (Long) -> Unit,
    onBack:     () -> Unit,
) {
    // 0 = 百分比折抵，1 = 固定金額折抵
    var typeIndex by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }

    val discountPreview by remember(grandTotal) {
        derivedStateOf {
            val inputLong = inputText.toLongOrNull() ?: 0L
            val discountAmt: Long = when (typeIndex) {
                0    -> grandTotal * inputLong.coerceIn(0L, 100L) / 100L
                else -> inputLong.coerceIn(0L, grandTotal)
            }
            discountAmt to (grandTotal - discountAmt)
        }
    }
    val discountAmt = discountPreview.first
    val afterTotal  = discountPreview.second

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        // 標題列（含返回按鈕）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "自訂折扣",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier   = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            "原始金額：${currency.format(grandTotal)}",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
        )

        // 折扣類型切換（SegmentedButton）
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("百分比折抵 %", "固定金額折抵 $").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = typeIndex == index,
                    onClick  = { typeIndex = index; inputText = "" },
                    shape    = SegmentedButtonDefaults.itemShape(index, count = 2),
                ) { Text(label) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 輸入列（數字框 + 單位標籤）
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value           = inputText,
                onValueChange   = { inputText = it.filter { c -> c.isDigit() } },
                modifier        = Modifier.weight(1f),
                singleLine      = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder     = {
                    Text(if (typeIndex == 0) "輸入折扣百分比..." else "輸入折扣金額...")
                },
            )
            Text(
                text       = if (typeIndex == 0) "%" else "元",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier   = Modifier.width(32.dp),
                textAlign  = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))

        // 即時預覽
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "折扣金額",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
                Text(
                    currency.format(discountAmt),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color      = if (discountAmt > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "折扣後應收",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
                Text(
                    currency.format(afterTotal),
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color      = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // 套用按鈕
        Button(
            onClick  = { onApply(discountAmt) },
            enabled  = discountAmt > 0,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape    = RoundedCornerShape(16.dp),
        ) {
            Text(
                "套用折扣",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 找零建議計算輔助
// ════════════════════════════════════════════════════════════════════════════

/** 向上整除：ceil(n / d)，n、d 為正。 */
private fun ceilDivPositive(n: Long, d: Long): Long = (n + d - 1) / d

/**
 * 智慧找零快捷：候選自台幣常見面額向上進位，去重、去掉 ≤ 應收後取最小 3 個。
 */
private fun smartTenderSuggestions(total: Long): List<Long> {
    if (total <= 0L) return emptyList()
    val c50 = ceilDivPositive(total, 50L) * 50L
    val c100 = ceilDivPositive(total, 100L) * 100L
    val c500 = ceilDivPositive(total, 500L) * 500L
    val k = ceilDivPositive(total, 1000L) * 1000L
    return listOf(
        c50,
        c100,
        c500,
        k,
        k + 500L,
        k + 1000L,
    ).distinct()
        .filter { it > total }
        .sortedBy { it }
        .take(3)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckoutBottomSheet(
    sheetState: SheetState,
    total:      Long,
    currency:   NumberFormat,
    onDismiss:  () -> Unit,
    onConfirm:  (PaymentMethod, tipAmount: Long) -> Unit,
    feedback:    PosFeedbackManager,
) {
    var paymentMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    var cashInput by remember { mutableStateOf("") }
    var tipAbsorbed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(cashInput, paymentMethod, total) {
        tipAbsorbed = 0L
    }

    val received = cashInput.toLongOrNull() ?: 0L
    val change   = received - total
    val displayChange = (change - tipAbsorbed).coerceAtLeast(0L)
    val canCheckout = when {
        total <= 0L -> false
        paymentMethod == PaymentMethod.DIGITAL -> true
        else -> received >= total
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        dragHandle       = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SegmentedButton(
                    selected      = paymentMethod == PaymentMethod.CASH,
                    onClick       = {
                        if (paymentMethod != PaymentMethod.CASH) {
                            paymentMethod = PaymentMethod.CASH
                            cashInput = ""
                        }
                    },
                    shape         = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("現金", fontWeight = FontWeight.Bold) }
                SegmentedButton(
                    selected      = paymentMethod == PaymentMethod.DIGITAL,
                    onClick       = {
                        if (paymentMethod != PaymentMethod.DIGITAL) {
                            paymentMethod = PaymentMethod.DIGITAL
                            cashInput = ""
                        }
                    },
                    shape         = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("行動支付", fontWeight = FontWeight.Bold) }
            }
            Text(
                when (paymentMethod) {
                    PaymentMethod.CASH    -> "結帳與找零"
                    PaymentMethod.DIGITAL -> "行動支付結帳"
                },
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                "應收　${currency.format(total)}",
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color      = MaterialTheme.colorScheme.primary,
                modifier   = Modifier.testTag(CheckoutSheetTestTags.RECEIVABLE),
            )
            if (paymentMethod == PaymentMethod.CASH && total > 0L) {
                val suggestions = remember(total) { smartTenderSuggestions(total) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick     = { cashInput = total.toString() },
                        modifier    = Modifier.defaultMinSize(minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape       = RoundedCornerShape(8.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "剛好",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                currency.format(total),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                    suggestions.forEach { amt ->
                        FilledTonalButton(
                            onClick        = { cashInput = amt.toString() },
                            modifier       = Modifier.defaultMinSize(minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            shape          = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                currency.format(amt),
                                style      = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black,
                                maxLines   = 1,
                            )
                        }
                    }
                }
            }
            if (paymentMethod == PaymentMethod.CASH) {
                OutlinedTextField(
                    value           = cashInput,
                    onValueChange   = { cashInput = it.filter { ch -> ch.isDigit() } },
                    label           = { Text("收款金額", style = MaterialTheme.typography.titleMedium) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine      = true,
                    modifier        = Modifier
                        .fillMaxWidth()
                        .testTag(CheckoutSheetTestTags.CASH_INPUT),
                    textStyle       = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                when {
                    received == 0L ->
                        Text(
                            "請輸入客人交付的金額",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    change < 0L ->
                        Text(
                            "收款不足，尚差 ${currency.format(-change)}",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.error,
                        )
                    else -> {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                "應找　${currency.format(displayChange)}",
                                style      = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                color      = LinearSemanticSuccess,
                            )
                            when {
                                change > 0L && tipAbsorbed == 0L ->
                                    FilledTonalButton(
                                        onClick = { tipAbsorbed = change },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 56.dp),
                                        shape    = RoundedCornerShape(16.dp),
                                        colors   = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Filled.Favorite,
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                            )
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "轉為小費（Boost）",
                                                    style      = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Black,
                                                )
                                                Text(
                                                    "將找零 ${currency.format(change)} 全部計入本筆小費",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.62f),
                                                )
                                            }
                                        }
                                    }
                                tipAbsorbed > 0L ->
                                    Surface(
                                        shape               = RoundedCornerShape(16.dp),
                                        color               = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                        modifier            = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                "已計入小費",
                                                fontWeight = FontWeight.Bold,
                                                style      = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                currency.format(tipAbsorbed),
                                                fontWeight = FontWeight.Black,
                                                style      = MaterialTheme.typography.titleMedium,
                                                color      = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    if (!canCheckout) {
                        if (paymentMethod == PaymentMethod.CASH && total > 0L && received > 0L && received < total) {
                            feedback.error()
                        }
                        return@Button
                    }
                    val tip = if (paymentMethod == PaymentMethod.CASH) tipAbsorbed else 0L
                    onConfirm(paymentMethod, tip)
                },
                enabled  = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag(CheckoutSheetTestTags.CONFIRM),
                shape    = RoundedCornerShape(16.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = if (canCheckout) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor           = if (canCheckout) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
            ) {
                Text(
                    "確認結帳",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
            }
            TextButton(
                onClick     = onDismiss,
                modifier    = Modifier.fillMaxWidth(),
            ) {
                Text("返回", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
