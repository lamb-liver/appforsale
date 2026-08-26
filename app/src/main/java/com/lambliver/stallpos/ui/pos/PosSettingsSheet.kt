package com.lambliver.stallpos.ui.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lambliver.stallpos.BuildConfig
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.ui.DeveloperContact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PosSettingsSheet(
    backupNeeded: Boolean,
    extraLargeText: Boolean,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    latestVersionTag: String?,
    checkDataMessage: String?,
    inactiveProducts: List<Product>,
    onDismiss: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onSnoozeBackup: () -> Unit,
    onCheckData: () -> Unit,
    onExtraLargeTextChange: (Boolean) -> Unit,
    onHapticEnabledChange: (Boolean) -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
    onReactivateProduct: (Product) -> Unit,
    onReportToDeveloper: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PosSettingsContent(
            backupNeeded = backupNeeded,
            extraLargeText = extraLargeText,
            hapticEnabled = hapticEnabled,
            soundEnabled = soundEnabled,
            latestVersionTag = latestVersionTag,
            checkDataMessage = checkDataMessage,
            inactiveProducts = inactiveProducts,
            onExportBackup = onExportBackup,
            onRestoreBackup = onRestoreBackup,
            onSnoozeBackup = onSnoozeBackup,
            onCheckData = onCheckData,
            onExtraLargeTextChange = onExtraLargeTextChange,
            onHapticEnabledChange = onHapticEnabledChange,
            onSoundEnabledChange = onSoundEnabledChange,
            onReactivateProduct = onReactivateProduct,
            onReportToDeveloper = onReportToDeveloper,
        )
    }
}

@Composable
internal fun PosSettingsContent(
    backupNeeded: Boolean,
    extraLargeText: Boolean,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    latestVersionTag: String?,
    checkDataMessage: String?,
    inactiveProducts: List<Product>,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onSnoozeBackup: () -> Unit,
    onCheckData: () -> Unit,
    onExtraLargeTextChange: (Boolean) -> Unit,
    onHapticEnabledChange: (Boolean) -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
    onReactivateProduct: (Product) -> Unit,
    onReportToDeveloper: () -> Unit,
) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "設定",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                modifier = Modifier.testTag("settings-sheet"),
            )
            Text("備份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (backupNeeded) {
                Text("有尚未備份的變更", color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onSnoozeBackup) { Text("稍後") }
            }
            OutlinedButton(
                onClick = onExportBackup,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("匯出備份") }
            OutlinedButton(
                onClick = onRestoreBackup,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("還原備份") }
            HorizontalDivider()
            Text("資料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onCheckData,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("check-data"),
            ) { Text("檢查資料") }
            checkDataMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Text("顯示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PreferenceToggle("大字體", extraLargeText, onExtraLargeTextChange)
            PreferenceToggle("震動回饋", hapticEnabled, onHapticEnabledChange)
            PreferenceToggle("音效回饋", soundEnabled, onSoundEnabledChange)
            if (inactiveProducts.isNotEmpty()) {
                HorizontalDivider()
                Text("已停用商品", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                inactiveProducts.forEach { product ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(product.name, Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { onReactivateProduct(product) }) { Text("重新啟用") }
                    }
                }
            }
            HorizontalDivider()
            Text("版本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("目前 v${BuildConfig.VERSION_NAME}")
            latestVersionTag?.let {
                Text("有新版本 $it 可下載", color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(
                onClick = onReportToDeveloper,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("回報給開發者") }
            Text(
                "寄到 ${DeveloperContact.EMAIL}，會附上裝置與上傳狀態，不含帳號或交易",
                style = MaterialTheme.typography.bodySmall,
            )
        }
}

@Composable
private fun PreferenceToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    OutlinedButton(
        onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text("$label　${if (checked) "開" else "關"}")
    }
}
