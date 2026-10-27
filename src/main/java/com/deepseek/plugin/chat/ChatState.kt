package com.deepseek.plugin.chat

import com.deepseek.plugin.ui.MessageBubble
import okhttp3.sse.EventSource
import javax.swing.Timer

/**
 * Finite state machine for chat state.
 *
 * Consolidates the scattered mutable fields in ChatPanel (isStreaming, currentEventSource,
 * streamBuffer, streamingBubble, firstTokenArrived, thinkingTimer)
 * into an immutable sealed class managed via [AtomicReference], making it thread-safe.
 *
 * State transitions:
 *   Idle → Streaming (user sends a message)
 *   Streaming → Idle (completion / error / cancellation)
 */
sealed class ChatState {

    /** Idle state — no request in progress */
    object Idle : ChatState()

    /** Streaming response in progress */
    data class Streaming(
        val eventSource: EventSource,
        /** Process handle held by CLI Agent mode (Claude/Codex), used to destroy it on stop; null for HTTP streams */
        val process: Process? = null,
        val buffer: StringBuilder = StringBuilder(),
        val reasoningBuffer: StringBuilder = StringBuilder(),
        val bubble: MessageBubble,
        /** Flush timer — must be stop()ed on stop to avoid touching removed components */
        val flushTimer: Timer? = null,
        /** Thinking animation timer */
        val thinkingTimer: Timer? = null,
        /** Millisecond timestamp when the request was initiated */
        val startTime: Long = System.currentTimeMillis()
    ) : ChatState()
}
