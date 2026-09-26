package com.vpsmanager.feature.terminal.power

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/** Label of the item that opens the explanation, in the terminal's options sheet. */
const val ISENCAO_BATERIA_LABEL = "Manter a sessão ao trocar de aplicativo"

/** Test tag for the sheet item. */
const val ISENCAO_BATERIA_TAG = "botao-isencao-bateria"

/** Test tag for the dialog that explains before the system asks. */
const val ISENCAO_BATERIA_DIALOGO_TAG = "dialogo-isencao-bateria"

/**
 * The text the operator reads BEFORE the system asks anything.
 *
 * It is deliberately honest about its reach. The exemption does NOT keep the
 * connection alive indefinitely: measured on an Android 16 emulator, it takes
 * background survival from about 6 s to about 70 s, and past those 70 s what
 * kills it is the cached-app freezer, which the exemption does not turn off.
 * Promising "keeps your connection alive" would be selling what Android does
 * not deliver — and the person would discover the lie over their first
 * ten-minute coffee.
 */
const val ISENCAO_BATERIA_EXPLICACAO: String =
    "Quando você sai do aplicativo, o Android corta a rede dele em poucos segundos e a " +
        "sessão do terminal cai — por isso ela reconecta toda vez que você volta.\n\n" +
        "Liberando este app da otimização de bateria, ele passa a aguentar cerca de um " +
        "minuto fora da tela em vez de alguns segundos: trocar de aplicativo, ler uma " +
        "mensagem e voltar deixa de custar reconexão.\n\n" +
        "Não é para sempre: depois de aproximadamente um minuto o Android congela o " +
        "aplicativo de qualquer jeito e a conexão cai — aí ela volta sozinha, e a sessão " +
        "continua intacta no servidor, sem perder nada do que estava rodando."

/** Already exempt? Asks the system, never a cached guess. */
fun isentoDeOtimizacaoDeBateria(context: Context): Boolean {
    val power = context.getSystemService<PowerManager>() ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Opens the SYSTEM's request (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
 *
 * It should only be called after [ISENCAO_BATERIA_EXPLICACAO] has been read: a
 * system dialog with no context is denied by reflex, and once denied it is
 * never offered again on its own.
 *
 * Falls back to the list screen
 * (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) when the direct request does
 * not exist on the device — some OEMs remove the direct action. Returns
 * `false` when neither opened, so the caller can say something instead of
 * flickering to no effect.
 */
fun abrirPedidoDeIsencaoDeBateria(context: Context): Boolean {
    val pedidoDireto = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    if (iniciar(context, pedidoDireto)) return true
    return iniciar(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun iniciar(context: Context, intent: Intent): Boolean {
    // FLAG_ACTIVITY_NEW_TASK only when the context is not an Activity: inside
    // an Activity the flag is unnecessary and gets in the way of coming back
    // to the screen.
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}
