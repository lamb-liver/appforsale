package com.lambliver.appforsale.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lambliver.appforsale.ui.billing.SponsorBillingViewModel
import com.lambliver.appforsale.ui.pos.PosAppShell
import com.lambliver.appforsale.ui.pos.rememberPosOverlayState
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(vm: PosViewModel = viewModel()) {
    val billingVm: SponsorBillingViewModel = viewModel()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.TAIWAN) }
    val scope = rememberCoroutineScope()
    val overlay = rememberPosOverlayState(scope)

    LaunchedEffect(vm.toastFlow) {
        vm.toastFlow.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(billingVm) {
        billingVm.billingMessage.collect { snackbarHostState.showSnackbar(it) }
    }

    PosAppShell(
        vm = vm,
        billingVm = billingVm,
        uiState = uiState,
        currency = currency,
        snackbarHostState = snackbarHostState,
        overlay = overlay,
    )
}
