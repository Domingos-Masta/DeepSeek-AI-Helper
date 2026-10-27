package com.deepseek.plugin.api

import java.io.IOException

/**
 * Unified base class for plugin exceptions.
 *
 * All exceptions thrown within the plugin should inherit from this class to ensure a unified handling path.
 *
 * @property severity Severity level
 * @property userMessage Friendly message shown to the user (Chinese)
 * @property techDetail Technical details recorded to the log
 */
open class DeepSeekPluginException(
    message: String,
    cause: Throwable? = null,
    val severity: Severity = Severity.ERROR,
    val userMessage: String = message,
    val techDetail: String = ""
) : RuntimeException(message, cause) {

    enum class Severity { WARN, ERROR, FATAL }

    fun toLogString(): String = buildString {
        append("[${severity.name}] $userMessage")
        if (techDetail.isNotBlank()) append(" | $techDetail")
        cause?.let { append(" | Caused by: ${it.message}") }
    }
}

// ============== Concrete exception types ==============

/**
 * API call failed (network error, HTTP error status code).
 */
class ApiException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
    severity: Severity = Severity.ERROR,
    userMessage: String = message
) : DeepSeekPluginException(message, cause, severity, userMessage)

/**
 * API rate limiting triggered (HTTP 429).
 */
class RateLimitException(
    message: String = "Too many requests, please try again later",
    retryAfterMs: Long = 60_000L,
    severity: Severity = Severity.WARN,
    userMessage: String = "Too many requests, please try again later"
) : DeepSeekPluginException(message, null, severity, userMessage) {
    val retryAfterMs: Long = retryAfterMs
}

/**
 * Session persistence exception.
 */
class SessionException(
    message: String,
    cause: Throwable? = null,
    severity: Severity = Severity.ERROR,
    userMessage: String = "Failed to save chat history, please check disk space"
) : DeepSeekPluginException(message, cause, severity, userMessage)

/**
 * Agent file operation exception.
 */
class AgentException(
    message: String,
    cause: Throwable? = null,
    severity: Severity = Severity.ERROR,
    userMessage: String = "Agent file operation failed"
) : DeepSeekPluginException(message, cause, severity, userMessage)

/**
 * Configuration error (API Key not set, etc.).
 */
class ConfigException(
    message: String,
    severity: Severity = Severity.WARN,
    userMessage: String = message
) : DeepSeekPluginException(message, null, severity, userMessage)

// ============== Extension / utility methods ==============

/** Convenience method to wrap a regular Exception as a plugin exception */
fun Throwable.toPluginException(
    userMessage: String = this.message ?: "Operation failed",
    severity: DeepSeekPluginException.Severity = DeepSeekPluginException.Severity.ERROR
): DeepSeekPluginException {
    return when (this) {
        is DeepSeekPluginException -> this
        is IOException -> ApiException(
            message = this.message ?: "Network error",
            cause = this,
            userMessage = userMessage
        )
        else -> DeepSeekPluginException(
            message = this.message ?: userMessage,
            cause = this,
            severity = severity,
            userMessage = userMessage
        )
    }
}
