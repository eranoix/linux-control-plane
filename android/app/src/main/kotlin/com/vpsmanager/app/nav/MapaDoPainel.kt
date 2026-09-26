package com.vpsmanager.app.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.vpsmanager.designsystem.VpsmIcons

/**
 * The panel's map: the parent pages and each one's children.
 *
 * ## Why it exists, and why it copies the web literally
 *
 * The drawer listed EIGHT loose screens and Administration dumped thirty
 * blocks into a single grid — two different taxonomies for the same
 * product, and neither of them matching the web panel's. The result was
 * Jira showing up in two places (a "Jira · Integrations" block in the grid
 * and a "Jira" destination in the drawer), which is the underlying
 * symptom: without a single taxonomy, every new screen picks its own home.
 *
 * The taxonomy here is the SAME as the web panel's, taken from `PAGE_REMAP`
 * (`00-shell.js`): seven parent pages — Home, System, Docker, Dev,
 * Security, Apps, Operations — plus Settings. Whoever uses both finds the
 * same thing in the same place, and whoever writes a new screen does not
 * have to invent: there is already a parent for it.
 *
 * ## Where Jira lives, and why the duplicate went away
 *
 * On the web, the Jira board is `operations/tarefas`. Here too: it is a
 * child of Operations, and points at the NATIVE screen. The SDUI section
 * `jira.issues` (the old table) stops being offered by
 * [FILHAS_SDUI_OCULTAS] — the server keeps serving it to the apps that do
 * not have the native board, and this one simply does not list it.
 */
internal enum class PaginaMae(
    val id: String,
    val titulo: String,
    val icone: ImageVector,
    val descricaoDoIcone: String,
) {
    Inicio("inicio", "Início", Icons.Filled.Home, "Início"),
    Sistema("sistema", "Sistema", VpsmIcons.Cpu, "Sistema"),
    Docker("docker", "Docker", VpsmIcons.Caixa, "Docker"),
    Dev("dev", "Dev", VpsmIcons.Terminal, "Desenvolvimento"),
    Seguranca("seguranca", "Segurança", VpsmIcons.Escudo, "Segurança"),
    Apps("apps", "Apps", VpsmIcons.Chat, "Aplicativos"),
    Operacoes("operacoes", "Operações", VpsmIcons.Quadro, "Operações"),
    Configuracoes("config", "Configurações", Icons.Filled.Settings, "Configurações"),
}

/**
 * A child: where it leads.
 *
 * [Nativa] is a screen of this app (an [AppNavHost] route); [Sdui] is a
 * section described by the server. The distinction exists in the
 * destination, never in the design — whoever looks at the grid sees
 * identical blocks, because to whoever operates it makes no difference at
 * all on which side the screen was written.
 */
internal sealed interface DestinoDaFilha {
    data class Nativa(val rota: String) : DestinoDaFilha
    data class Sdui(val sectionId: String) : DestinoDaFilha
}

/** A child page, as it appears in the parent's grid. */
internal data class PaginaFilha(
    val titulo: String,
    val icone: ImageVector,
    val destino: DestinoDaFilha,
)

/**
 * SDUI sections the app does NOT list, because it has a screen of its own
 * for them.
 *
 * `jira.issues` is the issue table the server serves to app versions older
 * than the native board. Keeping it on offer here would put two Jiras side
 * by side — a good one and a threadbare one — and the person would
 * discover the difference by trial and error.
 */
internal val FILHAS_SDUI_OCULTAS = setOf("jira.issues")

/**
 * The children of each parent, in the order they appear.
 *
 * The order within a group is the web panel's, and not alphabetical: the
 * first of each parent is its most used one, which is the one the thumb
 * reaches first.
 *
 * An SDUI child the server does not offer (by permission or by version)
 * disappears from the grid — whoever builds the screen crosses this list
 * with the real catalogue. That way a new section on the server does NOT
 * require a new app version to be reachable: it shows up under the parent
 * whose prefix it carries.
 */
internal fun filhasDe(mae: PaginaMae): List<PaginaFilha> = when (mae) {
    PaginaMae.Inicio, PaginaMae.Configuracoes -> emptyList()

    PaginaMae.Sistema -> listOf(
        PaginaFilha("Métricas", VpsmIcons.Velocimetro, DestinoDaFilha.Sdui("system.metrics")),
        PaginaFilha("Histórico", VpsmIcons.Grafico, DestinoDaFilha.Sdui("system.history")),
        PaginaFilha("Alertas", Icons.Filled.Warning, DestinoDaFilha.Sdui("alerts.rules")),
        PaginaFilha("Processos", VpsmIcons.Cpu, DestinoDaFilha.Sdui("system.processes")),
        PaginaFilha("Portas", VpsmIcons.Tomada, DestinoDaFilha.Sdui("system.ports")),
        PaginaFilha("Serviços", Icons.Filled.Build, DestinoDaFilha.Sdui("system.systemd")),
        PaginaFilha("Arquivos", VpsmIcons.Folder, DestinoDaFilha.Nativa(ROTA_ARQUIVOS)),
    )

    PaginaMae.Docker -> listOf(
        PaginaFilha("Containers", VpsmIcons.Caixa, DestinoDaFilha.Sdui("docker.containers")),
        PaginaFilha("Compose", VpsmIcons.Camadas, DestinoDaFilha.Sdui("docker.compose")),
        PaginaFilha("Imagens", VpsmIcons.Disco, DestinoDaFilha.Sdui("docker.images")),
        PaginaFilha("Volumes", VpsmIcons.Disco, DestinoDaFilha.Sdui("docker.volumes")),
        PaginaFilha("Redes", VpsmIcons.Globo, DestinoDaFilha.Sdui("docker.networks")),
        PaginaFilha("Limpeza", VpsmIcons.Troca, DestinoDaFilha.Sdui("docker.prune")),
    )

    PaginaMae.Dev -> listOf(
        PaginaFilha("Terminal", VpsmIcons.Terminal, DestinoDaFilha.Nativa(ROTA_TERMINAL)),
        PaginaFilha("Modelos de IA", Icons.Filled.Settings, DestinoDaFilha.Sdui("ai.settings")),
    )

    PaginaMae.Seguranca -> listOf(
        PaginaFilha("Auditoria", Icons.Filled.List, DestinoDaFilha.Sdui("security.audit")),
        PaginaFilha("Usuários", Icons.Filled.Person, DestinoDaFilha.Sdui("security.users")),
        PaginaFilha("Cofre", VpsmIcons.Chave, DestinoDaFilha.Sdui("security.secrets")),
        PaginaFilha("Sessões", VpsmIcons.SessaoAtiva, DestinoDaFilha.Sdui("security.sessions")),
        PaginaFilha("Firewall", VpsmIcons.Escudo, DestinoDaFilha.Sdui("security.ufw")),
        PaginaFilha("DNS", VpsmIcons.Globo, DestinoDaFilha.Sdui("security.adguard")),
        PaginaFilha("Dispositivos", VpsmIcons.Celular, DestinoDaFilha.Sdui("security.devices")),
        PaginaFilha("Uso de rede", VpsmIcons.Grafico, DestinoDaFilha.Sdui("security.economia")),
        // From the DEVICE, not the server: app lock and protected screen.
        PaginaFilha("Este aparelho", Icons.Filled.Lock, DestinoDaFilha.Nativa(ROTA_SEGURANCA)),
    )

    PaginaMae.Apps -> listOf(
        PaginaFilha("WhatsApp", VpsmIcons.Chat, DestinoDaFilha.Nativa(ROTA_WHATSAPP)),
        PaginaFilha("Chamada", VpsmIcons.Videocam, DestinoDaFilha.Nativa(ROTA_CHAMADA)),
    )

    PaginaMae.Operacoes -> listOf(
        // On the web this is called "Tasks", and it is the Jira board. The name
        // that shows is the web's; the destination is the native screen.
        PaginaFilha("Tarefas", VpsmIcons.Quadro, DestinoDaFilha.Nativa(ROTA_JIRA)),
        PaginaFilha("Fila de jobs", Icons.Filled.List, DestinoDaFilha.Sdui("queue.jobs")),
        PaginaFilha("Agendador", Icons.Filled.DateRange, DestinoDaFilha.Sdui("scheduler.jobs")),
        PaginaFilha("Deploy", VpsmIcons.Entrega, DestinoDaFilha.Sdui("deploy.apps")),
    )
}

/**
 * The parent an SDUI section belongs to, by the PREFIX of its id.
 *
 * This is what makes a new section from the server show up with no new app
 * version — the whole SDUI promise. `docker.anything_at_all` lands in
 * Docker even if this version has never heard of it; whatever matches no
 * known prefix lands in Operations, which is where the long tail of
 * administration lives.
 */
internal fun maeDaSecao(sectionId: String): PaginaMae = when (sectionId.substringBefore('.')) {
    "system", "alerts" -> PaginaMae.Sistema
    "docker" -> PaginaMae.Docker
    "security" -> PaginaMae.Seguranca
    "ai" -> PaginaMae.Dev
    else -> PaginaMae.Operacoes
}

/** Icon for a section this app does not know by name. */
internal val ICONE_DE_SECAO_DESCONHECIDA: ImageVector = Icons.Filled.Info

/** The notification icon, used on the Settings page. */
internal val ICONE_NOTIFICACOES: ImageVector = Icons.Filled.Notifications
