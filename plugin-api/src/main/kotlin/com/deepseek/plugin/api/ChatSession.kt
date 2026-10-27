package com.deepseek.plugin.api

/**
 * Chat session — represents the metadata and message list of a chat session.
 */
data class ChatSession(
    val name: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var totalTokens: Int = 0,
    var lastActiveTime: Long = System.currentTimeMillis()
)
