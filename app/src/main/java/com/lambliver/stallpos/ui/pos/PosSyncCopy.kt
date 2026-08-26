package com.lambliver.stallpos.ui.pos

import com.lambliver.stallpos.domain.SyncUiState
import com.lambliver.stallpos.domain.SyncUiStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun SyncUiState.displayText(): String = when (status) {
    SyncUiStatus.LOCAL_ONLY -> "還沒開雲端"
    SyncUiStatus.LOADING -> "連線中…"
    SyncUiStatus.SYNCED -> if (lastSyncedAtMillis == null) "已登入，還沒上傳過" else "已上傳"
    SyncUiStatus.PENDING -> "$pendingCount 筆還沒上傳"
    SyncUiStatus.BLOCKED -> "$blockedCount 筆上傳失敗"
    SyncUiStatus.ERROR -> message ?: "暫時傳不上去，稍後會再試"
}

internal fun SyncUiState.lastSyncText(): String? = when {
    status == SyncUiStatus.LOCAL_ONLY || status == SyncUiStatus.LOADING -> null
    lastSyncedAtMillis != null -> "上次上傳：${SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(lastSyncedAtMillis))}"
    else -> "還沒成功上傳過"
}

internal fun SyncUiState.attentionText(): String? = when (status) {
    SyncUiStatus.PENDING -> "$pendingCount 筆還沒上傳"
    SyncUiStatus.BLOCKED -> "$blockedCount 筆上傳失敗"
    SyncUiStatus.ERROR -> message ?: "暫時傳不上去"
    else -> null
}

internal fun SyncUiState.blockedReasonText(): String? = when (blockedCode) {
    "DEVICE_RETIRED" -> "這支手機已被換掉，請用現在登入的那支。"
    "CLOUD_EPOCH_REVOKED" -> "雲端帳號已重置，請重新登入。"
    "ACCOUNT_DELETED" -> "雲端帳號已刪除。"
    "INVALID_DATA" -> "有資料傳不上去，請用「回報給開發者」。"
    "SERVER_CONFLICT" -> "跟雲端資料對不起來，請用「回報給開發者」。"
    else -> null
}

internal fun SyncUiState.checkoutFollowUpText(): String? = when {
    status == SyncUiStatus.BLOCKED ->
        "結帳完成，還有 $blockedCount 筆沒上傳。"
    emphasizesPending ->
        "結帳完成，還有 $pendingCount 筆沒上傳。"
    else -> null
}
