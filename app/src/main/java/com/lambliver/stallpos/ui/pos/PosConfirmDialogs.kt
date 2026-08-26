package com.lambliver.stallpos.ui.pos

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ExitConfirmDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "離開？",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = "購物車會先存起來。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("繼續使用", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    (context as? ComponentActivity)?.finish()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("離開", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
internal fun NonCashVoidConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作廢非現金交易？") },
        text = {
            Text("這筆不是現金。作廢只改這裡的紀錄並補回庫存，LINE Pay／街口不會自動退款。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("確定作廢", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun RestoreBackupConfirmDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (Uri) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("還原備份") },
        text = {
            Text(
                "會用備份蓋掉現在的商品、套組、購物車和銷售紀錄。\n\n做了沒辦法復原，確定繼續？",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(uri)
                    onDismiss()
                },
            ) {
                Text("確定還原", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
