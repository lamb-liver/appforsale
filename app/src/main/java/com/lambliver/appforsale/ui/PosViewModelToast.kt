package com.lambliver.appforsale.ui

import androidx.lifecycle.viewModelScope
import com.lambliver.appforsale.domain.PosToast
import com.lambliver.appforsale.domain.PosToastSeverity
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
