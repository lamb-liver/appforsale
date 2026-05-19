package com.lambliver.stallpos.ui.dialog

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val NewCategorySlideTrackWidth = 192.dp
internal val NewCategorySlideThumbSize = 40.dp

/** 點擊後圓鈕由左自動滑到右（短軌、快動畫）；完成後短暫顯示「已新增分類」。 */
@Composable
internal fun SlideToConfirmNewCategory(
    enabled: Boolean,
    onCommitted: () -> Unit,
    onSuccessDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val thumbSize = NewCategorySlideThumbSize
    val thumbPx = with(density) { thumbSize.toPx() }

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var thumbOffsetPx by remember { mutableFloatStateOf(0f) }
    var showSuccess by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    val maxDrag = remember(trackWidthPx, thumbPx) {
        (trackWidthPx - thumbPx).coerceAtLeast(0f)
    }

    LaunchedEffect(enabled, showSuccess) {
        if (!enabled && !showSuccess) {
            thumbOffsetPx = 0f
            running = false
        }
    }

    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            delay(400)
            showSuccess = false
            thumbOffsetPx = 0f
            running = false
            onSuccessDone()
        }
    }

    val canTap = enabled && maxDrag > 0f && !running && !showSuccess

    if (showSuccess) {
        Box(
            modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .width(NewCategorySlideTrackWidth)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "已新增分類",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    } else {
        Box(
            modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .width(NewCategorySlideTrackWidth)
                    .height(48.dp)
                    .onSizeChanged { trackWidthPx = it.width.toFloat() }
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(
                        enabled = canTap,
                        onClick = {
                            if (!canTap) return@clickable
                            running = true
                            scope.launch {
                                try {
                                    thumbOffsetPx = 0f
                                    animate(
                                        initialValue = 0f,
                                        targetValue = maxDrag,
                                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                                    ) { v, _ ->
                                        thumbOffsetPx = v
                                    }
                                    onCommitted()
                                    showSuccess = true
                                } finally {
                                    running = false
                                }
                            }
                        },
                    ),
            ) {
                val hint = when {
                    !enabled -> "請輸入名稱"
                    running -> "新增中…"
                    else -> "點一下新增"
                }
                Text(
                    text = hint,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                )
                Box(
                    modifier = Modifier
                        .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                        .align(Alignment.CenterStart)
                        .padding(8.dp)
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
