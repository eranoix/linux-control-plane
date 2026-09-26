package com.vpsmanager.data.sdui

import com.vpsmanager.mobileapiclient.infrastructure.ClientException
import com.vpsmanager.mobileapiclient.infrastructure.RequestMethod
import com.vpsmanager.mobileapiclient.infrastructure.ServerException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * A section the server offers to the authenticated user.
 *
 * The app NEVER keeps its own list of sections: this list arrives whole from
 * `GET /api/mobile/v1/screens`, and that is the point of SDUI — a new screen
 * registered on the server appears on the phone with no release. A constant of
 * sections on the client would recreate exactly the coupling the architecture
 * exists to eliminate.
 *
 * [group] is the header the item appears under (Docker, System, Security…) and
 * [label] is the display text ready to draw — both come from the server, so
 * renaming a section needs no release either.
 */
data class SduiSection(
    val id: String,
    val group: String,
    val label: String,
)

/**
 * The result of `GET /screens`. There is no `Forbidden` variant: the catalogue
 * is filtered by OMISSION on the server, so a user with no permissions at all
 * gets a 200 with an empty list — never a 403. The only possible failure is
 * technical.
 */
sealed interface SduiSectionsResult {
    data class Success(val sections: List<SduiSection>) : SduiSectionsResult

    data class Error(val reason: String) : SduiSectionsResult
}

/**
 * A narrow seam for the catalogue, in the same spirit as [SduiScreenPort]: the
 * screen host declares only what it consumes, and a feature test fakes it here
 * instead of standing up an HTTP server inside `feature/` — which the gate
 * (`scripts/check-mobile-bff-only.sh`) forbids outside `:data`.
 */
fun interface SduiCatalogPort {
    suspend fun sections(): SduiSectionsResult
}

/**
 * Fetches the section catalogue through the same [SduiDataClient] that
 * descriptors, rows and actions already use — no new network path.
 *
 * Why it does not use the GENERATED `SduiApi.listScreens()`: the generated
 * client decodes bodies by reflection into generated types, and the rest of
 * this module's SDUI surface already goes through [SduiDataClient.call]'s raw
 * [JsonElement] path (see [SduiRepository]'s KDoc on `getScreen()` decoding
 * into `kotlin.Any`). Keeping all four SDUI calls on the SAME path is worth
 * more than saving this parser: one of them diverging in error handling is how
 * a 404 becomes a "scary error" instead of a normal state.
 *
 * TOLERANCE OF UNKNOWN FIELDS: an entry with no `id` or no `label` is SKIPPED,
 * not fatal, and extra fields are ignored. The server is authoritative about
 * what exists; the client is tolerant of what it does not yet know — the same
 * asymmetry the component vocabulary already adopts. A newer server that adds a
 * field must never blind an older app.
 */
class SduiCatalogRepository(
    private val client: SduiDataClient = SduiDataClient(),
) : SduiCatalogPort {

    override suspend fun sections(): SduiSectionsResult =
        try {
            val body: JsonElement = client.call("/api/mobile/v1/screens", RequestMethod.GET)
                ?: return SduiSectionsResult.Error("Resposta vazia do servidor.")
            SduiSectionsResult.Success(parseSections(body))
        } catch (e: ClientException) {
            when (e.statusCode) {
                // A 401 is an expired session, handled higher up the auth
                // stack; here it only needs not to become a cryptic message.
                401 -> SduiSectionsResult.Error("Sua sessão expirou. Entre novamente.")
                // A 404 on the CATALOGUE (not on a section) means an old
                // server without the endpoint — not the user's fault and not
                // their error.
                404 -> SduiSectionsResult.Error("Este servidor ainda não oferece a lista de seções.")
                else -> SduiSectionsResult.Error("Não foi possível carregar as seções (erro ${e.statusCode}).")
            }
        } catch (e: ServerException) {
            SduiSectionsResult.Error("O servidor está indisponível no momento.")
        } catch (e: IOException) {
            SduiSectionsResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
        } catch (e: IllegalStateException) {
            SduiSectionsResult.Error("Erro de configuração ao carregar as seções.")
        } catch (e: UnsupportedOperationException) {
            SduiSectionsResult.Error("Resposta inesperada do servidor.")
        } catch (e: SerializationException) {
            SduiSectionsResult.Error("Não foi possível interpretar a lista de seções.")
        }

    /**
     * Reads `{"sections":[{"id","group","label"}, ...]}` preserving the
     * SERVER's order — it already arrives grouped and sorted, and reordering
     * here would have the app disagree with the server about the shape of the
     * list.
     */
    private fun parseSections(body: JsonElement): List<SduiSection> {
        val array = body.jsonObject["sections"]?.jsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"].textoOuNulo() ?: return@mapNotNull null
            val label = obj["label"].textoOuNulo() ?: return@mapNotNull null
            SduiSection(
                id = id,
                // A missing group falls back to a neutral label rather than
                // dropping the entry: losing the header is cosmetic, losing the
                // section would make the screen unreachable.
                group = obj["group"].textoOuNulo() ?: "Outros",
                label = label,
            )
        }
    }
}

/**
 * The text of a JSON field, or null when it is absent, is `null`, is not a
 * primitive (an object or array where text was expected), or is only
 * whitespace — the four shapes that serve as neither id nor label.
 *
 * It checks [JsonNull] explicitly because `JsonPrimitive.content` of a JSON
 * null returns the STRING "null", which would pass any emptiness test and
 * become a section called "null" in the list.
 */
private fun JsonElement?.textoOuNulo(): String? {
    val primitivo = this as? JsonPrimitive ?: return null
    if (primitivo is JsonNull) return null
    return primitivo.content.takeIf { it.isNotBlank() }
}
