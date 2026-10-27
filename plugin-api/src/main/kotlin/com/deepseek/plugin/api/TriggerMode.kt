package com.deepseek.plugin.api

/**
 * Completion trigger mode.
 *
 * - AUTO: Automatically triggered on user input, uses cache, low temperature, fewer tokens, fast response
 * - MANUAL: Triggered by the user (Alt+P / right-click menu), skips cache, high temperature, more tokens, generates more complete output
 */
enum class TriggerMode {
    AUTO,
    MANUAL;

    companion object {
        /** Default temperature for AUTO mode */
        const val AUTO_TEMPERATURE = 0.0
        /** Default temperature for MANUAL mode */
        const val MANUAL_TEMPERATURE = 0.2
        /** Default max_tokens for AUTO mode */
        const val AUTO_MAX_TOKENS = 128
        /** Default max_tokens for MANUAL mode */
        const val MANUAL_MAX_TOKENS = 512
    }
}
