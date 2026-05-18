package com.lambliver.appforsale.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

data class TourStep(
    val title: String,
    val desc: String,
    val bounds: Rect?,
)

@Composable
fun OnboardingTour(
    steps: List<TourStep>,
    onFinish: () -> Unit,
) {
    var stepIdx by remember { mutableIntStateOf(0) }

    if (stepIdx >= steps.size) {
        SideEffect { onFinish() }
        return
    }

    val step = steps[stepIdx]

    fun advance() {
        if (stepIdx + 1 >= steps.size) onFinish() else stepIdx++
    }

    BackHandler(enabled = true) {
        advance()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { advance() },
    ) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val bounds = step.bounds

        // ── 半透明遮罩 + 挖空 ────────────────────────────────────────────
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(Color(0xB3000000))
            if (bounds != null) {
                val pad = 16.dp.toPx()
                drawRoundRect(
                    color      = Color.Transparent,
                    topLeft    = Offset(bounds.left - pad, bounds.top - pad),
                    size       = Size(bounds.width + pad * 2, bounds.height + pad * 2),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    blendMode  = BlendMode.Clear,
                )
            }
        }

        if (bounds != null) {
            // 目標在螢幕下半 → 文字擺上方；反之擺下方
            val textAbove = bounds.center.y > screenH * 0.40f

            // ── 虛線箭頭 ─────────────────────────────────────────────────
            Canvas(Modifier.fillMaxSize()) {
                val pad     = 16.dp.toPx()
                val arrowLen = 48.dp.toPx()
                val arrowTipY  = if (textAbove) bounds.top  - pad else bounds.bottom + pad
                val arrowBaseY = if (textAbove) arrowTipY - arrowLen else arrowTipY + arrowLen
                val arrowX = bounds.center.x.coerceIn(24.dp.toPx(), screenW - 24.dp.toPx())

                drawLine(
                    color       = Color.White,
                    start       = Offset(arrowX, arrowBaseY),
                    end         = Offset(arrowX, arrowTipY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect  = PathEffect.dashPathEffect(floatArrayOf(10f, 5f)),
                )

                val ah = 8.dp.toPx()
                drawPath(
                    color = Color.White,
                    path  = Path().apply {
                        if (textAbove) {
                            moveTo(arrowX, arrowTipY)
                            lineTo(arrowX - ah, arrowTipY - ah * 1.6f)
                            lineTo(arrowX + ah, arrowTipY - ah * 1.6f)
                        } else {
                            moveTo(arrowX, arrowTipY)
                            lineTo(arrowX - ah, arrowTipY + ah * 1.6f)
                            lineTo(arrowX + ah, arrowTipY + ah * 1.6f)
                        }
                        close()
                    },
                )
            }

            // ── 說明文字卡片 ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        val pad      = 16.dp.roundToPx()
                        val arrowLen = 48.dp.roundToPx()
                        val margin   = 16.dp.roundToPx()
                        val y = if (textAbove) {
                            val base = bounds.top.toInt() - pad - arrowLen
                            (base - 140.dp.roundToPx() - margin).coerceAtLeast(56.dp.roundToPx())
                        } else {
                            val base = bounds.bottom.toInt() + pad + arrowLen
                            (base + margin).coerceAtMost(screenH.toInt() - 184.dp.roundToPx())
                        }
                        IntOffset(0, y)
                    }
                    .padding(horizontal = 24.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(16.dp),
                    color    = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier            = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text       = step.title,
                            color      = MaterialTheme.colorScheme.onSurface,
                            style      = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign  = TextAlign.Center,
                        )
                        Text(
                            text      = step.desc,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                            style     = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text  = "點擊任意處繼續",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        // ── 跳過 + 步驟計數 ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text  = "${stepIdx + 1} / ${steps.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onFinish) {
                Text("跳過導覽", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
