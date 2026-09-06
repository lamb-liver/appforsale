package com.lambliver.stallpos.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
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
            onGoogleSignIn = { forceDevice ->
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
                    }.onSuccess { vm.signInWithGoogleIdToken(it, forceDevice) }
                        .onFailure { err ->
                            val type = (err as? GetCredentialException)?.type.orEmpty()
                            Log.e("StallPosGoogle", "sign-in failed type=$type class=${err::class.java.name} cause=${err.cause}", err)
                            val raw = err.message.orEmpty()
                            val hint = when {
                                raw.contains("canceled", ignoreCase = true) ||
                                    raw.contains("cancelled", ignoreCase = true) ||
                                    type.contains("TYPE_USER_CANCELED") ->
                                    "沒選帳號。請再按一次加入。"
                                raw.contains("No credentials", ignoreCase = true) ||
                                    type.contains("TYPE_NO_CREDENTIAL") ->
                                    "這台還沒有 Google 帳號，登入選單出不來。請先打開 Play 商店用同一個 Gmail 登入，再回到這裡按加入。"
                                raw.contains("GetCredentialResponse", ignoreCase = true) ||
                                    type.contains("TYPE_UNKNOWN") ->
                                    "Google 拒絕這支開發版簽章。把 debug SHA-1 加到 Google Cloud OAuth Android client（套件 com.lambliver.stallpos）。"
                                else -> raw.ifBlank { "請稍後再試" }
                            }
                            vm.reportGoogleSignInFailure(hint)
                        }
                }
            },
        )
    }
}
