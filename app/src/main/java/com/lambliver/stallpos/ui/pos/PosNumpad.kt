package com.lambliver.stallpos.ui.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp


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
