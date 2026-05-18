package com.lambliver.appforsale.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lambliver.appforsale.domain.BundleCategory
import com.lambliver.appforsale.domain.Category

// ════════════════════════════════════════════════════════════════════════════
// 套組分類操作底部 Sheet
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleCategoryActionSheet(
    category:   BundleCategory,
    sheetState: SheetState,
    onRename:   (String) -> Unit,
    onDelete:   () -> Unit,
    onDismiss:  () -> Unit,
) {
    var renameMode by remember { mutableStateOf(false) }
    var newName    by remember { mutableStateOf(category.name) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text       = category.name,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            HorizontalDivider()
            if (renameMode) {
                Row(
                    modifier              = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        label         = { Text("分類名稱") },
                        singleLine    = true,
                        modifier      = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick  = { if (newName.isNotBlank()) onRename(newName.trim()) },
                        enabled  = newName.isNotBlank(),
                    ) { Icon(Icons.Default.Check, contentDescription = "確認") }
                }
            } else {
                ListItem(
                    headlineContent = { Text("重命名", fontSize = 18.sp) },
                    leadingContent  = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier        = Modifier.clickable { renameMode = true },
                )
                ListItem(
                    headlineContent = {
                        Text("刪除分類", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent  = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 分類操作底部 Sheet（長按分類 Tab 觸發）
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryActionSheet(
    category:   Category,
    sheetState: SheetState,
    onRename:   (String) -> Unit,
    onDelete:   () -> Unit,
    onDismiss:  () -> Unit,
) {
    var renameMode by remember { mutableStateOf(false) }
    var newName    by remember { mutableStateOf(category.name) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text       = category.name,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            HorizontalDivider()
            if (renameMode) {
                Row(
                    modifier              = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        label         = { Text("分類名稱") },
                        singleLine    = true,
                        modifier      = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick  = { if (newName.isNotBlank()) onRename(newName.trim()) },
                        enabled  = newName.isNotBlank(),
                    ) { Icon(Icons.Default.Check, contentDescription = "確認") }
                }
            } else {
                ListItem(
                    headlineContent = { Text("重命名", fontSize = 18.sp) },
                    leadingContent  = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier        = Modifier.clickable { renameMode = true },
                )
                ListItem(
                    headlineContent = {
                        Text("刪除分類", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent  = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
        }
    }
}
