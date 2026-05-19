package com.lambliver.appforsale.ui.pos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lambliver.appforsale.ui.theme.PosCheckoutOrange

@Composable
internal fun PosCheckoutButton(
    displayAmount: Long,
    itemCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val isActive = enabled && pressed

    val scale by animateFloatAsState(
        targetValue = if (isActive) 0.97f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "checkoutButtonScale",
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = 80),
        label = "checkoutButtonPress",
    )
    val backgroundColor = lerp(PosCheckoutOrange, PosCheckoutOrange.darken(0.1f), pressProgress)

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.3f)
            .scale(scale)
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "結帳",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (enabled) {
                Text(
                    text = "${"%,d".format(displayAmount)} · $itemCount 件",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

private fun Color.darken(fraction: Float): Color = copy(
    red = red * (1f - fraction),
    green = green * (1f - fraction),
    blue = blue * (1f - fraction),
)
