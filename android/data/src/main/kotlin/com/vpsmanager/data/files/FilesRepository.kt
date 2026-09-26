package com.vpsmanager.data.files

import com.vpsmanager.core.model.FileEntry
import com.vpsmanager.mobileapiclient.api.MobileApi
import com.vpsmanager.mobileapiclient.infrastructure.ClientError
import com.vpsmanager.mobileapiclient.infrastructure.ClientException
import com.vpsmanager.mobileapiclient.infrastructure.ResponseType
import com.vpsmanager.mobileapiclient.infrastructure.Serializer
import com.vpsmanager.mobileapiclient.infrastructure.ServerException
import com.vpsmanager.mobileapiclient.infrastructure.Success
import com.vpsmanager.mobileapiclient.model.FileConflictResponse
import com.vpsmanager.mobileapiclient.model.FileWriteRequest
import com.vpsmanager.mobileapiclient.model.FileWriteResponse
import java.io.IOException
import kotlinx.serialization.SerializationException
import com.vpsmanager.mobileapiclient.model.FileEntry as GeneratedFileEntry

/**
 * Outcome of listing a server directory. A plain domain shape -- the
 * generated `FileListResponse`/`FileEntry` DTOs never cross this boundary,
 * and no caller ever sees a raw exception.
 */
sealed interface FileListResult {
    data class Success(val path: String, val parent: String?, val entries: List<FileEntry>) : FileListResult
    data object Empty : FileListResult
    data class Error(val reason: String) : FileListResult
}

/**
 * Outcome of reading a file's content for the editor. Mirrors
 * [FileListResult]'s shape -- the generated `FileReadResponse` DTO never
 * crosses this boundary. [Error] carries a reason distinguishing "too large"
 * (413/`ErrTooLarge`), "binary" (415/`ErrBinary`) and "not found" (404) so
 * the editor screen never collapses those into one generic message.
 */
sealed interface FileReadResult {
    data class Success(val content: String, val mtime: Long, val language: String) : FileReadResult
    data class Error(val reason: String) : FileReadResult
}

/**
 * Outcome of writing an edited file back. [Conflict] is populated
 * directly from the BFF's 409 body (`FileConflictResponse`'s
 * `server_content`/`server_mtime`) -- no second network call is made
 * client-side just to show the admin what changed.
 *
 * [Success] carries the mtime the server reports right after the write:
 * without it, the next save in the same editing session would keep sending
 * the pre-save mtime as `expected_mtime` and immediately, spuriously
 * conflict against the write that just succeeded.
 */
sealed interface FileWriteResult {
    data class Success(val mtime: Long) : FileWriteResult
    data class Conflict(val serverContent: String, val serverMtime: Long) : FileWriteResult
    data class Error(val reason: String) : FileWriteResult
}

/**
 * Outcome of resolving the default share-target landing directory
 * (`GET /api/mobile/v1/files/inbox`) -- the destination `ShareDestinationScreen`
 * uses for its zero-tap "send to the default folder" path.
 */
sealed interface InboxDirResult {
    data class Success(val path: String) : InboxDirResult
    data class Error(val reason: String) : InboxDirResult
}

/**
 * The single call site into the generated mobile BFF client
 * (`:data:mobile-api-client`) for directory listing
 * (`GET /api/mobile/v1/files/list`). No other module may reference
 * [MobileApi] or its generated model types directly -- callers only ever
 * see [FileListResult].
 *
 * Open (class and [list]) so `:feature-files`' `FileBrowserViewModel` test
 * can substitute a fake at this seam without ever seeing [MobileApi] --
 * that type stays invisible outside `:data` because this module depends on
 * `:data:mobile-api-client` with `implementation`, not `api`.
 */
open class FilesRepository(
    private val mobileApi: MobileApi = MobileApi(),
) {
    open suspend fun list(path: String): FileListResult = try {
        val response = mobileApi.listFiles(path = path)
        val entries = response.propertyEntries.orEmpty().map { it.toDomain() }
        if (entries.isEmpty()) {
            FileListResult.Empty
        } else {
            FileListResult.Success(path = response.path, parent = response.parent, entries = entries)
        }
    } catch (e: ClientException) {
        FileListResult.Error("Não foi possível listar o diretório (erro ${e.statusCode}).")
    } catch (e: ServerException) {
        FileListResult.Error("O servidor está indisponível no momento.")
    } catch (e: IOException) {
        FileListResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
    } catch (e: IllegalStateException) {
        FileListResult.Error("Erro de configuração ao listar o diretório.")
    } catch (e: UnsupportedOperationException) {
        FileListResult.Error("Resposta inesperada do servidor.")
    } catch (e: Exception) {
        FileListResult.Error("Não foi possível listar o diretório.")
    }

    /**
     * Single call site into `GET /api/mobile/v1/files/read`. The 413/415/404
     * cases are distinguished by [ClientException.statusCode] -- never
     * flattened into one "something went wrong" message, since the editor
     * needs to tell the admin "too large to open here" apart from "this
     * isn't a text file" apart from "it's gone".
     */
    open suspend fun read(path: String): FileReadResult = try {
        val response = mobileApi.readFile(path = path)
        FileReadResult.Success(content = response.content, mtime = response.mtime, language = response.language)
    } catch (e: ClientException) {
        FileReadResult.Error(
            when (e.statusCode) {
                413 -> "Arquivo grande demais para abrir no celular (limite de 2 MB)."
                415 -> "Este arquivo não é texto -- não é possível exibi-lo no editor."
                404 -> "Arquivo não encontrado."
                else -> "Não foi possível abrir o arquivo (erro ${e.statusCode})."
            },
        )
    } catch (e: ServerException) {
        FileReadResult.Error("O servidor está indisponível no momento.")
    } catch (e: IOException) {
        FileReadResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
    } catch (e: IllegalStateException) {
        FileReadResult.Error("Erro de configuração ao abrir o arquivo.")
    } catch (e: UnsupportedOperationException) {
        FileReadResult.Error("Resposta inesperada do servidor.")
    } catch (e: Exception) {
        FileReadResult.Error("Não foi possível abrir o arquivo.")
    }

    /**
     * Single call site into `POST /api/mobile/v1/files/write`. Calls
     * `writeFileWithHttpInfo` (not the throwing `writeFile` convenience
     * method) specifically to reach the 409's raw response body -- that's
     * where `server_content`/`server_mtime` live, and there is no other way
     * to reach them through the generated client's throwing surface.
     * `expectedMtime == 0L` is sent as an absent field (matches the BFF's
     * "no prior read, write unconditionally" semantics for brand-new files).
     */
    open suspend fun write(path: String, content: String, expectedMtime: Long): FileWriteResult = try {
        val request = FileWriteRequest(
            path = path,
            content = content,
            expectedMtime = expectedMtime.takeIf { it != 0L },
        )
        val response = mobileApi.writeFileWithHttpInfo(fileWriteRequest = request)
        when (response.responseType) {
            ResponseType.Success -> {
                val body = (response as Success<*>).data as? FileWriteResponse
                FileWriteResult.Success(mtime = body?.mtime ?: expectedMtime)
            }
            ResponseType.ClientError -> {
                val err = response as ClientError<*>
                if (err.statusCode == 409) {
                    parseConflict(err.body as? String)
                        ?: FileWriteResult.Error(
                            "O arquivo mudou no servidor, mas não foi possível ler o conteúdo atual.",
                        )
                } else {
                    FileWriteResult.Error("Não foi possível salvar o arquivo (erro ${err.statusCode}).")
                }
            }
            ResponseType.ServerError -> FileWriteResult.Error("O servidor está indisponível no momento.")
            else -> FileWriteResult.Error("Resposta inesperada do servidor.")
        }
    } catch (e: IOException) {
        FileWriteResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
    } catch (e: IllegalStateException) {
        FileWriteResult.Error("Erro de configuração ao salvar o arquivo.")
    } catch (e: Exception) {
        FileWriteResult.Error("Não foi possível salvar o arquivo.")
    }

    /**
     * Single call site into `GET /api/mobile/v1/files/inbox`. Mirrors the
     * shape every other method on this class uses -- no generated DTO
     * (`InboxResponse`) ever crosses this boundary, only [InboxDirResult].
     */
    open suspend fun inboxPath(): InboxDirResult = try {
        InboxDirResult.Success(path = mobileApi.filesInbox().path)
    } catch (e: ClientException) {
        InboxDirResult.Error("Não foi possível obter a pasta padrão (erro ${e.statusCode}).")
    } catch (e: ServerException) {
        InboxDirResult.Error("O servidor está indisponível no momento.")
    } catch (e: IOException) {
        InboxDirResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
    } catch (e: IllegalStateException) {
        InboxDirResult.Error("Erro de configuração ao obter a pasta padrão.")
    } catch (e: UnsupportedOperationException) {
        InboxDirResult.Error("Resposta inesperada do servidor.")
    } catch (e: Exception) {
        InboxDirResult.Error("Não foi possível obter a pasta padrão.")
    }
}

/**
 * Decodes the raw 409 body into [FileWriteResult.Conflict] using the same
 * `Json` instance the generated client itself uses for every other response
 * -- never a second, ad hoc JSON configuration.
 */
private fun parseConflict(rawBody: String?): FileWriteResult.Conflict? {
    if (rawBody.isNullOrBlank()) return null
    return try {
        val body = Serializer.kotlinxSerializationJson.decodeFromString(FileConflictResponse.serializer(), rawBody)
        FileWriteResult.Conflict(serverContent = body.serverContent, serverMtime = body.serverMtime)
    } catch (e: SerializationException) {
        null
    }
}

private fun GeneratedFileEntry.toDomain() = FileEntry(
    name = name,
    size = propertySize,
    isDir = isDir,
    modifiedEpochSeconds = modified,
)
