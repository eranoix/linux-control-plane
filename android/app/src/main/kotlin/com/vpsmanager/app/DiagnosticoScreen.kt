package com.vpsmanager.app

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * First-boot diagnostic screen.
 *
 * This app was built with no device available and the operator has only
 * the phone — no `adb`, no logcat. When something breaks during startup,
 * this screen is the only channel through which the information leaves
 * the device. That is why it shows the whole stack trace, selectable,
 * instead of "an error occurred".
 *
 * [falhasDeAtualizacao] brings the same channel to the other moment when
 * the app fails with no way to explain itself: installing its own update.
 * `PackageInstaller`'s `EXTRA_STATUS_MESSAGE` ("signature does not match",
 * "version downgrade", "blocked by device policy") is the only sentence
 * that tells those cases apart, and it does not fit in the banner at the
 * top — which is why the banner sends you here. Unlike the other two
 * sections, this one is reachable WITH the app working (route
 * `diagnostico`), because the owner needs it precisely when the app is up
 * and only the update failed.
 */
@Composable
fun DiagnosticoScreen(
    falhasDeInit: List<String>,
    ultimoCrash: String?,
    onLimpar: () -> Unit,
    falhasDeAtualizacao: String? = null,
    // Reached through the `diagnostico` route the app is ALREADY open, and
    // "try opening the app" there would be a meaningless sentence.
    rotuloLimpar: String = "Limpar e tentar abrir o app",
) {
    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Diagnóstico de inicialização",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "O app subiu, mas algo falhou. Mande este texto para a sessão " +
                    "que está construindo o app — ele diz exatamente o que quebrou.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            ProcedenciaDaInstalacao()

            if (falhasDeInit.isNotEmpty()) {
                Text("Etapas que falharam", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Card {
                    Column(Modifier.padding(12.dp)) {
                        falhasDeInit.forEach { falha ->
                            Text(
                                "• $falha",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (falhasDeAtualizacao != null) {
                Text("Atualização do app", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Card {
                    Text(
                        falhasDeAtualizacao,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (ultimoCrash != null) {
                Text("Crash do processo anterior", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Card {
                    Text(
                        ultimoCrash,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (falhasDeInit.isEmpty() && ultimoCrash == null && falhasDeAtualizacao == null) {
                Text(
                    "Nada registrado — nenhuma etapa falhou, o processo anterior " +
                        "terminou normalmente e a atualização não reportou erro.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
            }

            Button(onClick = onLimpar) {
                Text(rotuloLimpar)
            }
        }
    }
}

/**
 * Who Android thinks installed this app.
 *
 * ## Why this became a screen
 *
 * Updating without the system dialog — the only path Samsung's Auto
 * Blocker does not interrupt — requires Android to KNOW who installed the
 * app. An app whose provenance is null is refused with
 * `Self update is blocked by unknown source package`, and the code falls
 * back to the dialog. That is where Auto Blocker cuts in.
 *
 * That state decided the behaviour and showed up nowhere: I had been
 * inferring it from the symptom, and got it wrong twice in a row because
 * of that. Now it is readable on the device, by whoever is holding it.
 */
@Composable
private fun ProcedenciaDaInstalacao() {
    val contexto = LocalContext.current
    val instalador = remember(contexto) {
        runCatching {
            contexto.packageManager
                .getInstallSourceInfo(contexto.packageName)
                .installingPackageName
        }.getOrNull()
    }
    val ehOProprioApp = instalador == contexto.packageName

    Text("Origem desta instalação", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = instalador ?: "(nenhuma — instalado por um APK aberto à mão)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    ehOProprioApp ->
                        "A atualização pode acontecer sem o diálogo do sistema — que é " +
                            "onde o Bloqueador automático da Samsung interrompe."
                    instalador != null ->
                        "Quem instalou foi outro aplicativo. A atualização passa pelo " +
                            "diálogo do sistema."
                    else ->
                        "Sem origem conhecida, o Android RECUSA a atualização silenciosa " +
                            "e ela passa pelo diálogo — que o Bloqueador automático " +
                            "interrompe. Para sair deste estado é preciso deixar UMA " +
                            "atualização concluir pelo botão de dentro do app: a partir " +
                            "dela, quem consta como origem é o próprio aplicativo."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
}
