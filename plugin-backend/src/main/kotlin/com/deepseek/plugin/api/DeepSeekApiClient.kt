package com.deepseek.plugin.api

import com.deepseek.plugin.api.SettingsSnapshot
import com.deepseek.plugin.api.TriggerMode
import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.settings.readClaudeModelMapping
import com.deepseek.plugin.settings.toSnapshot
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

class DeepSeekApiClient {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()

        /** Anthropic Messages API version header */
        private const val ANTHROPIC_API_VERSION = "2023-06-01"

        /** Maximum retry count */
        private const val MAX_RETRIES = 2
        /** Initial backoff delay (ms) */
        private const val BASE_DELAY_MS = 500L

        /**
         * Determines whether an error is retryable:
         * - IOException (network-layer failure, e.g. connection reset, DNS timeout)
         * - HTTP 429 (rate limiting)
         * - HTTP 5xx (server error)
         */
        private fun isRetryable(error: Throwable, httpCode: Int?): Boolean {
            if (error is IOException) return true
            if (error is RateLimitException) return true
            return httpCode != null && httpCode >= 500
        }
    }

    /**
     * Executor with exponential-backoff retry.
     * Retries only errors judged retryable by [isRetryable].
     */
    private fun <T> retryWithBackoff(
        maxRetries: Int = MAX_RETRIES,
        block: () -> Result<T>
    ): Result<T> {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            val result = block()
            result.fold(
                onSuccess = { return result },
                onFailure = { error ->
                    lastError = error
                    val httpCode = extractHttpCode(error)
                    if (attempt < maxRetries - 1 && isRetryable(error, httpCode)) {
                        val delayMs = BASE_DELAY_MS * (1L shl attempt) // 1s, 2s, 4s
                        try {
                            Thread.sleep(delayMs)
                        } catch (_: InterruptedException) {
                            return Result.failure(error)
                        }
                    } else {
                        return result // Not retryable or maximum attempts reached
                    }
                }
            )
        }
        return Result.failure(lastError ?: IOException("Retry exhausted"))
    }

    /** Attempts to extract the HTTP status code from an exception */
    private fun extractHttpCode(error: Throwable): Int? {
        val msg = error.message ?: return null
        val match = Regex("HTTP (\\d+)").find(msg)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // ── Provider helpers ──
    private val syncClient get() = HttpClientProvider.chatSyncClient
    /** Shared client with relaxed timeouts for streaming SSE calls */
    private val streamClient get() = HttpClientProvider.SHARED_CLIENT
    /** Short-timeout client used for FIM completions */
    private val completionClient get() = HttpClientProvider.completionClient

    // ── Provider helpers (strategy pattern) ──
    private fun provider(settings: DeepSeekSettings): LlmProvider =
        LlmProviderRegistry.get(settings.provider)

    // ============ Non-streaming calls (Agent Actions) ============

    fun chatSync(systemPrompt: String, userMessage: String): Result<String> {
        val settings = DeepSeekSettings.instance
        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userMessage)
        )
        return chatSync(settings, messages)
    }

    fun chatSync(messages: List<ChatMessage>): Result<String> {
        return chatSync(DeepSeekSettings.instance, messages)
    }

    private fun chatSync(settings: DeepSeekSettings, messages: List<ChatMessage>): Result<String> {
        val prov = provider(settings)
        val temp = prov.temperature ?: settings.temperature
        return chatSyncWithExplicitConfig(
            baseUrl = prov.baseUrl(settings.toSnapshot()),
            apiKey = prov.apiKey(settings.toSnapshot()),
            model = prov.model(settings.toSnapshot()),
            temperature = temp,
            maxTokens = settings.maxTokens,
            messages = messages,
            protocol = prov.protocol,
            reasoningEffort = settings.codexReasoningEffort.takeIf { prov.id == "codex" }
        )
    }

    /**
     * Synchronous call — uses an explicitly specified API config (for multi-agent task division).
     */
    fun chatSyncWithExplicitConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double = 0.7,
        maxTokens: Int = 4096,
        messages: List<ChatMessage>,
        protocol: String = "openai",
        reasoningEffort: String? = null
    ): Result<String> {
        // H3: Request rate-limit check
        if (!HttpClientProvider.chatRateLimiter.tryAcquire()) {
            return Result.failure(RateLimitException())
        }
        // Anthropic native Messages API protocol (cc-switch and other third-party Anthropic-compatible relays)
        if (protocol == "anthropic") {
            return anthropicChatSync(baseUrl, apiKey, model, temperature, maxTokens, messages)
        }
        // OpenAI Responses API protocol (Codex CLI direct-connect mode, cc-switch provider native Responses)
        if (protocol == "codex-responses") {
            return codexResponsesChatSync(baseUrl, apiKey, model, temperature, maxTokens, messages, reasoningEffort)
        }
        return retryWithBackoff {
            val request = ChatRequest(
                model = model,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false
            )

            val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            try {
                syncClient.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val errMsg = tryParseError(responseBody, response.code)
                        val ex = if (response.code == 429) {
                            RateLimitException()
                        } else {
                            ApiException("API error ${response.code}: $errMsg", httpCode = response.code)
                        }
                        Result.failure(ex)
                    } else {
                        val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                        val content = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                        if (content.isBlank()) Result.failure(ApiException("Chat API returned an empty response"))
                        else Result.success(content)
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    // ============ Streaming calls (Chat Panel) ============

    /**
     * Streaming call — uses the default config in settings (model/Provider).
     * Keeps the existing interface unchanged.
     */
    fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit,
        onReasoningToken: ((String) -> Unit)? = null
    ): EventSource {
        val settings = DeepSeekSettings.instance
        val prov = provider(settings)
        val temp = prov.temperature ?: settings.temperature
        return chatStreamWithExplicitConfig(
            baseUrl = prov.baseUrl(settings.toSnapshot()),
            apiKey = prov.apiKey(settings.toSnapshot()),
            model = prov.model(settings.toSnapshot()),
            temperature = temp,
            maxTokens = settings.maxTokens,
            messages = messages,
            protocol = prov.protocol,
            reasoningEffort = settings.codexReasoningEffort.takeIf { prov.id == "codex" },
            onToken = onToken,
            onComplete = onComplete,
            onError = onError,
            onReasoningToken = onReasoningToken
        )
    }

    /**
     * Streaming call — uses an explicitly specified API config (for multi-agent task division; each agent uses a different model/Provider).
     *
     * @param baseUrl  API base URL (e.g. "https://api.deepseek.com/v1")
     * @param apiKey   API Key
     * @param model    Model name (e.g. "deepseek-v4-pro", "deepseek-v4-flash", "agnes-2.5-flash")
     * @param temperature Temperature
     * @param maxTokens Maximum token count
     */
    fun chatStreamWithExplicitConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double = 0.7,
        maxTokens: Int = 4096,
        messages: List<ChatMessage>,
        protocol: String = "openai",
        reasoningEffort: String? = null,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit,
        onReasoningToken: ((String) -> Unit)? = null
    ): EventSource {
        // Request rate-limit check
        if (!HttpClientProvider.chatRateLimiter.tryAcquire()) {
            onError(RateLimitException())
            return object : EventSource {
                override fun cancel() {}
                override fun request() = Request.Builder().url("$baseUrl/chat/completions").build()
            }
        }
        // Anthropic native Messages API protocol (cc-switch and other third-party Anthropic-compatible relays)
        if (protocol == "anthropic") {
            return anthropicChatStream(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                messages = messages,
                onToken = onToken,
                onComplete = onComplete,
                onError = onError,
                onReasoningToken = onReasoningToken
            )
        }
        // OpenAI Responses API protocol (Codex CLI direct-connect mode, cc-switch provider native Responses)
        if (protocol == "codex-responses") {
            return codexResponsesChatStream(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                messages = messages,
                reasoningEffort = reasoningEffort,
                onToken = onToken,
                onComplete = onComplete,
                onError = onError
            )
        }
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = true
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val factory = EventSources.createFactory(streamClient)
        val fullResponse = StringBuilder()
        var lastUsage: Usage? = null
        var completed = false

        return factory.newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (!completed) {
                        completed = true
                        onComplete(fullResponse.toString(), lastUsage)
                    }
                    return
                }
                try {
                    val chunk = gson.fromJson(data, StreamChunk::class.java)
                    val delta = chunk?.choices?.firstOrNull()?.delta
                    val content = delta?.content ?: ""
                    if (content.isNotEmpty()) {
                        fullResponse.append(content)
                        onToken(content)
                    }
                    val reasoningContent = delta?.reasoningContent ?: ""
                    if (reasoningContent.isNotEmpty()) {
                        onReasoningToken?.invoke(reasoningContent)
                    }
                    chunk?.usage?.let { lastUsage = it }
                } catch (_: Exception) {}
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed) return
                completed = true
                val bodyStr = response?.body?.string() ?: ""
                val errMsg = if (response != null) {
                    "API error ${response.code}: ${tryParseError(bodyStr, response.code)}"
                } else {
                    t?.message ?: "Unknown error"
                }
                onError(ApiException(errMsg, httpCode = response?.code))
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed) {
                    completed = true
                    onComplete(fullResponse.toString(), lastUsage)
                }
            }
        })
    }

    // ============ FIM Completion (code completion) ============

    /**
     * FIM (Fill-in-the-Middle) code completion — uses the DeepSeek /v1/completions FIM endpoint.
     * Asynchronous callback mode, used by CompletionContributor.
     *
     * @param prefix Text before the cursor
     * @param suffix Text after the cursor
     * @param language Programming language name
     * @param fileContext File-level context (package declarations, imports)
     * @param mode Trigger mode — AUTO uses low temperature + short output, MANUAL uses high temperature + long output
     * @param onResult Callback (invoked off the EDT; returns the completion text or null)
     */
    fun completionFim(
        prefix: String,
        suffix: String,
        language: String,
        fileContext: String,
        mode: TriggerMode = TriggerMode.AUTO,
        onResult: (String?) -> Unit
    ) {
        // H3: FIM request rate-limit check (max 60 per minute)
        if (!HttpClientProvider.completionRateLimiter.tryAcquire()) {
            onResult(null) // Silently skip when rate-limited
            return
        }
        val settings = DeepSeekSettings.instance
        val prompt = buildFimPrompt(prefix, suffix, language, fileContext)

        // Select different temperature/maxTokens/stop based on the trigger mode
        val temperature = if (mode == TriggerMode.MANUAL)
            settings.completionManualTemperature
        else
            0.0

        val maxTokens = if (mode == TriggerMode.MANUAL)
            settings.completionManualMaxTokens
        else
            settings.completionMaxTokens

        // MANUAL mode allows longer output without early stopping
        val stop = if (mode == TriggerMode.MANUAL)
            null
        else
            listOf("\n\n", "\r\n\r\n")

        val request = FimRequest(
            model = settings.completionModel.ifBlank { provider(settings).model(settings.toSnapshot()) },
            prompt = prompt,
            suffix = suffix,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 0.95,
            stop = stop
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings.toSnapshot())}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings.toSnapshot())}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        completionClient.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val suggestion = try {
                    if (!response.isSuccessful) { response.close(); return@onResponse onResult(null) }
                    val bodyStr = response.body?.string() ?: ""
                    val completionsResponse = gson.fromJson(bodyStr, FimResponse::class.java)
                    completionsResponse.choices.firstOrNull()?.text?.trim()
                } catch (_: Exception) { null } finally { response.close() }
                onResult(suggestion)
            }
        })
    }

    /**
     * Streaming FIM completion — returns results token by token over SSE; the first token is shown immediately.
     *
     * @param onToken    Callback for each new token received (may come from a background thread)
     * @param onComplete Callback when all tokens have been received (includes full text + usage)
     * @param onError    Callback on error
     * @return EventSource handle that can be used to cancel
     */
    fun completionFimStream(
        prefix: String,
        suffix: String,
        language: String,
        fileContext: String,
        mode: TriggerMode = TriggerMode.AUTO,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String) -> Unit,
        onError: (Throwable) -> Unit
    ): okhttp3.sse.EventSource {
        // Rate-limit check
        if (!HttpClientProvider.completionRateLimiter.tryAcquire()) {
            onError(okhttp3.internal.http2.ConnectionShutdownException())
            return object : okhttp3.sse.EventSource {
                override fun cancel() {}
                override fun request() = Request.Builder().url("https://unknown").build()
            }
        }
        val settings = DeepSeekSettings.instance
        val prompt = buildFimPrompt(prefix, suffix, language, fileContext)

        val temperature = if (mode == TriggerMode.MANUAL)
            settings.completionManualTemperature else 0.0
        val maxTokens = if (mode == TriggerMode.MANUAL)
            settings.completionManualMaxTokens else settings.completionMaxTokens
        val stop = if (mode == TriggerMode.MANUAL)
            null else listOf("\n\n", "\r\n\r\n")

        val request = FimRequest(
            model = settings.completionModel.ifBlank { provider(settings).model(settings.toSnapshot()) },
            prompt = prompt,
            suffix = suffix,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 0.95,
            stop = stop,
            stream = true
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)
        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings.toSnapshot())}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings.toSnapshot())}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val factory = okhttp3.sse.EventSources.createFactory(completionClient)
        val fullResponse = StringBuilder()
        var completed = false

        return factory.newEventSource(httpRequest, object : okhttp3.sse.EventSourceListener() {
            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (!completed) {
                        completed = true
                        onComplete(fullResponse.toString())
                    }
                    return
                }
                try {
                    val chunk = gson.fromJson(data, FimStreamChunk::class.java)
                    val text = chunk?.choices?.firstOrNull()?.text ?: ""
                    if (text.isNotEmpty()) {
                        fullResponse.append(text)
                        onToken(text)
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable?, response: okhttp3.Response?) {
                if (completed) return
                completed = true
                onError(t ?: Exception("FIM stream failed: HTTP ${response?.code}"))
            }

            override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                if (!completed) {
                    completed = true
                    onComplete(fullResponse.toString())
                }
            }
        })
    }

    /**
     * Builds the FIM prompt — injects language, file context, and completion instructions.
     */
    private fun buildFimPrompt(prefix: String, suffix: String, language: String, fileContext: String): String {
        val sb = StringBuilder()
        // Domain restriction (first principle, must not be changed)
        sb.appendLine(DOMAIN_RESTRICTION_PROMPT)
        sb.appendLine()
        // System instruction: tell the model what it is doing
        sb.append("You are a ${language} code completion engine. Given code before and after the cursor, ")
        sb.append("output ONLY the tokens that naturally belong between them.\n")
        sb.append("Rules: no explanation, no wrapping in markdown, no repeating existing code.\n")
        if (fileContext.isNotBlank()) {
            sb.append("File context:\n$fileContext\n\n")
        }
        sb.append("Code before cursor (prefix):\n$prefix\n\n")
        sb.append("Code after cursor (suffix):\n$suffix\n\n")
        sb.append("Completion:")
        return sb.toString()
    }

    /**
     * Synchronous FIM completion (compatibility with the old interface).
     */
    fun completion(prefix: String, suffix: String): Result<String> {
        val settings = DeepSeekSettings.instance

        val request = FimRequest(
            model = settings.completionModel.ifBlank { provider(settings).model(settings.toSnapshot()) },
            prompt = prefix,
            suffix = suffix,
            maxTokens = settings.completionMaxTokens,
            temperature = 0.0
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings.toSnapshot())}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings.toSnapshot())}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            completionClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = tryParseError(responseBody, response.code)
                    val ex = if (response.code == 429) RateLimitException()
                    else ApiException("API error ${response.code}: $errMsg", httpCode = response.code)
                    Result.failure(ex)
                } else {
                    val completionsResponse = gson.fromJson(responseBody, FimResponse::class.java)
                    val text = completionsResponse.choices.firstOrNull()?.text ?: ""
                    Result.success(text.trim())
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    // ============ Anthropic native Messages API (cc-switch and other Anthropic-compatible relays) ============

    /** Anthropic Messages API endpoint URL: {base}/v1/messages (baseUrl may already include the /v1 suffix) */
    private fun anthropicMessagesUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/').removeSuffix("/v1")
        return "$base/v1/messages"
    }

    /** Builds the Anthropic Messages API request body (system becomes a top-level field; role is only user/assistant) */
    internal fun buildAnthropicBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val msgs = JsonArray().apply {
            messages.filter { it.role != "system" }.forEach { message ->
                add(JsonObject().apply {
                    addProperty("role", message.role)
                    if (message.parts.isNullOrEmpty()) {
                        addProperty("content", message.content)
                    } else {
                        add("content", buildAnthropicContent(message))
                    }
                })
            }
        }
        val obj = JsonObject().apply {
            addProperty("model", mapAnthropicModel(model))
            addProperty("max_tokens", maxTokens)
            if (system.isNotBlank()) addProperty("system", system)
            // Anthropic does not accept temperature=0, and its range is 0~1
            if (temperature in 0.01..1.0) addProperty("temperature", temperature)
            addProperty("stream", stream)
            add("messages", msgs)
        }
        return obj.toString()
    }

    private fun buildAnthropicContent(message: ChatMessage): JsonArray = JsonArray().apply {
        if (message.content.isNotBlank()) {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", message.content)
            })
        }
        message.parts.orEmpty().forEach { part ->
            when (part.type) {
                ChatContentType.TEXT -> part.text?.takeIf { it.isNotBlank() }?.let { text ->
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                }
                ChatContentType.IMAGE -> parseDataUri(part.dataUri)?.let { (mediaType, data) ->
                    add(JsonObject().apply {
                        addProperty("type", "image")
                        add("source", JsonObject().apply {
                            addProperty("type", "base64")
                            addProperty("media_type", part.mediaType ?: mediaType)
                            addProperty("data", data)
                        })
                    })
                }
            }
        }
    }

    private fun parseDataUri(dataUri: String?): Pair<String, String>? {
        if (dataUri.isNullOrBlank() || !dataUri.startsWith("data:")) return null
        val comma = dataUri.indexOf(',')
        if (comma <= 5) return null
        val metadata = dataUri.substring(5, comma)
        if (!metadata.endsWith(";base64")) return null
        val mediaType = metadata.removeSuffix(";base64")
        val data = dataUri.substring(comma + 1)
        if (mediaType.isBlank() || data.isBlank()) return null
        return mediaType to data
    }

    /** Maps model names per cc-switch / Claude Code conventions (ANTHROPIC_DEFAULT_*_MODEL → actual model) */
    private fun mapAnthropicModel(model: String): String {
        val lower = model.lowercase()
        val key = when {
            lower.contains("sonnet") -> "sonnet"
            lower.contains("haiku") -> "haiku"
            lower.contains("opus") -> "opus"
            else -> null
        }
        return key?.let { readClaudeModelMapping()[it] } ?: model
    }

    /** Anthropic native Messages API synchronous call */
    private fun anthropicChatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>
    ): Result<String> {
        val body = buildAnthropicBody(model, messages, temperature, maxTokens, stream = false)
        val httpRequest = Request.Builder()
            .url(anthropicMessagesUrl(baseUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return retryWithBackoff {
            try {
                syncClient.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val errMsg = tryParseError(responseBody, response.code)
                        val ex = if (response.code == 429) {
                            RateLimitException()
                        } else {
                            ApiException("API error ${response.code}: $errMsg", httpCode = response.code)
                        }
                        Result.failure(ex)
                    } else {
                        val obj = gson.fromJson(responseBody, JsonObject::class.java)
                        val content = obj.getAsJsonArray("content")
                            ?.mapNotNull { it.asJsonObject.get("text")?.asString }
                            ?.joinToString("") ?: ""
                        Result.success(content)
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    /** Anthropic native Messages API streaming call (SSE: content_block_delta → delta.text / delta.thinking) */
    private fun anthropicChatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit,
        onReasoningToken: ((String) -> Unit)?
    ): EventSource {
        val body = buildAnthropicBody(model, messages, temperature, maxTokens, stream = true)
        val httpRequest = Request.Builder()
            .url(anthropicMessagesUrl(baseUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val factory = EventSources.createFactory(streamClient)
        val fullResponse = StringBuilder()
        var lastUsage: Usage? = null
        var completed = false

        return factory.newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val obj = gson.fromJson(data, JsonObject::class.java) ?: return
                    when (obj.get("type")?.asString) {
                        "message_start" -> {
                            val u = obj.getAsJsonObject("message")?.getAsJsonObject("usage")
                            val input = u?.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                            if (input > 0) lastUsage = Usage(input, 0, input)
                        }
                        "content_block_delta" -> {
                            val delta = obj.getAsJsonObject("delta")
                            when (delta.get("type")?.asString) {
                                "text_delta" -> {
                                    val text = delta.get("text")?.asString ?: ""
                                    if (text.isNotEmpty()) {
                                        fullResponse.append(text)
                                        onToken(text)
                                    }
                                }
                                "thinking_delta" -> {
                                    val thinking = delta.get("thinking")?.asString ?: ""
                                    if (thinking.isNotEmpty()) onReasoningToken?.invoke(thinking)
                                }
                            }
                        }
                        "message_delta" -> {
                            val u = obj.getAsJsonObject("usage")
                            val input = lastUsage?.promptTokens
                                ?: u?.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                                ?: 0
                            val output = u?.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                            lastUsage = Usage(input, output, input + output)
                        }
                        "message_stop" -> {
                            if (!completed) {
                                completed = true
                                if (fullResponse.isBlank()) onError(ApiException("Anthropic returned an empty response"))
                                else onComplete(fullResponse.toString(), lastUsage)
                            }
                        }
                        "error" -> {
                            if (!completed) {
                                completed = true
                                val error = obj.getAsJsonObject("error")
                                onError(ApiException(error?.get("message")?.asString ?: "Anthropic stream error"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!completed) {
                        completed = true
                        eventSource.cancel()
                        onError(ApiException("Invalid Anthropic stream event: ${e.message}"))
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed) return
                completed = true
                val bodyStr = response?.body?.string() ?: ""
                val errMsg = if (response != null) {
                    "API error ${response.code}: ${tryParseError(bodyStr, response.code)}"
                } else {
                    t?.message ?: "Unknown error"
                }
                onError(ApiException(errMsg, httpCode = response?.code))
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed) {
                    completed = true
                    onError(ApiException("Anthropic stream closed before message_stop"))
                }
            }
        })
    }

    // ============ OpenAI Responses API (Codex CLI direct-connect mode) ============

    /**
     * Builds the /v1/responses endpoint URL.
     * baseUrl may or may not include the /v1 suffix; both cases are handled uniformly:
     *   "https://api.openai.com/v1"   → "https://api.openai.com/v1/responses"
     *   "https://api.openai.com"      → "https://api.openai.com/v1/responses"
     */
    private fun codexResponsesUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1")) "$base/responses" else "$base/v1/responses"
    }

    /** Builds the Responses API request body: system → instructions, remaining messages → input[] */
    internal fun buildCodexResponsesBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
        reasoningEffort: String? = null
    ): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val input = JsonArray().apply {
            messages.filter { it.role != "system" }.forEach { msg ->
                add(JsonObject().apply {
                    addProperty("role", msg.role)
                    if (msg.parts.isNullOrEmpty()) addProperty("content", msg.content)
                    else add("content", buildCodexContent(msg))
                })
            }
        }
        return JsonObject().apply {
            addProperty("model", model)
            if (system.isNotBlank()) addProperty("instructions", system)
            add("input", input)
            addProperty("max_output_tokens", maxTokens)
            reasoningEffort?.takeIf { it in setOf("low", "medium", "high") }?.let { effort ->
                add("reasoning", JsonObject().apply { addProperty("effort", effort) })
            }
            // Codex reasoning models and compatible proxies may reject temperature.
            addProperty("stream", stream)
        }.toString()
    }

    private fun buildCodexContent(message: ChatMessage): JsonArray = JsonArray().apply {
        if (message.content.isNotBlank()) {
            add(JsonObject().apply {
                addProperty("type", if (message.role == "assistant") "output_text" else "input_text")
                addProperty("text", message.content)
            })
        }
        message.parts.orEmpty().forEach { part ->
            when (part.type) {
                ChatContentType.TEXT -> part.text?.takeIf { it.isNotBlank() }?.let { text ->
                    add(JsonObject().apply {
                        addProperty("type", if (message.role == "assistant") "output_text" else "input_text")
                        addProperty("text", text)
                    })
                }
                ChatContentType.IMAGE -> part.dataUri?.takeIf { it.startsWith("data:") }?.let { imageUrl ->
                    add(JsonObject().apply {
                        addProperty("type", "input_image")
                        addProperty("image_url", imageUrl)
                    })
                }
            }
        }
    }

    /** Extracts the output_text text from a non-streaming Responses API response */
    private fun parseCodexResponsesText(body: String): String {
        return try {
            gson.fromJson(body, JsonObject::class.java)
                .getAsJsonArray("output")
                ?.flatMap { item ->
                    item.asJsonObject.getAsJsonArray("content")
                        ?.mapNotNull { part ->
                            val p = part.asJsonObject
                            if (p.get("type")?.asString == "output_text") p.get("text")?.asString else null
                        } ?: emptyList()
                }?.joinToString("") ?: ""
        } catch (_: Exception) { "" }
    }

    /** Responses API synchronous call */
    private fun codexResponsesChatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>,
        reasoningEffort: String?
    ): Result<String> {
        val body = buildCodexResponsesBody(model, messages, temperature, maxTokens, stream = false, reasoningEffort)
        val httpRequest = Request.Builder()
            .url(codexResponsesUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return retryWithBackoff {
            try {
                syncClient.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val errMsg = tryParseError(responseBody, response.code)
                        val ex = if (response.code == 429) RateLimitException()
                        else ApiException("API error ${response.code}: $errMsg", httpCode = response.code)
                        Result.failure(ex)
                    } else {
                        val content = parseCodexResponsesText(responseBody)
                        if (content.isBlank()) Result.failure(ApiException("Codex Responses API returned an empty response"))
                        else Result.success(content)
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    /**
     * Responses API streaming call (SSE event types):
     *  - response.output_text.delta → delta field, per-token callback
     *  - response.completed         → response.usage, triggers onComplete
     *  - error                      → triggers onError
     */
    private fun codexResponsesChatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>,
        reasoningEffort: String?,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        val body = buildCodexResponsesBody(model, messages, temperature, maxTokens, stream = true, reasoningEffort)
        val httpRequest = Request.Builder()
            .url(codexResponsesUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val factory = EventSources.createFactory(streamClient)
        val fullResponse = StringBuilder()
        var lastUsage: Usage? = null
        var completed = false

        return factory.newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val obj = gson.fromJson(data, JsonObject::class.java) ?: return
                    when (obj.get("type")?.asString) {
                        "response.output_text.delta" -> {
                            val delta = obj.get("delta")?.asString ?: ""
                            if (delta.isNotEmpty()) {
                                fullResponse.append(delta)
                                onToken(delta)
                            }
                        }
                        "response.completed" -> {
                            val u = obj.getAsJsonObject("response")?.getAsJsonObject("usage")
                            if (u != null) {
                                val input = u.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                                val output = u.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                                lastUsage = Usage(input, output, input + output)
                            }
                            if (!completed) {
                                completed = true
                                if (fullResponse.isBlank()) onError(ApiException("Codex Responses API returned an empty response"))
                                else onComplete(fullResponse.toString(), lastUsage)
                            }
                        }
                        "error", "response.failed", "response.incomplete" -> {
                            if (!completed) {
                                completed = true
                                val responseError = obj.getAsJsonObject("response")?.getAsJsonObject("error")
                                val msg = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                                    ?: responseError?.get("message")?.asString
                                    ?: "Codex Responses API stream failed"
                                onError(ApiException(msg))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!completed) {
                        completed = true
                        eventSource.cancel()
                        onError(ApiException("Invalid Codex Responses stream event: ${e.message}"))
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed) return
                completed = true
                val bodyStr = response?.body?.string() ?: ""
                val errMsg = if (response != null) {
                    "API error ${response.code}: ${tryParseError(bodyStr, response.code)}"
                } else {
                    t?.message ?: "Unknown error"
                }
                onError(ApiException(errMsg, httpCode = response?.code))
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed) {
                    completed = true
                    onError(ApiException("Codex Responses stream closed before response.completed"))
                }
            }
        })
    }

    private fun tryParseError(body: String, code: Int): String {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonObject("error")?.get("message")?.asString ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
    }
}
