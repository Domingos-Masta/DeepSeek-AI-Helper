package com.deepseek.plugin.api

import com.google.gson.annotations.SerializedName

// --- Request models ---

data class ChatRequest(
    val model: String = "deepseek-v4-flash",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false,
    @SerializedName("top_p") val topP: Double = 1.0,
    @SerializedName("frequency_penalty") val frequencyPenalty: Double = 0.0,
    @SerializedName("presence_penalty") val presencePenalty: Double = 0.0
)

data class ChatMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String,
    /** The model's deep reasoning process (reasoning_content), only has a value when role="assistant" */
    val reasoning: String? = null,
    /**
     * Optional structured content used by provider-native multimodal APIs.
     * [content] remains the persisted/display fallback for backward compatibility.
     */
    val parts: List<ChatContentPart>? = null,
    /** Token usage for this assistant response; nullable for old sessions and unsupported providers. */
    val usage: Usage? = null
)

enum class ChatContentType {
    TEXT,
    IMAGE
}

data class ChatContentPart(
    val type: ChatContentType,
    val text: String? = null,
    /** Complete data URI, for example data:image/png;base64,... */
    val dataUri: String? = null,
    val mediaType: String? = null,
    val name: String? = null
)

// ── Multimodal message (supports text + image_url mixed content) ──

data class MultimodalContent(
    val type: String,  // "text" | "image_url"
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrlPart? = null
)

data class ImageUrlPart(
    val url: String  // base64 data URI
)

data class MultimodalMessage(
    val role: String,
    val content: List<MultimodalContent>
)

// --- Response models ---

data class ChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: ChatMessage?,
    val delta: Delta?,
    @SerializedName("finish_reason") val finishReason: String?
)

data class Delta(
    val role: String? = null,
    val content: String? = null,
    /** Deep reasoning content sent in the SSE stream by models such as DeepSeek, mutually exclusive with content */
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

// --- FIM Completion models ---

data class FimRequest(
    val model: String = "deepseek-v4-flash",
    val prompt: String,
    val suffix: String? = null,
    @SerializedName("max_tokens") val maxTokens: Int = 256,
    val temperature: Double = 0.0,
    @SerializedName("top_p") val topP: Double = 0.95,
    @SerializedName("frequency_penalty") val frequencyPenalty: Double = 0.0,
    @SerializedName("presence_penalty") val presencePenalty: Double = 0.0,
    val stop: List<String>? = null,
    val stream: Boolean = false
)

data class FimResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<FimChoice>,
    val usage: Usage?
)

data class FimChoice(
    val index: Int,
    val text: String,
    @SerializedName("finish_reason") val finishReason: String?
)

/** A single chunk in the FIM streaming SSE response */
data class FimStreamChunk(
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<FimStreamChoice>? = null,
    val usage: Usage? = null
)

data class FimStreamChoice(
    val index: Int? = null,
    val text: String? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

// --- Streaming SSE chunk ---

data class StreamChunk(
    val id: String?,
    val `object`: String?,
    val created: Long?,
    val model: String?,
    val choices: List<Choice>?,
    val usage: Usage?
)
