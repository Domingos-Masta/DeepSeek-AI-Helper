package com.deepseek.plugin.api

/**
 * Unified error domain for chat, completion, and agent flows.
 * A sealed hierarchy keeps error handling explicit without throwing
 * protocol-specific exceptions across module boundaries.
 */
sealed class ChatError(
    open val userMessage: String,
    open val cause: Throwable? = null
) {
    /** The error occurred before a request could be sent. */
    data class Configuration(
        override val userMessage: String,
        val configKey: String? = null
    ) : ChatError(userMessage)

    /** Network/HTTP failure. */
    data class Network(
        override val userMessage: String,
        val httpCode: Int? = null,
        override val cause: Throwable? = null,
        val retryable: Boolean = false
    ) : ChatError(userMessage, cause)

    /** HTTP 429 or server-side throttling. */
    data class RateLimited(
        override val userMessage: String = "Too many requests, please try again later",
        val retryAfterMs: Long = 60_000L,
        override val cause: Throwable? = null
    ) : ChatError(userMessage, cause)

    /** The stream ended before a complete response was received. */
    data class IncompleteStream(
        override val userMessage: String = "Incomplete response, please try again",
        val partialText: String = "",
        override val cause: Throwable? = null
    ) : ChatError(userMessage, cause)

    /** The response was cancelled by the user. */
    data object Cancelled : ChatError("Cancelled")

    /** The response was malformed and could not be parsed. */
    data class MalformedResponse(
        override val userMessage: String = "The model returned an unparseable response",
        override val cause: Throwable? = null
    ) : ChatError(userMessage, cause)

    /** Permission denied for a local file operation. */
    data class PermissionDenied(
        override val userMessage: String,
        val path: String? = null
    ) : ChatError(userMessage)

    /** Fallback for unexpected failures. */
    data class Unknown(
        override val userMessage: String = "An unknown error occurred",
        override val cause: Throwable? = null
    ) : ChatError(userMessage, cause)

    fun isRetryable(): Boolean = when (this) {
        is Network -> retryable
        is RateLimited -> true
        is IncompleteStream -> true
        is Unknown -> false
        else -> false
    }
}

/** Maps a [Throwable] into a [ChatError] using the plugin exception hierarchy. */
fun Throwable.toChatError(userMessage: String = this.message ?: "Operation failed"): ChatError = when (this) {
    is ApiException -> ChatError.Network(
        userMessage = userMessage,
        httpCode = httpCode,
        cause = this,
        retryable = httpCode == 429 || (httpCode != null && httpCode >= 500)
    )
    is RateLimitException -> ChatError.RateLimited(
        userMessage = userMessage,
        retryAfterMs = retryAfterMs,
        cause = this
    )
    is ConfigException -> ChatError.Configuration(userMessage = userMessage)
    is AgentException -> ChatError.PermissionDenied(userMessage = userMessage)
    is SessionException -> ChatError.Unknown(userMessage = userMessage, cause = this)
    is DeepSeekPluginException -> ChatError.Unknown(userMessage = this.userMessage, cause = this)
    else -> ChatError.Unknown(userMessage = userMessage, cause = this)
}