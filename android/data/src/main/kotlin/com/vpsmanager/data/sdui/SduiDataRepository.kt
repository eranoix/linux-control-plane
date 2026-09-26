package com.vpsmanager.data.sdui

import com.vpsmanager.core.sdui.SduiDataSource
import com.vpsmanager.mobileapiclient.infrastructure.ClientException
import com.vpsmanager.mobileapiclient.infrastructure.RequestMethod
import com.vpsmanager.mobileapiclient.infrastructure.ServerException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.IOException

/**
 * Outcome of resolving one SDUI component's `rows_source`/`data_source`/
 * `series_source`. A plain domain shape — no caller ever sees a raw
 * [JsonElement] parse exception or an [okhttp3.OkHttpClient] type.
 */
sealed interface SduiDataResult {
    data class Success(val body: JsonElement) : SduiDataResult
    data object Empty : SduiDataResult
    data class Error(val reason: String) : SduiDataResult
}

/** The only path outside the BFF mobile/v1 tree this repository will ever call. */
private const val ALLOWED_ENDPOINT_PREFIX = "/api/mobile/v1/"

/**
 * The single call site into [SduiDataClient] for an [SduiDataSource]
 * descriptor. [dataSource].endpoint is used verbatim — this repository never
 * appends, rewrites or templates it — except for the boundary check below.
 *
 * A descriptor is server-authored (RBAC-filtered, trusted at parse
 * time), but this is still the one place a tampered or malformed descriptor
 * would surface, so an endpoint outside the BFF mobile namespace is refused
 * before any network call is attempted — the same boundary
 * `android/build-logic`'s `BffOnlyNetworkPlugin` already enforces on this
 * module's own source literals, applied here to a runtime value instead.
 */
class SduiDataRepository(
    private val client: SduiDataClient = SduiDataClient(),
) {
    suspend fun fetch(dataSource: SduiDataSource): SduiDataResult {
        val endpoint = dataSource.endpoint
        if (!endpoint.startsWith(ALLOWED_ENDPOINT_PREFIX)) {
            return SduiDataResult.Error("Endpoint fora do namespace do BFF mobile: $endpoint")
        }
        val method = when (dataSource.method?.uppercase()) {
            null, "GET" -> RequestMethod.GET
            "POST" -> RequestMethod.POST
            "PUT" -> RequestMethod.PUT
            "PATCH" -> RequestMethod.PATCH
            "DELETE" -> RequestMethod.DELETE
            else -> return SduiDataResult.Error("Método HTTP não suportado: ${dataSource.method}")
        }
        return try {
            val body = client.call(endpoint, method)
            if (body == null || body is JsonNull) {
                SduiDataResult.Empty
            } else {
                SduiDataResult.Success(body)
            }
        } catch (e: ClientException) {
            SduiDataResult.Error("Não foi possível carregar os dados (erro ${e.statusCode}).")
        } catch (e: ServerException) {
            SduiDataResult.Error("O servidor está indisponível no momento.")
        } catch (e: IOException) {
            SduiDataResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
        } catch (e: IllegalStateException) {
            SduiDataResult.Error("Erro de configuração ao carregar os dados.")
        } catch (e: UnsupportedOperationException) {
            SduiDataResult.Error("Resposta inesperada do servidor.")
        } catch (e: Exception) {
            SduiDataResult.Error("Não foi possível carregar os dados.")
        }
    }
}
