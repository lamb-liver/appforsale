package com.lambliver.stallpos.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lambliver.stallpos.domain.PosToastSeverity
import com.lambliver.stallpos.ui.feedback.rememberPosFeedback
import com.lambliver.stallpos.ui.pos.PosAppShell
import com.lambliver.stallpos.ui.pos.rememberPosOverlayState
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(vm: PosViewModel = viewModel()) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.TAIWAN) }
    val scope = rememberCoroutineScope()
    val overlay = rememberPosOverlayState(scope)

    val hapticEnabled by vm.hapticEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val soundEnabled by vm.soundEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val feedback = rememberPosFeedback(hapticEnabled, soundEnabled)

    LaunchedEffect(vm.toastFlow, feedback) {
        vm.toastFlow.collect { toast ->
            if (toast.severity == PosToastSeverity.Error) {
                feedback.error()
            }
            snackbarHostState.showSnackbar(toast.message)
        }
    }

    PosAppShell(
        vm = vm,
        uiState = uiState,
        currency = currency,
        snackbarHostState = snackbarHostState,
        overlay = overlay,
        feedback = feedback,
        hapticEnabled = hapticEnabled,
        soundEnabled = soundEnabled,
        onHapticEnabledChange = vm::setHapticEnabled,
        onSoundEnabledChange = vm::setSoundEnabled,
    )
}
