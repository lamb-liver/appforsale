package com.lambliver.stallpos.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberPosFeedback(
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
): PosFeedbackManager {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val appContext = remember(activity) { activity.applicationContext }
    val manager = remember(activity) {
        PosFeedbackManager.create(activity)
    }
    LaunchedEffect(activity) {
        withContext(Dispatchers.IO) {
            manager.loadSounds(appContext)
        }
    }
    SideEffect {
        manager.updatePreferences(hapticEnabled, soundEnabled)
    }
    DisposableEffect(activity) {
        onDispose { manager.release() }
    }
    return manager
}
