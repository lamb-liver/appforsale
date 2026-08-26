package com.lambliver.stallpos.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.lambliver.stallpos.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lambliver.stallpos.domain.PosToastSeverity
import com.lambliver.stallpos.ui.feedback.rememberPosFeedback
import com.lambliver.stallpos.ui.pos.PosAppShell
import com.lambliver.stallpos.ui.pos.rememberPosOverlayState
import com.lambliver.stallpos.ui.theme.StallPosTheme
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(vm: PosViewModel = viewModel()) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.TAIWAN) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }
    val overlay = rememberPosOverlayState(scope)

    val hapticEnabled by vm.hapticEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val soundEnabled by vm.soundEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val extraLargeText by vm.extraLargeTextFlow.collectAsStateWithLifecycle(initialValue = false)
    val backupReminderVisible by vm.backupReminderVisible.collectAsStateWithLifecycle()
    val feedback = rememberPosFeedback(hapticEnabled, soundEnabled)

    LaunchedEffect(vm.toastFlow, feedback) {
        vm.toastFlow.collect { toast ->
            if (toast.severity == PosToastSeverity.Error) {
                feedback.error()
            }
            snackbarHostState.showSnackbar(toast.message)
        }
    }

    StallPosTheme(extraLargeText = extraLargeText) {
        PosAppShell(
            vm = vm,
            uiState = uiState,
            currency = currency,
            snackbarHostState = snackbarHostState,
            overlay = overlay,
            feedback = feedback,
            backupReminderVisible = backupReminderVisible,
            cloudLoginConfigured = BuildConfig.SYNC_BASE_URL.isNotBlank() && BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank(),
            onGoogleSignIn = {
                scope.launch {
                    runCatching {
                        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                            .setNonce(UUID.randomUUID().toString())
                            .build()
                        val credential = credentialManager.getCredential(
                            context,
                            GetCredentialRequest.Builder().addCredentialOption(option).build(),
                        ).credential
                        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    }.onSuccess { vm.signInWithGoogleIdToken(it) }
                        .onFailure { vm.reportGoogleSignInFailure(it.message) }
                }
            },
        )
    }
}
