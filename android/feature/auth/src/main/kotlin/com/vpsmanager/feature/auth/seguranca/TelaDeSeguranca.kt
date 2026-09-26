package com.vpsmanager.feature.auth.seguranca

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpsmanager.data.seguranca.PreferenciasDeSeguranca
import kotlinx.coroutines.launch

/**
 * The defences that depend on this device.
 *
 * Every switch carries the REASON with it, not just the name. A security screen
 * whose items say only "Lock the app" and "Protect the screen" forces people to
 * guess what they are being protected against — and, in doubt, nobody turns
 * anything on. What settles it is knowing what you lose by leaving it off.
 */
@Composable
fun TelaDeSeguranca(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val prefs = remember(contexto) { PreferenciasDeSeguranca(contexto.applicationContext) }
    val escopo = rememberCoroutineScope()

    val bloqueio by prefs.bloqueioAoAbrir.collectAsStateWithLifecycle(initialValue = false)
    val captura by prefs.protegerContraCaptura.collectAsStateWithLifecycle(initialValue = false)

    // A switch that does nothing when turned on is worse than no switch at
    // all: the person comes to believe they are protected. On a device with
    // neither biometrics nor a PIN, the lock cannot be enforced — so it appears
    // disabled, with the reason stated.
    val atividade = contexto as? FragmentActivity
    val bloqueioDisponivel = remember(atividade) {
        atividade != null && BloqueioDoApp.disponivel(atividade)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ItemDeSeguranca(
            titulo = "Pedir desbloqueio ao abrir",
            explicacao = if (bloqueioDisponivel) {
                "Exige a sua biometria ou o PIN do aparelho toda vez que o aplicativo " +
                    "volta para a frente. Sem isso, quem estiver com o celular " +
                    "desbloqueado na mão tem o mesmo acesso ao servidor que você."
            } else {
                "Indisponível: este aparelho não tem biometria nem PIN configurados."
            },
            ligado = bloqueio,
            habilitado = bloqueioDisponivel,
            aoMudar = { valor -> escopo.launch { prefs.definirBloqueioAoAbrir(valor) } },
        )

        ItemDeSeguranca(
            titulo = "Bloquear captura de tela",
            explicacao = "Impede captura, gravação de tela e a miniatura que aparece na " +
                "lista de aplicativos recentes — essa miniatura é gravada em disco pelo " +
                "Android e mostra a última tela, que aqui costuma ser o terminal. " +
                "Em troca, as suas próprias capturas de tela deste aplicativo saem pretas.",
            ligado = captura,
            habilitado = true,
            aoMudar = { valor -> escopo.launch { prefs.definirProtecaoContraCaptura(valor) } },
        )

        VoltarSozinhoDepoisDeAtualizar()

        Text(
            // What is already protected, said once, so the screen does not
            // read as a list of everything that is missing.
            text = "O seu acesso já é guardado cifrado pelo cofre do aparelho (Android " +
                "Keystore) e nunca entra em backup. As opções acima tratam de quem " +
                "está com o aparelho na mão.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ItemDeSeguranca(
    titulo: String,
    explicacao: String,
    ligado: Boolean,
    habilitado: Boolean,
    aoMudar: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = titulo, style = MaterialTheme.typography.titleMedium)
            Text(
                text = explicacao,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Material's Switch already guarantees the minimum touch target and is
        // already announced with its state by the screen reader; the label comes
        // from the text beside it, being on the same semantic row.
        Switch(checked = ligado, onCheckedChange = aoMudar, enabled = habilitado)
    }
}

/**
 * "Display over other apps", asked for by the REAL reason we ask for it.
 *
 * ## Why this row exists
 *
 * Updating kills the application process, and reopening our own screen from a
 * receiver is a "background Activity start" — which Android blocks. **Measured**:
 * neither a direct `startActivity` nor a `PendingIntent` with the two opt-ins
 * that `targetSdk` 36 requires gets through. The only exception on the official
 * list that an ordinary application can reach is this permission.
 *
 * ## Why it is a switch, and not a requirement
 *
 * Without it the application **works just the same**: after an update, a
 * one-tap notification leads to the same screen. With it, the return is
 * automatic. The permission trades **one tap for none** — and "Display over
 * other apps" is too sensitive to be demanded in exchange for that without the
 * person knowing what they are trading.
 *
 * ## Why it opens Settings instead of asking
 *
 * There is no question to ask: this is a special permission, with no runtime
 * dialog. The only route is the system screen, and that is where the button
 * leads.
 */
@Composable
private fun VoltarSozinhoDepoisDeAtualizar() {
    val contexto = LocalContext.current
    val ciclo = LocalLifecycleOwner.current

    // Re-read on every return to this screen: the grant happens OUTSIDE the
    // application, in Android's Settings, and without re-reading the state the
    // row would go on saying "off" after the person had turned it on.
    var concedida by remember { mutableStateOf(Settings.canDrawOverlays(contexto)) }
    DisposableEffect(ciclo) {
        val observador = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                concedida = Settings.canDrawOverlays(contexto)
            }
        }
        ciclo.lifecycle.addObserver(observador)
        onDispose { ciclo.lifecycle.removeObserver(observador) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Voltar sozinho depois de atualizar",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (concedida) {
                "Ativado. Depois de uma atualização o aplicativo reabre sozinho, na " +
                    "tela em que você estava."
            } else {
                "Hoje, depois de atualizar, aparece uma notificação e você toca para " +
                    "voltar. Para o aplicativo reabrir sozinho, o Android exige a " +
                    "permissão \"Exibir sobre outros apps\" — é a única forma que ele " +
                    "deixa. Sem ela nada deixa de funcionar: só continua sendo um toque."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!concedida) {
            TextButton(
                onClick = {
                    // The system screen, already filtered to this application.
                    // Without the `package:`, it opens the list of ALL
                    // applications and the person has to hunt for ours in the
                    // middle of it.
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${contexto.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { contexto.startActivity(intent) }
                },
            ) {
                Text("Abrir os Ajustes do Android")
            }
        }
    }
}
