package com.lambliver.appforsale.ui.sponsor

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lambliver.appforsale.ui.billing.SponsorBillingViewModel
import com.lambliver.appforsale.ui.billing.SponsorProductIds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorDeveloperBottomSheet(
    billingVm: SponsorBillingViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (visible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            SponsorDeveloperContent(billingVm = billingVm, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun SponsorDeveloperContent(
    billingVm: SponsorBillingViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val connectionReady by billingVm.connectionReady.collectAsStateWithLifecycle()
    val products by billingVm.productDetails.collectAsStateWithLifecycle()

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
            text = if (connectionReady) "選擇一個方案以完成付款（需登入 Google Play）" else "正在連線至 Google Play…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SponsorTierButton(
            label = "打道音遊",
            enabled = connectionReady && products.containsKey(SponsorProductIds.TIER_SMALL),
            onClick = { billingVm.launchPurchaseFromContext(context, SponsorProductIds.TIER_SMALL) },
        )
        SponsorTierButton(
            label = "喝杯咖啡",
            enabled = connectionReady && products.containsKey(SponsorProductIds.TIER_MEDIUM),
            onClick = { billingVm.launchPurchaseFromContext(context, SponsorProductIds.TIER_MEDIUM) },
        )
        SponsorTierButton(
            label = "吃個便當",
            enabled = connectionReady && products.containsKey(SponsorProductIds.TIER_LARGE),
            onClick = { billingVm.launchPurchaseFromContext(context, SponsorProductIds.TIER_LARGE) },
        )

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
