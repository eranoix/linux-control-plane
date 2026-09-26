package com.vpsmanager.data.events

import com.vpsmanager.mobileapiclient.api.MobileApi
import com.vpsmanager.mobileapiclient.infrastructure.ClientException
import com.vpsmanager.mobileapiclient.infrastructure.ServerException
import java.io.IOException

/** Outcome of `POST /api/mobile/v1/events/ws-ticket`. Never `Empty` — a successful call always returns a usable one-shot ticket. */
sealed interface WsTicketResult {
    data class Success(val ticket: String, val expiresIn: Int) : WsTicketResult
    data class Error(val reason: String) : WsTicketResult
}

/**
 * The narrow slice of [MobileEventsRepository] that [MobileEventsSocket] depends on: a fresh,
 * never-reused ticket for every connect/reconnect attempt. Kept as its own interface (rather
 * than a direct dependency on the concrete [MobileEventsRepository]) purely so tests can supply
 * a fake without touching the generated client or the network — production code always passes a
 * real [MobileEventsRepository]. Mirrors `com.vpsmanager.data.terminal.TerminalTicketSource`'s
 * exact shape, minus the per-session `name` parameter: `/ws/mobile-events` is one stream per
 * user, not per session.
 */
interface MobileEventsTicketSource {
    suspend fun wsTicket(): WsTicketResult
}

/**
 * The single call site into the generated mobile BFF client (`:data:mobile-api-client`) for
 * `/ws/mobile-events`'s ticket issuance — mirrors
 * `com.vpsmanager.data.terminal.TerminalRepository`'s exact shape. No other module may reference
 * [MobileApi] directly for this endpoint; callers only ever see [WsTicketResult].
 */
class MobileEventsRepository(
    private val mobileApi: MobileApi = MobileApi(),
) : MobileEventsTicketSource {

    override suspend fun wsTicket(): WsTicketResult = try {
        val response = mobileApi.issueMobileEventsWSTicket()
        WsTicketResult.Success(ticket = response.ticket, expiresIn = response.expiresIn.toInt())
    } catch (e: ClientException) {
        WsTicketResult.Error("Não foi possível conectar ao stream de eventos (erro ${e.statusCode}).")
    } catch (e: ServerException) {
        WsTicketResult.Error("O servidor está indisponível no momento.")
    } catch (e: IOException) {
        WsTicketResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
    } catch (e: IllegalStateException) {
        WsTicketResult.Error("Erro de configuração ao conectar ao stream de eventos.")
    } catch (e: UnsupportedOperationException) {
        WsTicketResult.Error("Resposta inesperada do servidor.")
    } catch (e: Exception) {
        WsTicketResult.Error("Não foi possível conectar ao stream de eventos.")
    }
}
