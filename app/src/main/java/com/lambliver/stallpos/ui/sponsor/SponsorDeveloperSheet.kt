package com.lambliver.stallpos.ui.sponsor

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorDeveloperBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (visible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            SponsorDeveloperContent(onDismiss = onDismiss)
        }
    }
}

@Composable
private fun SponsorDeveloperContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val anyConfigured = SponsorLinks.isConfigured()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "贊助開發者",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "純自願支持，與攤位結帳無關。\n請選擇金額後於瀏覽器完成綠界付款。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (!anyConfigured) {
            Text(
                text = "付款連結設定中，完成綠界申請後即可開放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        SponsorLinks.tiers.forEach { tier ->
            SponsorTierButton(
                label = tier.buttonLabel,
                enabled = tier.isConfigured,
                onClick = {
                    if (!context.openSponsorPayment(tier.paymentUrl)) {
                        Toast.makeText(context, "無法開啟付款頁面", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("關閉", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SponsorTierButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}
