package com.vpsmanager.core.model

import java.net.URI

/**
 * Outcome of validating a raw server URL before it is ever persisted or
 * handed to a repository. Fails closed — anything that is not an
 * unambiguous, schemed, hostful absolute URL is [Invalid].
 */
sealed interface ServerUrlValidation {
    data class Valid(val normalized: String) : ServerUrlValidation
    data class Invalid(val reason: String) : ServerUrlValidation
}

/**
 * Validates a candidate server base URL. This value decides where every
 * future WebAuthn ceremony and API credential is sent, so it is treated as
 * security-relevant: `https` is required unless [allowInsecureHttp] is
 * explicitly set — the one deliberate, opt-in development exception — and
 * malformed/schemeless/hostless input is always rejected rather than
 * guessed at.
 */
fun validateServerUrl(rawUrl: String, allowInsecureHttp: Boolean = false): ServerUrlValidation {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
        return ServerUrlValidation.Invalid("Endereço vazio.")
    }
    val uri = try {
        URI(trimmed)
    } catch (e: Exception) {
        return ServerUrlValidation.Invalid("Endereço inválido.")
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    if (scheme == null || host.isNullOrBlank()) {
        return ServerUrlValidation.Invalid(
            "Informe um endereço completo, com https:// e o domínio do servidor.",
        )
    }
    when (scheme) {
        "https" -> Unit
        "http" -> if (!allowInsecureHttp) {
            return ServerUrlValidation.Invalid(
                "Este servidor precisa de https:// (http:// é permitido só para desenvolvimento local).",
            )
        }
        else -> return ServerUrlValidation.Invalid("Esquema não suportado: $scheme")
    }
    val normalized = buildString {
        append(scheme)
        append("://")
        append(host)
        if (uri.port != -1) {
            append(":")
            append(uri.port)
        }
    }
    return ServerUrlValidation.Valid(normalized)
}
