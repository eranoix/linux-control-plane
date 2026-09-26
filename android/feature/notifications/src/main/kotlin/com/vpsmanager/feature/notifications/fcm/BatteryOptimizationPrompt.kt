package com.vpsmanager.feature.notifications.fcm

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vpsmanager.data.push.PushOnboardingState

/**
 * Shown once, in the same post-login onboarding moment as the `POST_NOTIFICATIONS` request,
 * explaining that exempting the app from battery optimization materially improves push delivery
 * reliability on OEMs known for aggressive throttling (Samsung/Xiaomi/OnePlus). Explanatory and
 * consent-seeking, never a forced gate — declining dismisses it for good (see
 * [PushOnboardingState]), it is never re-shown on a later launch.
 */
@Composable
fun BatteryOptimizationPrompt(onDismissed: () -> Unit) {
    val context = LocalContext.current
    val onboardingState = remember { PushOnboardingState(context) }

    AlertDialog(
        onDismissRequest = {
            onboardingState.markBatteryOptimizationPromptSeen()
            onDismissed()
        },
        title = { Text("Notificações mais confiáveis") },
        text = {
            Text(
                "Alguns fabricantes (Samsung, Xiaomi, OnePlus) restringem apps em segundo " +
                    "plano de um jeito que atrasa ou bloqueia notificações de deploy e alertas. " +
                    "Permitir \"sem restrições\" de bateria para o VPS Manager evita isso.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onboardingState.markBatteryOptimizationPromptSeen()
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
                onDismissed()
            }) { Text("Permitir") }
        },
        dismissButton = {
            TextButton(onClick = {
                onboardingState.markBatteryOptimizationPromptSeen()
                onDismissed()
            }) { Text("Agora não") }
        },
    )
}

/** Whether [BatteryOptimizationPrompt] still needs to be shown to this install. */
@Composable
fun rememberShouldShowBatteryOptimizationPrompt(): Boolean {
    val context = LocalContext.current
    return remember { !PushOnboardingState(context).hasSeenBatteryOptimizationPrompt() }
}
