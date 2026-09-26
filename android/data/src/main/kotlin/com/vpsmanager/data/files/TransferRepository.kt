package com.vpsmanager.data.files

import com.vpsmanager.mobileapiclient.api.MobileApi
import com.vpsmanager.mobileapiclient.infrastructure.ClientError
import com.vpsmanager.mobileapiclient.infrastructure.ClientException
import com.vpsmanager.mobileapiclient.infrastructure.ResponseType
import com.vpsmanager.mobileapiclient.infrastructure.Serializer
import com.vpsmanager.mobileapiclient.infrastructure.ServerException
import com.vpsmanager.mobileapiclient.infrastructure.Success
import com.vpsmanager.mobileapiclient.model.UploadCompleteRequest
import com.vpsmanager.mobileapiclient.model.UploadCompleteResponse
import com.vpsmanager.mobileapiclient.model.UploadIncompleteResponse
import com.vpsmanager.mobileapiclient.model.UploadInitRequest
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Outcome of streaming a chunk of a file download. Each [Bytes]
 * carries one piece of the response body as it arrives over the wire -- the
 * whole point of streaming instead of buffering the full file in memory
 * before returning. The final chunk of a successful download is a [Bytes]
 * with [Bytes.isLast] `true` (its `data` may be empty -- it exists purely as
 * an unambiguous "the stream is done" signal, distinct from a network drop
 * that simply stops emitting).
 */
sealed interface DownloadChunkResult {
    data class Bytes(val data: ByteArray, val isLast: Boolean) : DownloadChunkResult
    data class Error(val reason: String) : DownloadChunkResult
}

/** Outcome of starting a chunked upload session (`POST /files/upload/init`). */
sealed interface UploadSessionResult {
    data class Started(val sessionId: String) : UploadSessionResult
    data class Error(val reason: String, val retryable: Boolean = true) : UploadSessionResult
}

/**
 * Outcome of `POST /files/upload/chunk`.
 *
 * It used to be a `Boolean`, and the `false` conflated two worlds that demand
 * opposite reactions: "the network dropped, try again in a moment" and "the
 * server's disk is full / the file is over the cap — trying again will never
 * work". Without that distinction the app only knew how to repeat, and the
 * operator watched an upload spin forever without ever learning what needed
 * fixing.
 */
sealed interface UploadChunkResult {
    data object Accepted : UploadChunkResult
    data class Rejected(val reason: String, val retryable: Boolean = true) : UploadChunkResult
}

/**
 * Outcome of finalizing a chunked upload (`POST /files/upload/complete`).
 * [Incomplete] is populated directly from the BFF's 409 body -- distinct
 * from [Error] so a caller can resume from [Incomplete.receivedBytes]
 * instead of treating a partially-arrived upload as a hard failure.
 */
sealed interface UploadCompleteResult {
    data class Success(val path: String) : UploadCompleteResult
    data class Incomplete(val receivedBytes: Long, val totalSize: Long) : UploadCompleteResult
    data class Error(val reason: String, val retryable: Boolean = true) : UploadCompleteResult
}

/**
 * The translation — in ONE place — from an HTTP status on the transfer BFF into
 * the sentence the operator reads and the "is it worth retrying?" signal the
 * upload engine obeys.
 *
 * The rule guiding each sentence: **say what to do**, not just what happened.
 * "error 507" is not actionable; "the server is out of disk space — free some
 * space and send again" is. And `retryable` is not cosmetic: get it wrong and
 * an over-sized file would repeat in the background forever, while a network
 * drop would give up on the first attempt.
 */
internal fun transferErrorFor(statusCode: Int): Pair<String, Boolean> = when (statusCode) {
    401, 403 -> "Sem permissão para gravar na pasta de destino no servidor. Escolha outra pasta." to false
    404 -> "A sessão de envio não existe mais no servidor. O envio recomeça do zero." to true
    413 -> "O arquivo passa do limite de 2 GB por envio. Compacte ou divida o arquivo." to false
    // 507 Insufficient Storage — the server maps ENOSPC here; without it a
    // full disk arrived as "invalid request" and nobody knew why.
    507 -> "O servidor está sem espaço em disco. Libere espaço e envie de novo." to false
    in 500..599 -> "O servidor está indisponível no momento. O envio continua quando ele voltar." to true
    in 400..499 -> "O servidor recusou o envio (erro $statusCode)." to false
    else -> "Não foi possível enviar o arquivo (erro $statusCode)." to false
}

/**
 * The single call site into the generated mobile BFF client for the
 * download (`GET /files/download`) and chunked-upload-session
 * (`POST /files/upload/{init,chunk,complete}`) endpoints.
 * Mirrors [FilesRepository]'s shape -- sealed results, no generated-DTO
 * leakage to callers.
 *
 * [chunkStagingDir] is where each outgoing upload chunk is briefly
 * materialized as a file before handing it to the generated client (whose
 * `uploadChunk` operation is typed to take a `java.io.File`, not a raw byte
 * array -- an artifact of how the multipart/binary-body operation was
 * generated). Production callers (the transfer workers, which have an
 * Android `Context`) pass `context.cacheDir`; the default here is a plain
 * testability convenience so this class stays constructible without a
 * `Context` in `:data`'s unit tests.
 */
open class TransferRepository(
    private val mobileApi: MobileApi = MobileApi(),
    private val chunkStagingDir: File = File(System.getProperty("java.io.tmpdir") ?: "."),
) {

    /**
     * Issues the Range-aware `GET /files/download` request starting at
     * [fromByte] and streams the response body as it arrives, never
     * materializing the whole file in memory -- the reason a single
     * buffered request was rejected for this endpoint (large files over a
     * variable phone connection).
     *
     * The generated client's `downloadFile`/`downloadFileWithHttpInfo`
     * methods return `Unit` (the operation was generated with no typed
     * response body, since a raw byte stream isn't expressible through the
     * generated DTO layer) and offer no way to set a request-specific
     * `Range` header. This method therefore builds the request directly
     * against [MobileApi]'s own `baseUrl`/`client` (its public
     * `ApiClient`-inherited fields, so it reuses the exact same connection
     * pool, base URL and any interceptors already configured on that
     * `Call.Factory`) and reads the raw `okhttp3.Response` body stream.
     * This is a deliberate, narrow escape hatch: no generated DTO is
     * leaked to callers of this method -- only a raw byte stream, which is
     * the correct primitive for a resumable binary download and cannot be
     * expressed any other way through the generated layer.
     */
    open fun downloadRange(path: String, fromByte: Long): Flow<DownloadChunkResult> = flow {
        val requestConfig = mobileApi.downloadFileRequestConfig(path = path)
        val httpUrl = mobileApi.baseUrl.toHttpUrlOrNull()
        if (httpUrl == null) {
            emit(DownloadChunkResult.Error("Erro de configuração ao baixar o arquivo."))
            return@flow
        }
        val url = httpUrl.newBuilder()
            .addEncodedPathSegments(requestConfig.path.trimStart('/'))
            .apply {
                requestConfig.query.forEach { entry ->
                    entry.value.forEach { value -> addQueryParameter(entry.key, value) }
                }
            }
            .build()
        val requestBuilder = Request.Builder().url(url)
        if (fromByte > 0) {
            requestBuilder.header("Range", "bytes=$fromByte-")
        }
        try {
            mobileApi.client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadChunkResult.Error("Não foi possível baixar o arquivo (erro ${response.code})."))
                    return@flow
                }
                val body = response.body
                if (body == null) {
                    emit(DownloadChunkResult.Error("Resposta vazia do servidor."))
                    return@flow
                }
                body.byteStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_CHUNK_SIZE_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        emit(DownloadChunkResult.Bytes(data = buffer.copyOf(read), isLast = false))
                    }
                }
                emit(DownloadChunkResult.Bytes(data = ByteArray(0), isLast = true))
            }
        } catch (e: IOException) {
            emit(DownloadChunkResult.Error("Falha de conexão. Verifique a rede e tente novamente."))
        }
    }.flowOn(Dispatchers.IO)

    /** Single call site into `POST /files/upload/init`. */
    open suspend fun startUpload(destDir: String, filename: String, totalSize: Long): UploadSessionResult = try {
        val response = mobileApi.initUpload(
            UploadInitRequest(destDir = destDir, filename = filename, totalSize = totalSize),
        )
        UploadSessionResult.Started(sessionId = response.sessionId)
    } catch (e: ClientException) {
        val (reason, retryable) = transferErrorFor(e.statusCode)
        UploadSessionResult.Error(reason, retryable = retryable)
    } catch (e: ServerException) {
        UploadSessionResult.Error("O servidor está indisponível no momento.", retryable = true)
    } catch (e: IOException) {
        UploadSessionResult.Error("Falha de conexão. O envio continua quando a rede voltar.", retryable = true)
    } catch (e: IllegalStateException) {
        UploadSessionResult.Error("Erro de configuração ao iniciar o envio.", retryable = false)
    } catch (e: UnsupportedOperationException) {
        UploadSessionResult.Error("Resposta inesperada do servidor.", retryable = false)
    } catch (e: Exception) {
        UploadSessionResult.Error("Não foi possível iniciar o envio.", retryable = false)
    }

    /**
     * Single call site into `POST /files/upload/chunk`. [data] is briefly
     * staged as a file in [chunkStagingDir] because the generated
     * `uploadChunk` operation is typed to a `java.io.File` body (see
     * [downloadRange]'s doc for the analogous reason on the download side).
     *
     * Returns [UploadChunkResult.Accepted] only when the server wrote the chunk
     * (HTTP 2xx); anything else becomes [UploadChunkResult.Rejected] with the
     * sentence and the `retryable` that [transferErrorFor] decides — this is
     * where "disk full" stops looking like "flaky network".
     */
    open suspend fun uploadChunk(sessionId: String, offset: Long, data: ByteArray): UploadChunkResult {
        val stagedChunk = File.createTempFile("upload-chunk-", ".bin", chunkStagingDir)
        return try {
            stagedChunk.writeBytes(data)
            val response = mobileApi.uploadChunkWithHttpInfo(sessionId = sessionId, offset = offset, body = stagedChunk)
            if (response.responseType == ResponseType.Success) {
                UploadChunkResult.Accepted
            } else {
                val (reason, retryable) = transferErrorFor(response.statusCode)
                UploadChunkResult.Rejected(reason, retryable = retryable)
            }
        } catch (e: IOException) {
            UploadChunkResult.Rejected("Falha de conexão. O envio continua quando a rede voltar.", retryable = true)
        } catch (e: IllegalStateException) {
            UploadChunkResult.Rejected("Erro de configuração ao enviar o arquivo.", retryable = false)
        } catch (e: Exception) {
            UploadChunkResult.Rejected("Não foi possível enviar este pedaço do arquivo.", retryable = true)
        } finally {
            stagedChunk.delete()
        }
    }

    /**
     * Single call site into `POST /files/upload/complete`. Calls
     * `completeUploadWithHttpInfo` (not the throwing `completeUpload`
     * convenience method) specifically to reach the 409's raw response
     * body -- that's where `received_bytes`/`total_size` live, mirroring
     * [FilesRepository.write]'s handling of its own 409 conflict body.
     */
    open suspend fun completeUpload(sessionId: String): UploadCompleteResult = try {
        val response = mobileApi.completeUploadWithHttpInfo(UploadCompleteRequest(sessionId = sessionId))
        when (response.responseType) {
            ResponseType.Success -> {
                val body = (response as Success<*>).data as? UploadCompleteResponse
                if (body != null) {
                    UploadCompleteResult.Success(path = body.path)
                } else {
                    UploadCompleteResult.Error("Resposta inesperada do servidor.", retryable = false)
                }
            }
            ResponseType.ClientError -> {
                val err = response as ClientError<*>
                if (err.statusCode == 409) {
                    parseIncomplete(err.body as? String)
                        ?: UploadCompleteResult.Error(
                            "O envio está incompleto, mas não foi possível determinar o progresso.",
                            retryable = true,
                        )
                } else {
                    val (reason, retryable) = transferErrorFor(err.statusCode)
                    UploadCompleteResult.Error(reason, retryable = retryable)
                }
            }
            ResponseType.ServerError -> UploadCompleteResult.Error(
                "O servidor está indisponível no momento. O envio continua quando ele voltar.",
                retryable = true,
            )
            else -> UploadCompleteResult.Error("Resposta inesperada do servidor.", retryable = false)
        }
    } catch (e: IOException) {
        UploadCompleteResult.Error("Falha de conexão. O envio continua quando a rede voltar.", retryable = true)
    } catch (e: IllegalStateException) {
        UploadCompleteResult.Error("Erro de configuração ao concluir o envio.", retryable = false)
    } catch (e: Exception) {
        UploadCompleteResult.Error("Não foi possível concluir o envio.", retryable = false)
    }
}

private const val DOWNLOAD_CHUNK_SIZE_BYTES = 64 * 1024

/**
 * Decodes the raw 409 body into [UploadCompleteResult.Incomplete] using the
 * same `Json` instance the generated client itself uses for every other
 * response -- never a second, ad hoc JSON configuration (mirrors
 * [FilesRepository]'s `parseConflict`).
 */
private fun parseIncomplete(rawBody: String?): UploadCompleteResult.Incomplete? {
    if (rawBody.isNullOrBlank()) return null
    return try {
        val body = Serializer.kotlinxSerializationJson.decodeFromString(UploadIncompleteResponse.serializer(), rawBody)
        UploadCompleteResult.Incomplete(receivedBytes = body.receivedBytes, totalSize = body.totalSize)
    } catch (e: SerializationException) {
        null
    }
}
