package com.vpsmanager.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vpsmanager.data.config.ConfigureServerResult
import com.vpsmanager.data.config.ServerConfigRepository

/**
 * First-run gate: nothing else in the app can reach a real server until a
 * base URL is configured here. This is now the FALLBACK path — the primary
 * first-run flow is [PasskeyRegisterFlow], whose scanned QR already carries
 * the server address (`PairingPayload.serverUrl`), so pairing normally never
 * requires typing a hostname. This screen stays reachable via
 * `onManualSetupRequested` for a device that cannot scan (no camera, no
 * admin physically present with the panel) or a manual override for local
 * development.
 */
@Composable
fun ServerSetupScreen(
    serverConfigRepository: ServerConfigRepository,
    modifier: Modifier = Modifier,
    onConfigured: () -> Unit,
) {
    var rawUrl by remember { mutableStateOf("") }
    var allowInsecureHttp by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp).widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Configurar servidor", style = MaterialTheme.typography.titleLarge)
            Text(text = "Informe o endereço do seu VPS Manager para continuar.")
            OutlinedTextField(
                value = rawUrl,
                onValueChange = {
                    rawUrl = it
                    errorMessage = null
                },
                label = { Text("https://seu-servidor.exemplo.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowInsecureHttp, onCheckedChange = { allowInsecureHttp = it })
                Text(text = "Permitir http:// (somente desenvolvimento local)")
            }
            errorMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    when (
                        val result = serverConfigRepository.configure(
                            rawUrl = rawUrl,
                            allowInsecureHttp = allowInsecureHttp,
                            allowRepoint = false,
                        )
                    ) {
                        is ConfigureServerResult.Applied -> onConfigured()
                        is ConfigureServerResult.Rejected -> errorMessage = result.reason
                        is ConfigureServerResult.RepointBlocked -> errorMessage =
                            "Este app já está configurado para ${result.currentBaseUrl}. " +
                                "Remova a configuração atual antes de trocar de servidor."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Continuar")
            }
        }
    }
}
