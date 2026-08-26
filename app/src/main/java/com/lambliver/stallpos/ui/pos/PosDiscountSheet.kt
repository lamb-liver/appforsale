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
