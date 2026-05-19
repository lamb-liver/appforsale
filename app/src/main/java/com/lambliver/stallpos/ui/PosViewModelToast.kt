package com.lambliver.stallpos.ui

import androidx.lifecycle.viewModelScope
import com.lambliver.stallpos.domain.PosToast
import com.lambliver.stallpos.domain.PosToastSeverity
import kotlinx.coroutines.launch

internal suspend fun PosViewModel.emitToast(
    message: String,
    severity: PosToastSeverity = PosToastSeverity.Info,
) {
    posToastChannel.send(PosToast(message, severity))
}

internal fun PosViewModel.emitToastAsync(
    message: String,
    severity: PosToastSeverity = PosToastSeverity.Info,
) {
    viewModelScope.launch { emitToast(message, severity) }
}
