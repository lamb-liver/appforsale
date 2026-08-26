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
import androidx.compose.ui.graphics.Color
import com.lambliver.stallpos.ui.feedback.PosFeedbackManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lambliver.stallpos.ui.theme.LinearSemanticSuccess
import com.lambliver.stallpos.ui.theme.PosCheckoutOrange
import java.text.NumberFormat


/** Robolectric Compose 測試用 semantics 標籤。 */
internal object CheckoutSheetTestTags {
    const val RECEIVABLE = "checkout_sheet_receivable"
    const val CASH_INPUT = "checkout_cash_input"
    const val LINE_PAY_PAYMENT = "checkout_line_pay_payment"
    const val CONFIRM = "checkout_confirm_button"
}


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
        paymentMethod != PaymentMethod.CASH -> true
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
                    shape         = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                ) { Text("現金", fontWeight = FontWeight.Bold) }
                SegmentedButton(
                    selected      = paymentMethod == PaymentMethod.LINE_PAY,
                    onClick       = {
                        if (paymentMethod != PaymentMethod.LINE_PAY) {
                            paymentMethod = PaymentMethod.LINE_PAY
                            cashInput = ""
                        }
                    },
                    modifier      = Modifier.testTag(CheckoutSheetTestTags.LINE_PAY_PAYMENT),
                    shape         = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                ) { Text("LINE Pay", fontWeight = FontWeight.Bold) }
                SegmentedButton(
                    selected = paymentMethod == PaymentMethod.JKOPAY,
                    onClick = { paymentMethod = PaymentMethod.JKOPAY; cashInput = "" },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                ) { Text("街口", fontWeight = FontWeight.Bold) }
                SegmentedButton(
                    selected = paymentMethod == PaymentMethod.OTHER,
                    onClick = { paymentMethod = PaymentMethod.OTHER; cashInput = "" },
                    shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                ) { Text("其他", fontWeight = FontWeight.Bold) }
            }
            Text(
                if (paymentMethod == PaymentMethod.CASH) "結帳與找零" else "${paymentMethod.displayName}結帳",
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
                    containerColor         = if (canCheckout) PosCheckoutOrange
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor           = if (canCheckout) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
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
