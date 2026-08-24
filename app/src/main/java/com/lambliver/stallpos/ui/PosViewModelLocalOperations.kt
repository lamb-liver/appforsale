package com.lambliver.stallpos.ui

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.launch
import java.util.TimeZone

internal fun PosViewModel.createMarketEvent(name: String, type: MarketEventType, location: String) {
    val now = System.currentTimeMillis()
    val event = MarketEvent.create(
        name = name,
        type = type,
        startAtMillis = now,
        endAtMillis = now + 12 * 60 * 60 * 1_000L,
        timezone = TimeZone.getDefault().id,
        location = location,
        nowMillis = now,
    )
    launchLocalOperation("create event", "建立活動失敗") { posStore.saveEvent(event) }
}

internal fun PosViewModel.changeMarketEventStatus(eventId: String, status: MarketEventStatus) {
    launchLocalOperation("change event status", "更新活動失敗") {
        posStore.changeEventStatus(eventId, status)
    }
}

internal fun PosViewModel.closeMarketEvent(eventId: String) {
    launchLocalOperation("close event", "結束活動失敗") {
        posStore.closeEventAndReturnInventory(eventId)
    }
}

internal fun PosViewModel.moveInventory(event: PosEvent.MoveInventory) {
    launchLocalOperation("move inventory", "更新庫存失敗") {
        posStore.moveInventory(event.productId, event.quantity, event.from, event.to, event.type)
    }
}

private fun PosViewModel.launchLocalOperation(logName: String, userMessage: String, block: suspend () -> Unit) {
    viewModelScope.launch {
        try {
            block()
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "$logName failed", e)
            emitToast("$userMessage：${e.message}", PosToastSeverity.Error)
        }
    }
}
