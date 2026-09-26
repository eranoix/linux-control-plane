package com.vpsmanager.app.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vpsmanager.designsystem.ThemeMode
import com.vpsmanager.designsystem.ThemeModeSelector

/** Test tag for the settings screen. */
internal const val TAG_CONFIGURACOES = "tela-configuracoes"

/** Label of the manual update check. The UI and the test read it from here. */
internal const val CHECK_UPDATE_LABEL = "Procurar atualizações"

/** Description of the manual check icon, for screen readers. */
internal const val CHECK_UPDATE_ICON_DESCRIPTION = "Procurar atualizações do aplicativo"

/**
 * Device settings.
 *
 * ## Why it exists, and what it took out of the drawer
 *
 * The appearance and the "check for updates" lived in the drawer's footer.
 * That made sense while the drawer was a list of loose screens — the
 * footer was the only "device" place there was. With the parent pages
 * there is a right place: a setting is not a work destination, and mixing
 * it with System, Docker and Operations made the drawer answer two
 * different questions.
 *
 * Signing out STAYED in the footer, and that is deliberate: ending the
 * session has to be one tap away from any screen, without navigating
 * anywhere first.
 *
 * ## The version sits next to the button
 *
 * It is the only piece of information that makes the answer verifiable.
 * Without it, "you are already on the latest version" is a claim nobody
 * can check — and the person has just spent a tap to ask.
 */
@Composable
internal fun TelaDeConfiguracoes(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    versaoInstalada: String?,
    onProcurarAtualizacao: () -> Unit,
    onAbrirNotificacoes: () -> Unit,
    onAbrirSeguranca: () -> Unit,
    onAbrirLicencas: () -> Unit,
    onAbrirDiagnostico: () -> Unit,
    onAbrirArmazenamento: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
            .testTag(TAG_CONFIGURACOES),
    ) {
        // No section title here: ThemeModeSelector already announces itself as
        // "Appearance", and the two together put the same word on the screen
        // twice — the screen reader read the header and repeated it on the
        // selector.
        ThemeModeSelector(
            selected = themeMode,
            onSelect = onThemeModeChange,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Titulo("Aplicativo")
        LinhaDeAjuste(
            icone = Icons.Filled.Refresh,
            descricaoDoIcone = CHECK_UPDATE_ICON_DESCRIPTION,
            titulo = CHECK_UPDATE_LABEL,
            apoio = versaoInstalada?.let { "Versão $it" },
            onClick = onProcurarAtualizacao,
        )
        LinhaDeAjuste(
            icone = ICONE_NOTIFICACOES,
            descricaoDoIcone = "Notificações",
            titulo = "Notificações",
            apoio = "O que este aparelho recebe, e quando",
            onClick = onAbrirNotificacoes,
        )
        LinhaDeAjuste(
            icone = Icons.Filled.Lock,
            descricaoDoIcone = "Segurança deste aparelho",
            titulo = "Segurança deste aparelho",
            apoio = "Bloqueio do app, tela protegida, voltar sozinho",
            onClick = onAbrirSeguranca,
        )
        // It lives under "App", next to updates and notifications, because it
        // belongs to the same family: things the app does on its own and that
        // the person has a right to look at. Under "About" it would be passive
        // reading, and here there is a button that acts.
        LinhaDeAjuste(
            icone = Icons.Filled.Delete,
            descricaoDoIcone = "Armazenamento",
            titulo = "Armazenamento",
            apoio = "Quanto o app ocupa, e o que dá para devolver",
            onClick = onAbrirArmazenamento,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Titulo("Sobre")
        LinhaDeAjuste(
            icone = Icons.Filled.Warning,
            descricaoDoIcone = "Diagnóstico",
            titulo = "Diagnóstico",
            apoio = "O relatório que explica uma instalação recusada",
            onClick = onAbrirDiagnostico,
        )
        LinhaDeAjuste(
            icone = Icons.Filled.Info,
            descricaoDoIcone = "Licenças de software livre",
            titulo = "Licenças",
            apoio = null,
            onClick = onAbrirLicencas,
        )
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun LinhaDeAjuste(
    icone: ImageVector,
    descricaoDoIcone: String,
    titulo: String,
    apoio: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icone,
            contentDescription = descricaoDoIcone,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.padding(start = 16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = titulo, style = MaterialTheme.typography.bodyLarge)
            apoio?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
