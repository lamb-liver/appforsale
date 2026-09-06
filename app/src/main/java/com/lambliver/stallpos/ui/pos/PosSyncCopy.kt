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

internal const val MULTI_DEVICE_JOIN_TOAST =
    "這支手機已加入同一帳號。購物車與今日統計只算這支；全部營收請到網頁看。"
internal const val MULTI_DEVICE_STOCK_WARNING =
    "有開庫存追蹤時，多支手機可能賣超；超賣那筆可能已經收了客人的錢，但傳不進雲端。多機請關閉庫存追蹤，或事先把數量拆開。"
internal const val DEVICE_RETIRED_LOCK = "這支手機已退役，無法再收款。"
internal const val DEVICE_LIMIT_COPY = "這個帳號已有 4 支手機在用。請在這支按「接手」（會退役其他所有手機）。"
internal const val NEW_DEVICE_JOINED_TOAST = "有另一支手機加入這個帳號"
internal const val TAKEOVER_BUTTON = "把帳換到這支並退役其他所有手機"
internal const val JOIN_BUTTON = "用 Google 登入，加入此帳號"
internal const val CLOUD_MULTI_DEVICE_NOTE =
    "這支是其中一台收銀機。其他手機收的錢不會出現在這支的今日統計。目錄與活動請只在一台改；改之前先讓其他手機上傳完。多機請關閉庫存追蹤。"
internal const val JOIN_TAKEOVER_HINT = "加入：多支都能收錢。接手：把帳換到這支並退役其他所有手機。"

internal fun SyncUiState.blockedReasonText(): String? = when (blockedCode) {
    "DEVICE_RETIRED" -> "這支手機已被換掉或接手，無法再收款。請用還在用的那支，或在這支按「接手」。"
    "CLOUD_EPOCH_REVOKED" -> "雲端帳號已重置，請重新登入。"
    "ACCOUNT_DELETED" -> "雲端帳號已刪除。"
    "INVALID_DATA" -> "有資料傳不上去。錢若已收，這筆會留在本機並標「待處理」。"
    "SERVER_CONFLICT" -> "跟雲端資料對不起來，請用「回報給開發者」。"
    "DEVICE_LIMIT" -> DEVICE_LIMIT_COPY
    else -> null
}

internal fun SyncUiState.checkoutFollowUpText(): String? = when {
    status == SyncUiStatus.BLOCKED ->
        "結帳完成，還有 $blockedCount 筆沒上傳。"
    emphasizesPending ->
        "結帳完成，還有 $pendingCount 筆沒上傳。"
    else -> null
}
